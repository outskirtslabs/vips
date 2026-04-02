# Native Companion Jars

This workspace contains the platform-native companion artifacts for `com.outskirtslabs/vips`.

[`sharp-libvips`](https://github.com/lovell/sharp-libvips) is the upstream project that publishes prebuilt, per-platform `libvips` bundles with the OS, CPU, and Linux libc split already handled for us. We use it here so `ol.vips` can reuse those curated native binaries instead of compiling `libvips` in this repo or requiring downstream users to install the native stack themselves.

Each platform directory under `native/` is a small resource-only jar that packages a `sharp-libvips` npm tarball payload into the resource layout the future loader will consume.

## Update Flow

1. Set `:vips-version` in [../deps.edn]
2. Run `bb native:update` to resolve the matching `sharp-libvips` package version from npm and stage the selected platform resources.
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

`manifest.edn` is the loader contract. It records the platform id, artifact coordinate, upstream package metadata, pinned libvips version, and the ordered list of native library files to extract and load.

The main library does not load these resources yet in this phase. This workspace only establishes the artifact structure, staging flow, and jar production.
