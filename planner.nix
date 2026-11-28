pkgs:
let
  system = pkgs.stdenv.hostPlatform.system;
  jdk = pkgs.jdk_headless;
in builtins.derivation {
  name = "drvjava-plan";
  inherit system;
  builder = "${pkgs.bashNonInteractive}/bin/bash";
  args = [ "-e" "-c" ''
    "${jdk}/bin/javac" -encoding UTF-8 -d "$TMPDIR/classes" "${./DrvJava.java}"
    "${pkgs.coreutils}/bin/mkdir" -p "$out/bin" "$out/lib"
    "${jdk}/bin/jar" --create --file "$out/lib/drvjava.jar" --date=1980-01-01T00:00:02Z -C "$TMPDIR/classes" .
    printf '#!%s/bin/bash\nexec %s/bin/java -cp %s/lib/drvjava.jar DrvJava plan-json "$@"\n' \
      "${pkgs.bashNonInteractive}" "${jdk}" "$out" > "$out/bin/drvjava-plan"
    "${pkgs.coreutils}/bin/chmod" +x "$out/bin/drvjava-plan"
  '' ];
  __contentAddressed = true;
  outputHashMode = "recursive";
  outputHashAlgo = "sha256";
}
