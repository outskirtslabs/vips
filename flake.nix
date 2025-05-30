{
  description = "clj-vips";
  inputs = {
    nixpkgs.url = "https://flakehub.com/f/NixOS/nixpkgs/0.1"; # tracks nixpkgs unstable branch
    nixpkgs-mine.url = "git+https://github.com/ramblurr/nixpkgs?shallow=1&ref=consolidated";
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
            pkgs-mine = import inputs.nixpkgs-mine {
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
        { pkgs, pkgs-mine }:
        let
          libraries = [ pkgs.vips ];
          giLibs = [
            pkgs.glib
            pkgs.gobject-introspection
            pkgs-mine.vips
          ];

        in
        {
          default = pkgs.mkShell {
            packages = [
              pkgs.vips
              pkgs.clojure
              pkgs.clojure-lsp
              pkgs.babashka
              pkgs.clj-kondo
              pkgs.graalvm-ce
              pkgs.gobject-introspection
              pkgs.yelp-tools
              (pkgs-mine.python3.withPackages (ps: [ ps.pyvips ]))
            ];
            buildInputs = libraries;
            inputsFrom = libraries;
            nativeBuildInputs = [ pkgs.pkg-config ];
            env.LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath libraries;
            env.GI_TYPELIB_PATH = pkgs.lib.concatStringsSep ":" (
              map (pkg: "${pkg}/lib/girepository-1.0") giLibs
            );
          };
        }
      );
    };
}
