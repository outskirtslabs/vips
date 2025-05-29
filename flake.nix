{
  description = "clj-vips";
  inputs = {
    nixpkgs.url = "https://flakehub.com/f/NixOS/nixpkgs/0.1"; # tracks nixpkgs unstable branch
  };
  outputs =
    inputs:
    let
      javaVersion = 24;
      supportedSystems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];
      forEachSupportedSystem =
        f:
        inputs.nixpkgs.lib.genAttrs supportedSystems (
          system:
          f {
            pkgs = import inputs.nixpkgs {
              inherit system;
              overlays = [
                inputs.self.overlays.default
              ];
            };
          }
        );
    in
    {
      overlays.default =
        final: prev:
        let
          jdk = prev."jdk${toString javaVersion}";
        in
        {
          clojure = prev.clojure.override { inherit jdk; };
        };

      devShells = forEachSupportedSystem (
        { pkgs }:
        let
  libraries = [pkgs.vips];
        in
        {
          default = pkgs.mkShell {
            packages = with pkgs; [
              vips
              clojure
              clojure-lsp
              babashka
              clj-kondo
              graalvm-ce
            ];
            buildInputs = libraries;
            inputsFrom = libraries;
            env.LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath libraries;
          };
        }
      );
    };
}
