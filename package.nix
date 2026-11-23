{ drvkit, test ? false }:
pkgs:
{ src, projectDir ? ".", lockFile ? null, roots ? [], mainClass ? null,
  sourceDirs ? [ "${projectDir}/src/main/java" ], extraSourceDirs ? [], sources ? [],
  resourceDirs ? [ "${projectDir}/src/main/resources" ],
  testDirs ? [ "${projectDir}/src/test/java" ], testResourceDirs ? [ "${projectDir}/src/test/resources" ],
  testClass ? null, testArgs ? [], jdk ? pkgs.jdk_headless }:
let
  inherit (pkgs) lib;
  mainSources = sourceDirs ++ extraSourceDirs ++ sources;
  root = toString (src.origSrc or src);
  absolute = p: let rel = lib.removePrefix "./" (lib.path.subpath.normalise p);
    in if rel == "." then root else root + "/" + rel;
  selectFiles = name: paths: let selected = map absolute paths;
    in if lib.isDerivation src then src else lib.cleanSourceWith {
      inherit src name;
      filter = path: type:
        let file = toString path;
        in lib.any (wanted: file == wanted || lib.hasPrefix (wanted + "/") file ||
          (type == "directory" && lib.hasPrefix (file + "/") wanted)) selected;
    };
  source = selectFiles "drvjava-source" (mainSources ++ resourceDirs ++ lib.optionals test (testDirs ++ testResourceDirs));
  planner = import ./planner.nix pkgs;
  memberLock = "${projectDir}/maven_install.json";
  lockSource = selectFiles "drvjava-lock" (if lockFile == null then [ memberLock "maven_install.json" ] else [ lockFile ]);
  dynamic = import (drvkit + "/dynamic.nix") { inherit pkgs; };
  classpath = dynamic {
    name = "drvjava-classpath";
    prepare = ''
      lock=${lib.escapeShellArg (if lockFile == null then memberLock else lockFile)}
      ${lib.optionalString (lockFile == null) ''if [[ ! -f ${lockSource}/"$lock" ]]; then lock=maven_install.json; fi''}
      ${planner}/bin/drvjava-plan ${lockSource}/"$lock" ${lib.escapeShellArgs roots} > plan.json
    '';
    expression = ''
      import ${./classpath.nix} {
        pkgs = import ${pkgs.path} { system = "${pkgs.stdenv.hostPlatform.system}"; };
        jars = builtins.attrValues (builtins.fromJSON (builtins.readFile ./plan.json));
      }
    '';
  };
  compile = ''
    compile() {
      local dest=$1 cp=$2
      shift 2
      mkdir -p "$dest"
      local -a files=()
      for path in "$@"; do
        if [[ -d "$path" ]]; then
          while IFS= read -r -d "" f; do files+=("$f"); done < <(find "$path" -type f -name '*.java' -print0 | sort -z)
        elif [[ -f "$path" ]]; then
          files+=("$path")
        fi
      done
      if (( ''${#files[@]} )); then
        javac -encoding UTF-8 -cp "$cp" -d "$dest" "''${files[@]}"
      fi
    }
    resources() {
      local dest=$1
      shift
      for path in "$@"; do
        if [[ -d "$path" ]]; then cp -r "$path"/. "$dest/"; fi
      done
    }
  '';
in pkgs.stdenvNoCC.mkDerivation {
  name = if test then "drvjava-test" else "drvjava-build";
  src = source;
  nativeBuildInputs = [ jdk pkgs.makeWrapper ];
  dontConfigure = true;
  dontFixup = true;
  dontInstall = true;
  buildPhase = ''
    ${compile}
    cp=$(cat ${classpath}/classpath)
    classes="$TMPDIR/main-classes"
    for f in ${lib.escapeShellArgs sources}; do
      if [[ ! -f "$f" ]]; then echo "missing Java source: $f" >&2; exit 1; fi
    done
    compile "$classes" "$cp" ${lib.escapeShellArgs mainSources}
    resources "$classes" ${lib.escapeShellArgs resourceDirs}
  '' + (if test then ''
    tests="$TMPDIR/test-classes"
    compile "$tests" "$classes:$cp" ${lib.escapeShellArgs testDirs}
    resources "$tests" ${lib.escapeShellArgs testResourceDirs}
    cd "$TMPDIR"
    java -ea -cp "$tests:$classes:$cp" ${if testClass == null
      then "org.junit.platform.console.ConsoleLauncher execute --scan-class-path --fail-if-no-tests"
      else lib.escapeShellArg testClass} ${lib.escapeShellArgs testArgs}
    touch "$out"
  '' else ''
    mkdir -p "$out/bin" "$out/lib" "$out/jars"
    if [[ -d ${classpath}/jars ]]; then
      rmdir "$out/jars"
      ln -s ${classpath}/jars "$out/jars"
    fi
    jar --create --file "$out/lib/project.jar" --date=1980-01-01T00:00:02Z \
      ${lib.optionalString (mainClass != null) "--main-class ${lib.escapeShellArg mainClass}"} -C "$classes" .
    printf '%s' "$out/lib/project.jar''${cp:+:$cp}" > "$out/classpath"
    makeWrapper ${jdk}/bin/java "$out/bin/drvjava-run" \
      --add-flag "-cp" --add-flag "$(cat "$out/classpath")" \
      ${lib.optionalString (mainClass != null) "--add-flag ${lib.escapeShellArg mainClass}"}
  '');
}
