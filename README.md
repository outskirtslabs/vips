# ol.vips

> Clojure bindings for [libvips][upstream] image processing library

This package wraps the core functionality of the native [libvips][upstream] image processing library by exposing all native image operations as data and functions to Clojure programs.

We use [coffi][coffi] with Java 22+ FFI/FFM (Project Panama) for efficient native bridging.

Project Status: [Experimental](https://docs.outskirtslabs.com/open-source-vital-signs#experimental). It is used in production, but needs more exercise and feedback from the community before I commit to a stable version.

## Installation

Install the main library plus exactly one native jar that matches your target platform.

```clojure
;; deps.edn
{:deps {com.outskirtslabs/vips {:git/url "https://github.com/outskirtslabs/vips.git"
                                :git/sha "46789851fa8d749517bb7e09ea4e7340e1b757ec"}
        ;; change the following based on your runtime platform
        com.outskirtslabs/vips-native-linux-x86-64-gnu {:mvn/version "1.2.4-1"}}}
```


Don't forget to run `clojure -X:deps prep` before first use if using the git coord.

[Choose one or more native artifacts][clojars] (only the appropriate one for the runtime platform will be used):

- `com.outskirtslabs/vips-native-linux-x86-64-gnu`
- `com.outskirtslabs/vips-native-linux-x86-64-musl`
- `com.outskirtslabs/vips-native-linux-aarch64-gnu`
- `com.outskirtslabs/vips-native-linux-aarch64-musl`
- `com.outskirtslabs/vips-native-macos-x86-64`
- `com.outskirtslabs/vips-native-macos-aarch64`
- `com.outskirtslabs/vips-native-win32-x86-64`

`ol.vips` requires Java 22+ and native access enabled for the JVM process:

```clojure
;; deps.edn
{:aliases
 {:dev {:jvm-opts ["--enable-native-access=ALL-UNNAMED"]}}}
```

To use your own libvips build, see [Loading the native library](#loading-the-native-library).

## Quick Start

```clojure
(require '[ol.vips :as v]
         '[ol.vips.operations :as ops])

(v/init!)

(with-open [image   (v/from-file "dev/rabbit.jpg")
            thumb   (v/thumbnail image 300 {:auto-rotate true})
            rotated (ops/rotate thumb 90.0)]
  (v/write-to-file rotated "thumbnail.jpg")
  (v/info rotated))
;; => {:width 300, :height 242, :bands 3, :has-alpha? false}
```

The top-level `ol.vips` namespace provides the image loading, saving, metadata, and convenience helpers. `ol.vips.operations` contains generated wrappers for libvips operations.

## Introduction

`ol.vips` wraps libvips's image graph in [Closeable][closeable] Clojure values. Loader functions such as `v/from-file`, `v/from-buffer`, and `v/from-stream` return image handles, and operations such as `ops/resize`, `ops/colourspace`, and `v/call!` return new handles rather than mutating the original image.

This model is immutable and pipeline-oriented. In the common case, libvips reads only enough of the input to discover image metadata and build an operation graph. Calling loaders and transforms usually does not decode the whole image up front.

libvips is demand-driven, so the pixel work happens when something downstream asks for image data. In `ol.vips`, that usually means a sink such as `v/write-to-file`, `v/write-to-buffer`, or `v/write-to-stream`. At that point libvips pulls pixels through the pipeline, processing small regions with a horizontally threaded execution model instead of materializing every intermediate image in memory.

Some operations that need actual pixel values can trigger evaluation earlier, but metadata queries such as `v/width`, `v/height`, and `v/info` can often be satisfied from loader metadata alone. Because the image graph is immutable and operations are generally side-effect free, libvips can also reuse and cache operation results internally.

`ol.vips` adds a small amount of Clojure-specific structure on top of that model. The `ol.vips.operations` namespace is generated from libvips introspection data, `ol.vips.enums` exposes the enum keywords used by many options, and the top-level `ol.vips` namespace provides the file, buffer, stream, metadata, and convenience helpers you use most often.

## Why libvips?

The typical libvips workflow is exactly what many applications need: load a large source image, resize or transform it, and write a smaller web-friendly JPEG, PNG, WebP, AVIF, or TIFF result. `ol.vips` gives Clojure access to that model without shelling out to external image tools or spawning child processes.

libvips is [blazing fast][vipsspeed] and [memory efficient][vipsmemory] because it usually works on small regions of uncompressed image data at a time, takes advantage of multiple CPU cores, and avoids materializing every intermediate result in memory. That execution model is a strong fit for image pipelines, thumbnails, upload processing, preprocessing for vision workloads, and other server-side image tasks.

It also brings broad format and operation support: [300 operations][vipsfuncs] covering arithmetic, histograms, convolution, morphology, colour, resampling, statistics, and more, with correct handling of colour spaces, embedded ICC profiles, and alpha channels.

[libvips][upstream] was originally created at Birkbeck College and is currently maintained by [John Cupitt][john-cupitt] and other contributors.

### Bindings Generation

libvips itself is very amenable to automatic bindings generation. The core of `ol.vips` is small introspection layer that loads the native library then introspects it to generate `ol.vips.operations` and `ol.vips.enums`, and then a small runtime for loading/reading/writing.

As a convenience for Clojure users there are separate jars available with the native libraries per-platform, but `ol.vips` it self does not depend on them directly. You can depend on one (or more) of those native deps, or provide your own libvips build with `-Dol.vips.native.preload`. You'll need to choose one.

This means that you can even use a custom libvips build or a newer version of libvips without having to wait for `ol.vips` to catch up (though I expect to keep it up to date). Any new or updated operations in libvips can be used with this library using the lower level `ol.vips/call!` function. You can even generate the bindings yourself if you need to, simply use `ol.vips.codegen`.

## Examples

The snippets below show the main API shapes inline. For runnable end-to-end scripts, see the examples in [`examples/`](./examples/):

- [`examples/create_thumbnail.clj`](./examples/create_thumbnail.clj) creates a thumbnail from a local file and writes it back out.
- [`examples/chain_transforms.clj`](./examples/chain_transforms.clj) chains several image operations together with the threading style API.
- [`examples/compose_images.clj`](./examples/compose_images.clj) joins two images and builds a small grid with `join` and `arrayjoin`.
- [`examples/bytes_and_streams.clj`](./examples/bytes_and_streams.clj) reads image bytes into memory, streams them through libvips, and writes the result back out.
- [`examples/http_stream_fetch.clj`](./examples/http_stream_fetch.clj) streams an HTTP response body into libvips and saves a transformed result.
- [`examples/metadata_roundtrip.clj`](./examples/metadata_roundtrip.clj) copies an image while preserving metadata on write.

### File Input and Output

Use the file helpers when your source and destination already live on disk and you want libvips to infer formats from the path or suffix.

```clojure
(with-open [image (v/from-file "dev/rabbit.jpg" {:shrink 2})]
  (v/info image)
  (v/shape image)
  (v/write-to-file image "rabbit.png" {:compression 9})
  (v/write-to-buffer image ".png" {:compression 9}))
```

### Image Loaders

The generic loaders `v/from-file`, `v/from-buffer`, and `v/from-stream` auto-detect the input format and accept option maps that libvips forwards to the selected loader. When you need format-specific behavior, use the generated loader operations in `ol.vips.operations`, such as `ops/jpegload`.

JPEG auto-rotation rotates the image according to its EXIF orientation at load time:

```clojure
(require '[ol.vips :as v]
         '[ol.vips.operations :as ops])

(with-open [image (ops/jpegload "dev/rabbit.jpg" {:autorotate true})]
  (v/write-to-file image "rabbit-upright.jpg")
  (v/info image))
```

### Streaming Input And Output

`from-stream` reads from any `java.io.InputStream`. `from-buffer` reads from a byte array already in memory. 

```clojure
(require '[babashka.http-client :as http]
         '[ol.vips :as v])

(import '[java.io FileOutputStream])

(with-open [response-body (:body (http/get "https://casey.link/square-flask.png"
                                           {:as :stream}))
            image         (v/from-stream response-body {:access  :sequential
                                                        :fail-on :error})
            thumbnail     (v/thumbnail image 200)
            out           (FileOutputStream. "square-flask-thumb.png")]
  (v/write-to-stream thumbnail out ".png")
  (v/info thumbnail))
```

`write-to-stream` writes encoded bytes to any `java.io.OutputStream`.

```clojure
(require '[babashka.fs :as fs]
         '[ol.vips :as v])

(import '[java.io FileOutputStream])

(let [source-bytes (fs/read-all-bytes "dev/rabbit.jpg")]
  (with-open [image     (v/from-buffer source-bytes)
              thumbnail (v/thumbnail image 200)
              out       (FileOutputStream. "rabbit-thumb.png")]
    (v/write-to-stream thumbnail out ".png")
    (v/info thumbnail)))
```

### Autorotate And Inspect Outputs

`autorot` returns a closeable result map. You can inspect `:angle` and `:flip`, and still use the same value anywhere an image is expected.

```clojure
(with-open [image   (v/from-file "dev/rabbit.jpg")
            autorot (ops/autorot image)]
  {:angle (:angle autorot)
   :flip  (:flip autorot)
   :info  (v/info autorot)})
;; => {:angle :d0, :flip false, :info {:width 2490, :height 3084, :bands 3, :has-alpha? false}}
```

### Resize And Crop

Use these geometry operations when you want to rescale an image first and then work on a precise rectangular region.

```clojure
(with-open [image   (v/from-file "dev/rabbit.jpg")
            resized (ops/resize image 0.5)
            cropped (ops/extract-area resized 100 100 500 500)]
  (v/write-to-file resized "rabbit-resized.jpg")
  (v/write-to-file cropped "rabbit-cropped.jpg")
  {:resized (v/info resized)
   :cropped (v/info cropped)})
```

### Smart Thumbnailing

`ops/thumbnail` exposes libvips's higher-level thumbnail pipeline. Enum options such as `:size` and `:crop` are documented in `ol.vips.enums`.

```clojure
(with-open [thumb (ops/thumbnail "dev/rabbit.jpg" 300
                                 {:height 300
                                  :size :down
                                  :crop :attention})]
  (v/write-to-file thumb "rabbit-smart-thumb.jpg")
  (v/info thumb))
```

### Transforming Images

These operations are a good fit for orientation, color interpretation, and other structural changes you want to compose into one pipeline.

```clojure
(with-open [image   (v/from-file "dev/rabbit.jpg")
            rotated (ops/rotate image 90.0)
            flipped (ops/flip rotated :horizontal)
            bw      (ops/colourspace flipped :b-w)]
  (v/write-to-file bw "rabbit-bw.jpg"))
```

### Filters And Effects

libvips includes a large set of convolution and enhancement operations, so common blur and sharpen passes can stay inside the same image pipeline.

```clojure
(with-open [image   (v/from-file "dev/rabbit.jpg")
            blurred (ops/gaussblur image 3.0)
            sharp   (ops/sharpen image {:sigma 1.0})]
  (v/write-to-file blurred "rabbit-blur.jpg")
  (v/write-to-file sharp "rabbit-sharp.jpg")
  {:blurred (v/info blurred)
   :sharp   (v/info sharp)})
```

### Composing Images

Use the composition helpers when you need to join images, build contact sheets, or align multiple sources into one output image.

```clojure
(with-open [left   (v/from-file "dev/rabbit.jpg")
            right  (v/from-file "dev/rabbit.jpg")
            joined (ops/join left right :horizontal)
            grid   (ops/arrayjoin [left right left right]
                                  {:across 2
                                   :shim   10
                                   :halign :centre
                                   :valign :centre})]
  (v/write-to-file joined "rabbit-joined.jpg")
  (v/write-to-file grid "rabbit-grid.jpg"))
```

### Web Optimization

The save helpers accept format-specific encoder options, which makes it easy to produce smaller web-friendly JPEG and WebP outputs from the same source image.

```clojure
(with-open [image (v/from-file "dev/rabbit.jpg")]
  (v/write-to-file image "rabbit-progressive.jpg"
                   {:interlace true
                    :strip true
                    :Q 85})
  (v/write-to-file image "rabbit.webp"
                   {:Q 80
                    :effort 4}))
```

### Calling Raw Operations

Use `v/call!` when you want access to the full libvips operation surface before a dedicated convenience wrapper exists.

```clojure
(with-open [image   (v/from-file "dev/rabbit.jpg")
            rotated (v/call! "rotate" {:in image :angle 90.0})]
  (v/info rotated))
```

Use `v/operations` to list available libvips operations and `v/operation-info` to inspect their inputs and outputs.

### Text Overlay

Generated operation wrappers also cover image synthesis and compositing, so overlays like text labels can stay inside libvips rather than being rendered in a separate tool.

```clojure
(with-open [image  (v/from-file "dev/rabbit.jpg")
            label  (ops/text "ol.vips"
                             {:font "Sans Bold 48"
                              :rgba true})
            poster (ops/composite2 image label :over {:x 40 :y 40})]
  (v/write-to-file poster "rabbit-poster.png")
  (v/info poster))
```

## Loading the native library

`ol.vips` uses this procedure to load the native components:

1. Load any explicit libraries listed in `-Dol.vips.native.preload`.
2. Load the extracted libraries from the platform native jar on the classpath.
3. If that fails, fall back to the system library loader, which can resolve `libvips` from `LD_LIBRARY_PATH`.

### 1. Preload

`-Dol.vips.native.preload` is for exact native library file paths. Use it when you want to point `ol.vips` at a specific custom build in a non-standard location, or when you need to preload dependency libraries before libvips itself. On Nix-like systems that can include things like `libstdc++.so.6` as well as your `libvips` library. The value is a single string containing one or more full library file paths separated by the OS path separator, which is `:` on Linux and macOS and `;` on Windows.

If `ol.vips.native.preload` is set, those entries are always loaded first. If any preload entry fails to load, the packaged native-jar path is abandoned for that initialization attempt and `ol.vips` falls back to the system library loader instead of continuing with the native jar.

### 2. Classpath native bundle

`ol.vips` detects the current platform, builds a resource path like `ol/vips/native/{os}-{arch}` on macOS and Windows or `ol/vips/native/{os}-{arch}-{libc}` on Linux, and then looks for `manifest.edn` under that path.

For example, Linux x86-64 glibc resolves to `ol/vips/native/linux-x86-64-gnu/manifest.edn`. If that resource is present, `ol.vips` reads the manifest, extracts the bundled native libraries into the local cache, and loads those extracted files.

You can override parts of that platform detection with `-Dol.vips.native.platform-id`, `-Dol.vips.native.os`, `-Dol.vips.native.arch`, and `-Dol.vips.native.libc`.

In practice, this means the companion jar for the current runtime platform needs to be on the classpath, meaning the current OS, CPU architecture, and Linux libc when applicable. This path is attempted after any explicit `ol.vips.native.preload` entries. 

In the successful packaged case, `v/init!` will report `:native-load-source :packaged` and expose the extracted primary library path in `:primary-library-path`, which is useful when debugging exactly what got loaded.

### 3. System fallback

The system-library fallback loads by library name rather than full file path. Use this when you want the OS or JVM loader to resolve libvips from `LD_LIBRARY_PATH`, standard system locations, or other platform-specific loader configuration. A platform native jar is not required for this path; if the packaged load fails, including because no matching native jar is present on the classpath, `ol.vips` can still fall back to the system loader.

By default the system fallback tries `vips-cpp` and then `vips`. Most users should not need to change this. `-Dol.vips.native.system-libs` exists for the uncommon case where your environment exposes libvips under different system library names. Like `ol.vips.native.preload`, it accepts multiple entries separated by the OS path separator.

The fallback only applies to native library loading. If the packaged libraries load successfully but `vips_init` fails afterwards, `ol.vips` does not automatically retry via the system loader. For debugging, `v/init!` exposes runtime state including whether initialization came from the packaged path or the system fallback.

## Licensing

The Clojure library `ol.vips` is copyright (C) 2026 Casey Link and is licensed under [EUPL-1.2](./LICENSE).

The platform-native companion jars under `native/` redistribute upstream [sharp-libvips][sharp-libvips] binary bundles. Those redistributed native binaries are licensed separately from the `ol.vips` source, principally under LGPL-3.0-or-later, with additional bundled third-party component notices documented in [THIRD-PARTY-NOTICES.md](./THIRD-PARTY-NOTICES.md).

[upstream]: https://github.com/libvips/libvips
[coffi]: https://github.com/IGJoshua/coffi
[john-cupitt]: https://github.com/jcupitt
[vipsspeed]: https://github.com/libvips/libvips/wiki/Why-is-libvips-quick
[vipsmemory]: https://github.com/libvips/libvips/wiki/Speed-and-memory-use
[vipsfuncs]: https://www.libvips.org/API/current/func-list.html
[sharp-libvips]: https://github.com/lovell/sharp-libvips/releases
[clojars]: https://clojars.org/search?q=group-id%3Acom.outskirtslabs+AND+artifact-id%3Avips-native*
[closeable]: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/io/Closeable.html
