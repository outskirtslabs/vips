{
  description = "dev env";
  inputs = {
    nixpkgs.url = "https://flakehub.com/f/NixOS/nixpkgs/0.1"; # tracks nixpkgs unstable branch
    devshell.url = "github:numtide/devshell";
    devshell.inputs.nixpkgs.follows = "nixpkgs";
    devenv.url = "https://flakehub.com/f/ramblurr/nix-devenv/*";
    devenv.inputs.nixpkgs.follows = "nixpkgs";
    clojure-nix-locker.url = "github:bevuta/clojure-nix-locker";
    clojure-nix-locker.inputs.nixpkgs.follows = "nixpkgs";
  };
  outputs =
    inputs@{
      clojure-nix-locker,
      self,
      devenv,
      devshell,
      ...
    }:
    let
      jdk = "jdk25";
    in
    devenv.lib.mkFlake ./. {
      inherit inputs;
      withOverlays = [
        devshell.overlays.default
        devenv.overlays.default
      ];
      packages = {
        default =
          pkgs:
          let
            jdkPackage = pkgs.${jdk};
            lockerPkgs = pkgs // {
              clojure = pkgs.clojure.override { jdk = jdkPackage; };
            };
            clojure = pkgs.clojure.override { jdk = jdkPackage; };
            gitRev =
              if self ? rev then
                self.rev
              else if self ? dirtyRev then
                self.dirtyRev
              else
                "dirty";
            clojureLocker = (import "${clojure-nix-locker}/default.nix" { pkgs = lockerPkgs; }).lockfile {
              src = ./.;
              lockfile = "./deps-lock.json";
            };
          in
          pkgs.stdenv.mkDerivation {
            pname = "TODO";
            version = "0.0.TODO";
            src = ./.;
            nativeBuildInputs = [
              clojure
              pkgs.coreutils
              pkgs.findutils
              pkgs.git
              jdkPackage
            ];
            GIT_REV = gitRev;
            JAVA_HOME = jdkPackage.home;
            buildPhase = ''
              runHook preBuild

              source ${clojureLocker.shellEnv}
              export JAVA_HOME="${jdkPackage.home}"
              export JAVA_CMD="${jdkPackage}/bin/java"

              clojure -Srepro -M:kaocha
              clojure -Srepro -T:build jar

              runHook postBuild
            '';
            installPhase = ''
              runHook preInstall

              mkdir -p $out
              cp "$(find target -type f -name '*.jar' -print | head -n 1)" $out/

              runHook postInstall
            '';
          };
        locker =
          pkgs:
          let
            jdkPackage = pkgs.${jdk};
            lockerPkgs = pkgs // {
              clojure = pkgs.clojure.override { jdk = jdkPackage; };
            };
            clojure = pkgs.clojure.override { jdk = jdkPackage; };
            clojureLocker = (import "${clojure-nix-locker}/default.nix" { pkgs = lockerPkgs; }).lockfile {
              src = ./.;
              lockfile = "./deps-lock.json";
            };
          in
          clojureLocker.commandLocker ''
            export HOME="$tmp/home"
            unset CLJ_CACHE CLJ_CONFIG XDG_CACHE_HOME XDG_CONFIG_HOME XDG_DATA_HOME

            ${clojure}/bin/clojure -Srepro -X:deps prep
            ${clojure}/bin/clojure -Srepro -P -M:kaocha
            ${clojure}/bin/clojure -Srepro -P -T:build jar
          '';
      };
      devShell =
        pkgs:
        pkgs.devshell.mkShell {
          imports = [
            devenv.capsules.base
            devenv.capsules.clojure
          ];
          # https://numtide.github.io/devshell
          commands = [
            # { package = pkgs.bazqux; }
          ];
          env = [
            {
              name = "LD_LIBRARY_PATH";
              value = pkgs.lib.makeLibraryPath [ pkgs.glib pkgs.vips ];
            }
          ];
          packages = [
            pkgs.vips
            pkgs.glib
            pkgs.gobject-introspection
            (if self ? packages then self.packages.${pkgs.system}.locker else pkgs.deps-lock)
            # pkgs.foobar
          ];

        };
    };
}
