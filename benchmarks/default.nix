{ self, pkgs, system }:
let
  src = (import ./source.nix).outPath;
  dir = "private/tools/java/com/github/bazelbuild/rules_jvm_external/jar";
  indexer = self.lib.mkPackage {
    inherit system src;
    lockFile = "rules_jvm_external_deps_install.json";
    roots = [
      "com.google.code.gson:gson" "org.jetbrains.kotlin:kotlin-metadata-jvm"
      "org.jetbrains.kotlin:kotlin-stdlib" "org.ow2.asm:asm"
    ];
    sources = map (name: "${dir}/${name}.java") [ "IndexJar" "KotlinTopLevel" "PerJarIndexResults" ];
    mainClass = "com.github.bazelbuild.rules_jvm_external.jar.IndexJar";
  };
in pkgs.runCommand "drvjava-indexjar-test" { nativeBuildInputs = [ pkgs.jq ]; } ''
  ln -s ${indexer}/lib/project.jar indexed.jar
  echo indexed.jar > args
  ${indexer}/bin/drvjava-run --argsfile args > "$out"
  jq -e '."indexed.jar".classes | index("com.github.bazelbuild.rules_jvm_external.jar.IndexJar") != null' "$out"
''
