{
  description = "Build and test JVM projects from rules_jvm_external locks with dynamic Nix derivations";
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/6b5e5b7a6631f065bf6908986990b37d845f847f";
  inputs.drvkit = { url = "github:drvlang/drvkit"; flake = false; };

  outputs = { self, nixpkgs, drvkit, ... }: let tools = import drvkit {
    inherit self nixpkgs;
    name = "drvjava";
    mkPackage = import ./package.nix { inherit drvkit; };
    mkTest = import ./package.nix { inherit drvkit; test = true; };
    selectTarget = { src, name, target }: { inherit src; projectDir = target; };
    extraChecks = system: let
      pkgs = nixpkgs.legacyPackages.${system};
      workspace = self.lib.mkMonorepoFlake {
        inherit (builtins.fromJSON (builtins.readFile ./tests/drvlang.json)) commands;
        srcForTarget = { target, ... }: self.lib.projectSource {
          root = ./tests;
          files = [ (./tests + "/${target}") ./tests/pkg/common ./tests/maven_install.json ];
        };
        extraSourceDirs = [ "pkg/common" ];
        testRoots = [ "org.junit.platform:junit-platform-console-standalone" ];
      };
      package = workspace.packages.${system}.api;
      rebuilt = package.overrideAttrs { DRVLANG_REBUILD_CHECK = "1"; };
    in {
      benchmark = import ./benchmarks { inherit pkgs self system; };
      junit = workspace.checks.${system}.api;
      content-addressing = pkgs.runCommand "drvjava-check-content-addressing" { __contentAddressed = true; } ''
        test ${package} = ${rebuilt}
        test -z "$(ls -A ${package}/jars)"
        touch "$out"
      '';
    };
  };
  in tools // {
    packages = nixpkgs.lib.mapAttrs (system: packages: packages // {
      plan = import ./planner.nix nixpkgs.legacyPackages.${system};
    }) tools.packages;
  };
}
