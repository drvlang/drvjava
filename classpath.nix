{ pkgs, jars }:
let
  files = map (j: pkgs.fetchurl {
    inherit (j) urls sha256;
    name = builtins.baseNameOf j.path;
  }) jars;
in pkgs.linkFarm "drvjava-jars" ([
  { name = "classpath"; path = pkgs.writeText "classpath" (pkgs.lib.concatStringsSep ":" files); }
] ++ pkgs.lib.zipListsWith (j: path: { name = "jars/" + j.path; inherit path; }) jars files)
