# drvjava

Turn a version 3 `rules_jvm_external` lockfile into a JVM classpath. The Rust planner follows the selected dependency edges, including classifier-specific edges. Nix downloads each JAR from its locked repositories and checks its SHA-256.

```sh
nix run github:drvlang/drvjava -- build -C ./app
javac -cp "$(cat result/classpath)" Example.java
```

The default lockfile is `maven_install.json`. To select one dependency tree, pass `--options '{"roots":["com.google.guava:guava"]}'`. For another lockfile, add `"lockFile":"deps_install.json"` to the same object. The output also contains Maven-layout files under `jars/`. The builder produces a JAR classpath. Android AARs and private/local repositories are outside its current scope.

Use `drvjava.lib.mkFlake { src = ./app; }` in a downstream flake or `lib.mkPackage { system, src, roots, ... }` in a package expression. Nix reads the Rust planner output during evaluation. To inspect the plan directly, run `nix run github:drvlang/drvjava#plan -- maven_install.json com.google.guava:guava`.

## Benchmark

The benchmark compiles and executes Bazel's upstream `IndexJar` utility. Four roots select six JARs from its 169-artifact lockfile. The check indexes the compiled program's own JAR and verifies the class list. [Benchmark definition](benchmarks/default.nix) · [Upstream source](https://github.com/bazel-contrib/rules_jvm_external/tree/1ba9a81c928ca28ff4b592e7b20d131ba9b9a431)

```sh
nix run github:drvlang/drvjava#bench -- --runs 5
```
