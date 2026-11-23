pkgs: pkgs.rustPlatform.buildRustPackage {
  pname = "drvjava-plan";
  version = "0.1.0";
  src = pkgs.lib.fileset.toSource {
    root = ./planner;
    fileset = pkgs.lib.fileset.unions [ ./planner/src ./planner/Cargo.toml ./planner/Cargo.lock ];
  };
  cargoLock.lockFile = ./planner/Cargo.lock;
}
