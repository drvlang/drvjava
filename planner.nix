pkgs: pkgs.rustPlatform.buildRustPackage {
  pname = "drvjava-plan";
  version = "0.1.0";
  src = ./planner;
  cargoLock.lockFile = ./planner/Cargo.lock;
}
