{ drvkit ? null, nixpkgs ? null, test ? false }:
pkgs:
{ src, projectDir ? ".", lockFile ? null, roots ? [], testRoots ? [], mainClass ? null,
  sourceDirs ? [ "${projectDir}/src/main/java" ], extraSourceDirs ? [], sources ? [],
  resourceDirs ? [ "${projectDir}/src/main/resources" ],
  testDirs ? [ "${projectDir}/src/test/java" ], testResourceDirs ? [ "${projectDir}/src/test/resources" ],
  testClass ? null, testArgs ? [], jdk ? pkgs.jdk_headless }:
let
  inherit (pkgs) lib;
  system = pkgs.stdenv.hostPlatform.system;

  mkOverridableDrv = attrs: let d = builtins.derivation attrs; in d // {
    overrideAttrs = f: mkOverridableDrv (attrs // (if builtins.isFunction f then f attrs else f));
  };

  # Input-addressed slim JDK slice: drops jmods/ (~307 MiB) and lib/src.zip (~52 MiB),
  # rewriting embedded store paths in-place (exact same name length) so there is zero
  # runtime reference to the full openjdk-headless store path.
  jdkNameLen = builtins.stringLength jdk.name;
  slimName = let base = "drvjava-jdk-slim-"; in
    if jdkNameLen > builtins.stringLength base
    then base + lib.concatStrings (builtins.genList (_: "0") (jdkNameLen - builtins.stringLength base))
    else builtins.substring 0 jdkNameLen "drvjava-jdk-slim";

  jdkSlim = if pkgs.stdenv.hostPlatform.isLinux && jdk == pkgs.jdk_headless then builtins.derivation {
    name = slimName;
    inherit system;
    builder = "${jdk}/bin/java";
    args = [ ./DrvJava.java "slice-jdk" ];
    FULL_JDK = "${jdk}";
    allowSubstitutes = true;
  } else jdk;

  # Input-addressed precompiled helper JAR (zero external crates, ~0.5 s compile once).
  toolJar = builtins.derivation {
    name = "drvjava-tool";
    inherit system;
    builder = "${pkgs.bashNonInteractive}/bin/bash";
    args = [ "-e" "-c" ''
      "${jdkSlim}/bin/javac" -encoding UTF-8 -d "$TMPDIR/classes" "${./DrvJava.java}"
      "${pkgs.coreutils}/bin/mkdir" -p "$out"
      "${jdkSlim}/bin/jar" --create --file "$out/drvjava.jar" --date=1980-01-01T00:00:02Z -C "$TMPDIR/classes" .
    '' ];
    allowSubstitutes = true;
  };

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

  memberLock = "${projectDir}/maven_install.json";
  lockSource = selectFiles "drvjava-lock" (if lockFile == null then [ memberLock "maven_install.json" ] else [ lockFile ]);

  classpathFor = selectedRoots: let
    planner = builtins.derivation {
      name = "drvjava-classpath.drv";
      inherit system;
      builder = "${jdkSlim}/bin/java";
      args = [ "-cp" "${toolJar}/drvjava.jar" "DrvJava" "plan" ];
      CONFIG_JSON = builtins.toJSON {
        lockSource = "${lockSource}";
        inherit lockFile memberLock system;
        roots = selectedRoots;
        explicitRoots = testRoots != [] && selectedRoots == [];
        jdk = "${jdkSlim}";
        tool = "${toolJar}";
      };
      requiredSystemFeatures = [ "recursive-nix" ];
      __contentAddressed = true;
      outputHashMode = "text";
      outputHashAlgo = "sha256";
    };
  in builtins.outputOf planner.outPath "out";

  classpath = classpathFor roots;
  testClasspath = if testRoots == [] then classpath else classpathFor (roots ++ testRoots);

  compileClasses = { name, dirs, resourcePaths, classpathDir, extraCp ? "", requiredSources ? [] }:
    builtins.derivation {
      inherit name system;
      builder = "${jdkSlim}/bin/java";
      args = [ "-cp" "${toolJar}/drvjava.jar" "DrvJava" "compile" ];
      SRC = "${selectFiles "drvjava-source" (dirs ++ resourcePaths)}";
      COMPILE_JSON = builtins.toJSON {
        inherit dirs resourcePaths requiredSources extraCp;
        classpathFile = "${classpathDir}/classpath";
      };
      __contentAddressed = true;
      outputHashMode = "recursive";
      outputHashAlgo = "sha256";
    };

  mainClasses = compileClasses {
    name = "drvjava-main-classes";
    dirs = mainSources;
    resourcePaths = resourceDirs;
    classpathDir = classpath;
    requiredSources = sources;
  };

  testClasses = compileClasses {
    name = "drvjava-test-classes";
    dirs = testDirs;
    resourcePaths = testResourceDirs;
    classpathDir = testClasspath;
    extraCp = "${mainClasses}";
  };
in if test then mkOverridableDrv {
  name = "drvjava-test";
  inherit system;
  builder = "${jdkSlim}/bin/java";
  args = [ "-cp" "${toolJar}/drvjava.jar" "DrvJava" "test" ];
  TEST_CLASSES = "${testClasses}";
  MAIN_CLASSES = "${mainClasses}";
  TEST_CLASSPATH_DIR = testClasspath;
  JDK = "${jdkSlim}";
  TEST_JSON = builtins.toJSON { inherit testClass testArgs; };
  __contentAddressed = true;
  outputHashMode = "recursive";
  outputHashAlgo = "sha256";
} else mkOverridableDrv {
  name = "drvjava-build";
  inherit system;
  builder = "${jdkSlim}/bin/java";
  args = [ "-cp" "${toolJar}/drvjava.jar" "DrvJava" "build" ];
  CLASSPATH_DIR = classpath;
  MAIN_CLASSES = "${mainClasses}";
  JDK = "${jdkSlim}";
  BASH = "${pkgs.bashNonInteractive}";
  MAIN_CLASS = if mainClass == null then "" else mainClass;
  __contentAddressed = true;
  outputHashMode = "recursive";
  outputHashAlgo = "sha256";
}
