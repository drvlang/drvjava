{ self, pkgs, system }:
let
  root = /. + builtins.unsafeDiscardStringContext (import ./source.nix).outPath;
  dir = "private/tools/java/com/github/bazelbuild/rules_jvm_external/jar";
  lockFile = "rules_jvm_external_deps_install.json";
  sources = map (name: "${dir}/${name}.java") [ "IndexJar" "KotlinTopLevel" "PerJarIndexResults" ];
  indexer = self.lib.mkPackage {
    inherit system sources lockFile;
    src = self.lib.projectSource { inherit root; files = map (p: root + ("/" + p)) ([ lockFile ] ++ sources); };
    roots = [
      "com.google.code.gson:gson" "org.jetbrains.kotlin:kotlin-metadata-jvm"
      "org.jetbrains.kotlin:kotlin-stdlib" "org.ow2.asm:asm"
    ];
    mainClass = "com.github.bazelbuild.rules_jvm_external.jar.IndexJar";
  };
in pkgs.runCommand "drvjava-indexjar-test" { nativeBuildInputs = [ pkgs.jq ]; } ''
  ln -s ${indexer}/lib/project.jar indexed.jar
  echo indexed.jar > args
  ${indexer}/bin/drvjava-run --argsfile args > "$out"
  jq -e '."indexed.jar".classes | index("com.github.bazelbuild.rules_jvm_external.jar.IndexJar") != null' "$out"
''
