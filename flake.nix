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
    extraChecks = system: {
      benchmark = import ./benchmarks { pkgs = nixpkgs.legacyPackages.${system}; inherit self system; };
      junit = (self.lib.mkMonorepoFlake {
        inherit (builtins.fromJSON (builtins.readFile ./tests/drvlang.json)) commands;
        srcForTarget = { target, ... }: self.lib.projectSource {
          root = ./tests;
          files = [ (./tests + "/${target}") ./tests/pkg/common ./tests/maven_install.json ];
        };
        extraSourceDirs = [ "pkg/common" ];
      }).checks.${system}.api;
    };
  };
  in tools // {
    packages = nixpkgs.lib.mapAttrs (system: packages: packages // {
      plan = import ./planner.nix nixpkgs.legacyPackages.${system};
    }) tools.packages;
  };
}
