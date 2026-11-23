{ pkgs, jars }:
let
  files = map (j: pkgs.fetchurl {
    inherit (j) urls sha256;
    name = builtins.baseNameOf j.path;
  }) jars;
  classpath = pkgs.writeTextFile {
    name = "classpath";
    text = pkgs.lib.concatStringsSep ":" files;
    derivationArgs.__contentAddressed = true;
  };
in (pkgs.linkFarm "drvjava-jars" ([
  { name = "classpath"; path = classpath; }
] ++ pkgs.lib.zipListsWith (j: path: { name = "jars/" + j.path; inherit path; }) jars files)).overrideAttrs {
  __contentAddressed = true;
}
