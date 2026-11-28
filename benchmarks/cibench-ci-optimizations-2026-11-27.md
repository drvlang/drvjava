# Optimizing drvjava for CI

Across all seven CIBench CI cases, replacing `drvjava`'s Rust/Cargo planner (`rustPlatform.buildRustPackage`), `drvkit/dynamic.nix`, `nix-instantiate`, `nixpkgsSource`, `pkgs.fetchurl`, `pkgs.linkFarm`, `stdenvNoCC`, and `makeWrapper` with a self-contained pure-JDK Java 21 Nix daemon ATerm planner & builder plus an input-addressed slim JDK slice (`jdkSlim`) reduced cold builds from 42.47 s to 6.64 s (6.40× faster; 2.39× faster than Nixpkgs `buildMavenPackage`), remote cache restores from 20.27 s / 1126.2 MiB to 2.72 s / 97.3 MiB (7.45× faster, 91.4% less traffic; 2.64× faster and 4.5× less traffic than `buildMavenPackage`), local warm checks from 1.31 s to 1.15 s (faster than `buildMavenPackage`), remote edits from 20.61 s / 1126.2 MiB to 3.55 s / 97.3 MiB (5.81× faster; 3.12× faster and 6.1× less traffic than `buildMavenPackage`), local edits from 2.36 s to 2.09 s (1.86× faster than `buildMavenPackage`), remote dependency updates from 38.03 s / 1126.0 MiB to 3.77 s / 97.1 MiB (10.09× faster; 5.98× faster and 12.0× less traffic than `buildMavenPackage`), and local dependency updates from 17.29 s to 2.36 s (7.33× faster; 4.60× faster than `buildMavenPackage`).

CIBench builds a Java application (`json-demo`) depending on `com.google.code.gson:gson` from `maven_install.json` and checks the output of `drvjava-run`. The source edit modifies `Main.java` and the expected output without changing `maven_install.json`. The independent dependency update upgrades `gson` from `2.10.1` to `2.11.0` in `maven_install.json` without any separate Maven fixed-output hash.

| CIBench case | drvjava `45f7a3c` (s) | drvjava optimized (s) | `buildMavenPackage` (s) | drvjava cache transfer (before → after) | drvjava builds (before → after) | drvjava peak RAM (before → after) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| cold | 42.47 | 6.64 | 15.84 | 0.0 → 0.0 MiB | 22 → 8 | 4286 → 1894 MiB |
| remote-warm | 20.27 | 2.72 | 7.19 | 1126.2 → 97.3 MiB | 0 → 0 | 3015 → 851 MiB |
| local-warm | 1.31 | 1.15 | 1.19 | 0.0 → 0.0 MiB | 0 → 0 | 450 → 430 MiB |
| edit-remote | 20.61 | 3.55 | 11.07 | 1126.2 → 97.3 MiB | 3 → 3 | 3079 → 921 MiB |
| edit-local | 2.36 | 2.09 | 3.89 | 0.0 → 0.0 MiB | 3 → 3 | 517 → 498 MiB |
| dependency-remote | 38.03 | 3.77 | 22.55 | 1126.0 → 97.1 MiB | 8 → 5 | 3075 → 910 MiB |
| dependency-local | 17.29 | 2.36 | 10.86 | 0.0 → 0.0 MiB | 8 → 5 | 521 → 496 MiB |

Times are medians of two trials for `45f7a3c`, three trials for the optimized tree, and five trials for the shared Nixpkgs `buildMavenPackage` comparator, run in alternating order (`before-1`, `after-1` ×2, `before-2`, `after-2`) with no competing host workloads. Both builders used OpenJDK 21.0.12+8. Each sample timed evaluation and realization of the package and check together. [Individual timings, build counts, cache traffic, peak memory, and realized outputs](cibench-ci-optimizations-2026-11-27.csv)

## Summary of optimizations

1. **Self-contained pure-JDK Java 21 Nix daemon ATerm planner & builder (`DrvJava.java`, `package.nix`, `planner.nix`, `flake.nix`)**: Replaced the Rust/Cargo planner (`rustc-1.97.1`, `cargo`, `llvm-lib`), `drvkit/dynamic.nix`, `nix-instantiate`, `nixpkgsSource` (202 MiB), `pkgs.nix`, `pkgs.fetchurl`, `pkgs.linkFarm`, `stdenvNoCC`, and `makeWrapper` with a single zero-dependency Java 21 helper compiled once into a ~15 KiB input-addressed JAR (`drvjava-tool`). Speaks Nix daemon protocol 1.21 directly over `java.net.UnixDomainSocketAddress`, emits `builtin:fetchurl` derivations for locked Maven JARs, compiles Java sources in-process via `ToolProvider.getSystemJavaCompiler()`, and writes deterministic JARs in-process via `JarOutputStream` (`1980-01-01T00:00:02Z`).
2. **Input-addressed slim JDK slice (`jdkSlim` in `package.nix`, `DrvJava.java`)**: Drops `jmods/` (307 MiB) and `lib/src.zip` (52 MiB) from `openjdk-headless-21.0.12+8` while rewriting embedded store paths in-place using an exact-length output name, eliminating all runtime references to the full 643 MiB JDK and cutting remote binary-cache transfers from 1126 MiB to 97.3 MiB (4.5× to 12.0× less than `buildMavenPackage`).

## Correctness checks

- Realized package and check outputs were deterministic across all three optimized trials and matched between `cold`, `remote-warm`, and `local-warm`, between `edit-remote` and `edit-local`, and between `dependency-remote` and `dependency-local`.
- `nix flake check -L --option allow-import-from-derivation false` passed all checks:
  - `checks.x86_64-linux.benchmark` (Bazel `rules_jvm_external` `IndexJar` resolving 6 JARs from 4 roots in a 169-artifact lockfile)
  - `checks.x86_64-linux.junit` (monorepo `apps/api` + `pkg/common` executing JUnit 5 `ConsoleLauncher`)
  - `checks.x86_64-linux.content-addressing` (`overrideAttrs` rebuild producing byte-identical CA output and empty `jars/` directory)
  - `checks.x86_64-linux.cli`
- `nix flake show --all-systems` (`x86_64-linux`, `aarch64-linux`) and `nix build .#plan` (`drvjava-plan` CLI) passed.

## Reproduction

The runner used x86_64 Linux, an Intel Core i7-1280P, Nix 2.35.3, four logical CPUs (16–19), four jobs, four cores per builder, 8 GiB RAM, and zero swap. Sandboxing was enabled and IFD disabled. The simulated project cache used 5 ms latency and 100 MiB/s aggregate bandwidth. Both builders used the same local mirror of signed NixOS packages populated from `drvkit/cibench/results/bootstrap/java.txt`. Cold jobs started with an empty local store and project cache. Subsequent cases independently used the original job's local store or its published project cache. The recorded runner was `/nix/store/vpysz8ih9lhm154cjaamrncwjn08njp6-cibench-0.1.0`.

```sh
work=$(mktemp -d)
stock="file://$work/stock"
nix copy --from https://cache.nixos.org --to "$stock?compression=zstd&compression-level=1" \
  --stdin < ../drvkit/cibench/results/bootstrap/java.txt
runner="$(nix build --impure --no-link --print-out-paths -f ../drvkit/cibench)/bin/cibench"
mkdir "$work/before"
git archive 45f7a3c | tar -x -C "$work/before"
run() {
  taskset -c 16-19 "$runner" java --builder "$1" --out "$work/$2" --trials "$3" \
    --cpus 4 --memory-mib 8192 --latency-ms 5 --bandwidth-mib-s 100 \
    --upstream "$stock?priority=40"
}
run "$work/before" before-1 1
run "$PWD" after-1 2
run "$work/before" before-2 1
run "$PWD" after-2 1
```
