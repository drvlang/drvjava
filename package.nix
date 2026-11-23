pkgs: { src, lockFile ? "maven_install.json", roots ? [] }:
let
  planner = import ./planner.nix pkgs;
  # Isolate the planner from code changes in the consuming project.
  lock = pkgs.writeText "jvm-lock.json" (builtins.readFile (src + ("/" + lockFile)));
  planFile = pkgs.runCommand "drvjava-plan.json" {} ''
    ${planner}/bin/drvjava-plan ${lock} ${pkgs.lib.escapeShellArgs roots} > "$out"
  '';
  jars = builtins.attrValues (builtins.fromJSON (builtins.readFile planFile));
  files = map (j: pkgs.fetchurl {
    inherit (j) urls sha256;
    name = builtins.baseNameOf j.path;
  }) jars;
in pkgs.linkFarm "drvjava-classpath" ([
  { name = "classpath"; path = pkgs.writeText "classpath" (pkgs.lib.concatStringsSep ":" files); }
] ++ pkgs.lib.zipListsWith (j: path: { name = "jars/" + j.path; inherit path; }) jars files)
