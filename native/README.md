# Native Companion Jars

This workspace contains the platform-native companion artifacts for `com.outskirtslabs/vips`.

[`sharp-libvips`](https://github.com/lovell/sharp-libvips) is the upstream project that publishes prebuilt, per-platform `libvips` bundles with the OS, CPU, and Linux libc split already handled for us. We use it here so `ol.vips` can reuse those curated native binaries instead of compiling `libvips` in this repo or requiring downstream users to install the native stack themselves.

Each platform directory under `native/` is a small resource-only jar that packages a `sharp-libvips` npm tarball payload into the resource layout the future loader will consume.

The Clojure source and build logic in this repo remain EUPL-1.2. The native companion jars themselves redistribute upstream `sharp-libvips` binary bundles, which carry their own licensing obligations. Treat those redistributed binaries as LGPL-3.0-or-later plus the bundled third-party notices documented in [../THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md).

## Update Flow

1. Set `:sharp-vips-version` and `:native-version-revision` in [../deps.edn]
2. Run `bb native:update` to stage the selected `sharp-libvips` platform resources for that upstream release.
3. Run `bb jar:native` to build the companion jars.

By default these tasks operate on every platform directory under `native/`.

You can pass one or more platform ids to limit the run, for example:

```bash
bb native:update linux-x86-64-gnu
bb jar:native linux-x86-64-gnu macos-aarch64
```

## Resource Contract

Each staged platform jar uses this resource root:

```text
ol/vips/native/<platform-id>/
```

Contents:

- `manifest.edn`
- `lib/`
- `upstream/package.json`
- `upstream/versions.json`
- `upstream/README.md`

`manifest.edn` is the loader contract. It records the platform id, artifact coordinate, upstream package metadata, bundled libvips version, and the ordered list of native library files to extract and load.

The main library does not load these resources yet in this phase. This workspace only establishes the artifact structure, staging flow, and jar production.
