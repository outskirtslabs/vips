# ol.vips

> High-perf image processing for Clojure built with [libvips][upstream]. 

This package wraps the core functionality of [libvips][upstream] image processing library by exposing all native image operations as data and functions to Clojure programs.

We use [coffi][coffi] along with Java 22+ FFM (aka Project Panama) is used to provide speed and efficient native bridging.

## Installation

Install the main library plus exactly one native jar that matches your target platform.

```clojure
;; deps.edn
{:deps {com.outskirtslabs/vips {:mvn/version "0.0.1"}
        com.outskirtslabs/vips-native-linux-x86_64-gnu  {:mvn/version "1.2.4-0"}}}
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

### Transforming Images

```clojure
(with-open [image   (v/from-file "dev/rabbit.jpg")
            rotated (ops/rotate image 90.0)
            flipped (ops/flip rotated :horizontal)
            bw      (ops/colourspace flipped :b-w)]
  (v/write-to-file bw "rabbit-bw.jpg"))
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

### Calling Raw Operations

```clojure
(with-open [image   (v/from-file "dev/rabbit.jpg")
            rotated (v/call! "rotate" {:in image :angle 90.0})]
  (v/info rotated))
```

Use `v/operations` to list available libvips operations and `v/operation-info` to inspect their inputs and outputs.

## Why libvips?

* [Blazing fast][vipsspeed] and [memory efficient][vipsmemory]
* Comprehensive format support - handles all major image formats (JPEG, PNG, WebP, TIFF, HEIF, AVIF) without extra libraries or need to `exec` external programs
* [300 operations][vipsfuncs] covering arithmetic, histograms, convolution, morphological operations, frequency filtering, colour, resampling, statistics and others. 


[upstream]: https://github.com/libvips/libvips
[coffi]: https://github.com/IGJoshua/coffi
[vipsspeed]: https://github.com/libvips/libvips/wiki/Why-is-libvips-quick
[vipsmemory]: https://github.com/libvips/libvips/wiki/Speed-and-memory-use
[vipsfuncs]: https://www.libvips.org/API/current/func-list.html
