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
              extraPrepInputs = [ pkgs.git ];
            };
          in
          pkgs.stdenv.mkDerivation {
            pname = "ol-vips";
            version = "0.0.1";
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
            LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath [
              pkgs.stdenv.cc.cc.lib
            ];
            buildPhase = ''
              runHook preBuild

              source ${clojureLocker.shellEnv}
              export JAVA_HOME="${jdkPackage.home}"
              export JAVA_CMD="${jdkPackage}/bin/java"
              export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Dol.vips.native.cache-root=$TMPDIR/ol.vips-cache"

              clojure -Srepro -M:dev:kaocha
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
              extraPrepInputs = [ pkgs.git ];
            };
          in
          clojureLocker.commandLocker ''
            export HOME="$tmp/home"
            export GITLIBS="$tmp/home/.gitlibs"
            unset CLJ_CACHE CLJ_CONFIG XDG_CACHE_HOME XDG_CONFIG_HOME XDG_DATA_HOME
            ${clojure}/bin/clojure -Srepro -X:deps prep :aliases "[:dev :kaocha]"
            ${clojure}/bin/clojure -Srepro -P -M:dev:kaocha
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
            { package = self.packages.${pkgs.system}.locker; }
          ];
          env = [
            {
              name = "LD_LIBRARY_PATH";
              value = pkgs.lib.makeLibraryPath [
                pkgs.stdenv.cc.cc.lib
              ];
            }
          ];
          packages = [
            self.packages.${pkgs.system}.locker
          ];

        };
    };
}
