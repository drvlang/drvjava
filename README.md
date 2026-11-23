# drvjava

Build, test, and run a Java project directly from its version 3 `rules_jvm_external` lockfile. The Rust planner selects the dependency closure, and Nix fetches each JAR by its locked SHA-256. Editing the native lockfile is enough to change dependencies. Generated classpaths and build outputs are content-addressed, so builds of a target that produce identical files and store references reuse the same store path.

From a project with `maven_install.json`, sources in `src/main/java`, and JUnit Platform Console on its locked classpath, run

```sh
nix run github:drvlang/drvjava -- build
nix run github:drvlang/drvjava -- test
nix run github:drvlang/drvjava -- run -- com.example.Main hello
```

The result contains `lib/project.jar`, a `classpath` file, Maven-layout dependencies under `jars/`, and a Java launcher. Use `--options '{"mainClass":"com.example.Main"}'` to select a default entry point. For a test suite with its own Java main method, use `--options '{"testClass":"com.example.MainTest"}'`. Arguments after `--` go to the program or test runner.

## Flakes and monorepos

For one project, pass `src = drvjava.lib.projectSource { root = ./.; files = [ ./src ./maven_install.json ]; };` and `mainClass = "com.example.Main";` to `drvjava.lib.mkFlake`. For several programs, put the member map in the repository's `drvlang.json` so the CLI and flake share the same names.

```json
{"commands":{"api":"apps/api","worker":"apps/worker"}}
```

```nix
{
  inputs.drvjava.url = "github:drvlang/drvjava";
  outputs = { self, drvjava }: drvjava.lib.mkMonorepoFlake {
    inherit (builtins.fromJSON (builtins.readFile ./drvlang.json)) commands;
    srcForTarget = { target, ... }: drvjava.lib.projectSource {
      root = ./.;
      files = [ (./. + "/${target}") ./pkg/common ./maven_install.json ];
    };
    extraSourceDirs = [ "pkg/common/src/main/java" ];
  };
}
```

`nix build .#api`, `nix flake check`, and `nix run .#api -- com.example.Api` select member sources and the shared code. The CLI equivalent for the build is `build api --options '{"extraSourceDirs":["pkg/common/src/main/java"]}'`. Each member may supply its own lock or inherit the root lock. Set `roots` to a list of Maven coordinates to fetch only their transitive dependencies. Set `lockFile` to choose a different repository-relative lock path. This builder supports public HTTPS repositories and JAR dependencies on x86-64 and AArch64 Linux.

Enable these Nix settings on the client and build daemon, then restart the daemon after changing its configuration.

```ini
extra-experimental-features = nix-command flakes ca-derivations dynamic-derivations recursive-nix
extra-system-features = recursive-nix ca-derivations
```

## Benchmark

`nix run github:drvlang/drvjava#bench -- --runs 5` runs Bazel's IndexJar utility against its own compiled JAR and checks the class list. Four locked roots select six JARs from 169 artifacts. The Rust runner warms the build, then times each rebuild of the runtime check. [Benchmark definition](benchmarks/default.nix) · [Pinned upstream source](https://github.com/bazel-contrib/rules_jvm_external/tree/1ba9a81c928ca28ff4b592e7b20d131ba9b9a431)
