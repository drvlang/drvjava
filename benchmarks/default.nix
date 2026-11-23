{ self, pkgs, system }:
let
  src = (import ./source.nix).outPath;
  jars = self.lib.mkPackage {
    inherit system src;
    lockFile = "rules_jvm_external_deps_install.json";
    roots = [
      "com.google.code.gson:gson" "org.jetbrains.kotlin:kotlin-metadata-jvm"
      "org.jetbrains.kotlin:kotlin-stdlib" "org.ow2.asm:asm"
    ];
  };
  sources = "${src}/private/tools/java/com/github/bazelbuild/rules_jvm_external/jar";
in pkgs.runCommand "drvjava-indexjar-test" { nativeBuildInputs = [ pkgs.jdk_headless pkgs.jq ]; } ''
  mkdir classes
  cp=$(cat ${jars}/classpath)
  javac -cp "$cp" -d classes ${sources}/{IndexJar,KotlinTopLevel,PerJarIndexResults}.java
  jar cf indexed.jar -C classes .
  echo indexed.jar > args
  java -cp "classes:$cp" com.github.bazelbuild.rules_jvm_external.jar.IndexJar --argsfile args > "$out"
  jq -e '."indexed.jar".classes | index("com.github.bazelbuild.rules_jvm_external.jar.IndexJar") != null' "$out"
''
