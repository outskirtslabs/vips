# ol.vips

> High-perf image processing for Clojure built with [libvips][upstream]. 

This package wraps the core functionality of the native [libvips][upstream] image processing library by exposing all native image operations as data and functions to Clojure programs.

We use [coffi][coffi] with Java 22+ FFM (Project Panama) for efficient native bridging.

## Installation

Install the main library plus exactly one native jar that matches your target platform.

```clojure
;; deps.edn
{:deps {com.outskirtslabs/vips {:mvn/version "0.0.1"}
        com.outskirtslabs/vips-native-linux-x86-64-gnu {:mvn/version "1.2.4-0"}}}
```

Choose one native artifact:

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

The library bundles platform-specific native artifacts for supported Linux, macOS, and Windows targets.

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

## Common Operations

### Reading And Writing

```clojure
(with-open [image (v/from-file "dev/rabbit.jpg" {:shrink 2})]
  (v/info image)
  (v/shape image)
  (v/write-to-file image "rabbit.png" {:compression 9})
  (v/write-to-buffer image ".png" {:compression 9}))
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

```clojure
(with-open [thumb (ops/thumbnail "dev/rabbit.jpg" 300
                                 {:height 300
                                  :size :down
                                  :crop :attention})]
  (v/write-to-file thumb "rabbit-smart-thumb.jpg")
  (v/info thumb))
```

### Transforming Images

```clojure
(with-open [image   (v/from-file "dev/rabbit.jpg")
            rotated (ops/rotate image 90.0)
            flipped (ops/flip rotated :horizontal)
            bw      (ops/colourspace flipped :b-w)]
  (v/write-to-file bw "rabbit-bw.jpg"))
```

### Filters And Effects

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

```clojure
(with-open [image   (v/from-file "dev/rabbit.jpg")
            rotated (v/call! "rotate" {:in image :angle 90.0})]
  (v/info rotated))
```

Use `v/operations` to list available libvips operations and `v/operation-info` to inspect their inputs and outputs.

## Advanced Usage

### Chunked Input And Output

`from-enum` reads an image from a sequence of binary chunks. `write-to-stream` returns a sequence of encoded chunks you can write anywhere.

```clojure
(let [source-bytes (java.nio.file.Files/readAllBytes
                    (java.nio.file.Path/of "dev/rabbit.jpg" (make-array String 0)))
      chunks       (partition-all 4096 source-bytes)]
  (with-open [image   (v/from-enum chunks)
              thumb   (v/thumbnail image 200)
              roundtripped (v/from-enum (v/write-to-stream thumb ".png" {:chunk-size 4096}))]
    (v/write-to-file roundtripped "rabbit-streamed.png")
    (v/info roundtripped)))
```

### Text Overlay

```clojure
(with-open [image  (v/from-file "dev/rabbit.jpg")
            label  (ops/text "ol.vips"
                             {:font "Sans Bold 48"
                              :rgba true})
            poster (ops/composite2 image label :over {:x 40 :y 40})]
  (v/write-to-file poster "rabbit-poster.png")
  (v/info poster))
```

## Why libvips?

* [Blazing fast][vipsspeed] and [memory efficient][vipsmemory]
* Comprehensive format support - handles all major image formats (JPEG, PNG, WebP, TIFF, HEIF, AVIF) without extra libraries or need to `exec` external programs
* [300 operations][vipsfuncs] covering arithmetic, histograms, convolution, morphological operations, frequency filtering, colour, resampling, statistics and others. 

## Licensing

The Clojure library `ol.vips` is copyright (C) 2026 Casey Link and is licensed under [EUPL-1.2](./LICENSE).

The platform-native companion jars under `native/` redistribute upstream
[sharp-libvips][sharp-libvips] binary bundles. Those redistributed native
binaries are licensed separately from the `ol.vips` source, principally under
LGPL-3.0-or-later, with additional bundled third-party component notices
documented in [THIRD-PARTY-NOTICES.md](./THIRD-PARTY-NOTICES.md).

[upstream]: https://github.com/libvips/libvips
[coffi]: https://github.com/IGJoshua/coffi
[vipsspeed]: https://github.com/libvips/libvips/wiki/Why-is-libvips-quick
[vipsmemory]: https://github.com/libvips/libvips/wiki/Speed-and-memory-use
[vipsfuncs]: https://www.libvips.org/API/current/func-list.html
[sharp-libvips]: https://github.com/lovell/sharp-libvips/releases
