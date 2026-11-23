{ drvkit, test ? false }:
pkgs:
{ src, projectDir ? ".", lockFile ? null, roots ? [], testRoots ? [], mainClass ? null,
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
  planner = import ./planner.nix pkgs;
  memberLock = "${projectDir}/maven_install.json";
  lockSource = selectFiles "drvjava-lock" (if lockFile == null then [ memberLock "maven_install.json" ] else [ lockFile ]);
  dynamic = import (drvkit + "/dynamic.nix") { inherit pkgs; };
  classpathFor = selectedRoots: dynamic {
    name = "drvjava-classpath";
    prepare = ''
      lock=${lib.escapeShellArg (if lockFile == null then memberLock else lockFile)}
      ${lib.optionalString (lockFile == null) ''if [[ ! -f ${lockSource}/"$lock" ]]; then lock=maven_install.json; fi''}
      ${planner}/bin/drvjava-plan ${lockSource}/"$lock" \
        ${lib.optionalString (testRoots != [] && selectedRoots == []) "--explicit-roots"} ${lib.escapeShellArgs selectedRoots} > plan.json
    '';
    expression = ''
      import ${./classpath.nix} {
        pkgs = import ${pkgs.path} { system = "${pkgs.stdenv.hostPlatform.system}"; };
        jars = builtins.attrValues (builtins.fromJSON (builtins.readFile ./plan.json));
      }
    '';
  };
  classpath = classpathFor roots;
  testClasspath = if testRoots == [] then classpath else classpathFor (roots ++ testRoots);
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
  compileClasses = { name, dirs, resourcePaths, dependencyPath, requiredSources ? [] }:
    pkgs.stdenvNoCC.mkDerivation {
      inherit name;
      __contentAddressed = true;
      src = selectFiles "drvjava-source" (dirs ++ resourcePaths);
      nativeBuildInputs = [ jdk ];
      dontConfigure = true;
      dontFixup = true;
      dontInstall = true;
      buildPhase = ''
        ${compile}
        for f in ${lib.escapeShellArgs requiredSources}; do
          if [[ ! -f "$f" ]]; then echo "missing Java source: $f" >&2; exit 1; fi
        done
        compile "$out" "${dependencyPath}" ${lib.escapeShellArgs dirs}
        resources "$out" ${lib.escapeShellArgs resourcePaths}
      '';
    };
  mainClasses = compileClasses {
    name = "drvjava-main-classes";
    dirs = mainSources;
    resourcePaths = resourceDirs;
    dependencyPath = "$(cat ${classpath}/classpath)";
    requiredSources = sources;
  };
  testDependencies = "${mainClasses}:$(cat ${testClasspath}/classpath)";
  testClasses = compileClasses {
    name = "drvjava-test-classes";
    dirs = testDirs;
    resourcePaths = testResourceDirs;
    dependencyPath = testDependencies;
  };
in if test then pkgs.runCommand "drvjava-test" {
  __contentAddressed = true;
  nativeBuildInputs = [ jdk ];
} ''
  cd "$TMPDIR"
  java -ea -cp "${testClasses}:${testDependencies}" ${if testClass == null
    then "org.junit.platform.console.ConsoleLauncher execute --scan-class-path --fail-if-no-tests"
    else lib.escapeShellArg testClass} ${lib.escapeShellArgs testArgs}
  touch "$out"
'' else pkgs.runCommand "drvjava-build" {
  __contentAddressed = true;
  nativeBuildInputs = [ jdk pkgs.makeWrapper ];
} ''
  cp=$(cat ${classpath}/classpath)
  mkdir -p "$out/bin" "$out/lib" "$out/jars"
  if [[ -d ${classpath}/jars ]]; then
    rmdir "$out/jars"
    ln -s ${classpath}/jars "$out/jars"
  fi
  jar --create --file "$out/lib/project.jar" --date=1980-01-01T00:00:02Z \
    ${lib.optionalString (mainClass != null) "--main-class ${lib.escapeShellArg mainClass}"} -C ${mainClasses} .
  printf '%s' "$out/lib/project.jar''${cp:+:$cp}" > "$out/classpath"
  makeWrapper ${jdk}/bin/java "$out/bin/drvjava-run" \
    --add-flag "-cp" --add-flag "$(cat "$out/classpath")" \
    ${lib.optionalString (mainClass != null) "--add-flag ${lib.escapeShellArg mainClass}"}
''
