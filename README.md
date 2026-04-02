# ol.vips

> High-perf image processing for Clojure built with [libvips][upstream]. 

This package wraps the core functionality of [libvips][upstream] image processing library by exposing all native image operations as data and functions to Clojure programs.

We use [coffi][coffi] along with Java 22+ FFM (aka Project Panama) is used to provide speed and efficient native bridging.


## Why libvips?

* [Blazing fast][vipsspeed] and [memory efficient][vipsmemory]
* Comprehensive format support - handles all major image formats (JPEG, PNG, WebP, TIFF, HEIF, AVIF) without extra libraries or need to `exec` external programs
* [300 operations][vipsfuncs] covering arithmetic, histograms, convolution, morphological operations, frequency filtering, colour, resampling, statistics and others. 


[upstream]: https://github.com/libvips/libvips
[coffi]: https://github.com/IGJoshua/coffi
[vipsspeed]: https://github.com/libvips/libvips/wiki/Why-is-libvips-quick
[vipsmemory]: https://github.com/libvips/libvips/wiki/Speed-and-memory-use
[vipsfuncs]: https://www.libvips.org/API/current/func-list.html
