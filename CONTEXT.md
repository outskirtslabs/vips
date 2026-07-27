# ol.vips

`ol.vips` gives Clojure programs access to the libvips image-processing
engine. This glossary defines the project's language for images, processing
pipelines, multipage and animated content, and native distribution.

## Language

### Product boundaries

**ol.vips**:
The Clojure image-processing library owned by this project.
_Avoid_: Vips, vips (both are ambiguous with libvips)

**libvips**:
The upstream native image-processing engine that performs image operations for `ol.vips`.
_Avoid_: ol.vips

**Main library**:
The platform-independent `ol.vips` artifact that provides the public Clojure API.
_Avoid_: Core jar

**Native companion jar**:
A platform-specific artifact that distributes a tested libvips build and its
native dependencies for use with the main library.
_Avoid_: Native jar, platform jar

**Native bundle**:
The platform-specific libvips binary set, native dependencies, and provenance
metadata carried by a native companion jar.
_Avoid_: Native companion jar (the jar carries the bundle; it is not the bundle)

**sharp-libvips**:
The upstream distributor of the prebuilt libvips bundles repackaged in the native companion jars.

**Platform ID**:
The canonical name for a supported operating-system, CPU-architecture, and, on Linux, C-library combination.
_Avoid_: Platform name, target name

### Images and processing

**Image**:
A logical image consisting of pixels, dimensions, bands, and header fields. It
may represent one page or multiple ordered pages.

**Image handle**:
A scoped reference to an image passed between `ol.vips` operations.
_Avoid_: Native pointer, VipsImage (when discussing the public API)

**Band**:
One component of every pixel; width, height, and bands form the three dimensions of a libvips image.
_Avoid_: Channel, except when quoting an external format's terminology

**Header field**:
A named value associated with an image, including core image facts and attached metadata.
_Avoid_: Property, attribute

**Metadata**:
Non-pixel information associated with an image, such as resolution, orientation, color profiles, and animation timing.
_Avoid_: EXIF (EXIF is only one kind of metadata)

**Operation**:
A named libvips capability that loads, transforms, analyzes, or saves images.
_Avoid_: Transform when the operation does not produce a transformed image

**Loader**:
An operation that reads an image source and produces an image.
_Avoid_: Decoder (a loader may do more than decode)

**Saver**:
An operation that encodes an image for a file, byte buffer, or stream.
_Avoid_: Encoder (a saver also delivers the encoded result)

**Pipeline**:
One or more connected image operations whose outputs feed later operations.
_Avoid_: Workflow, chain, graph

**Sink**:
A pipeline endpoint that requests pixel data and produces an encoded or materialized result.
_Avoid_: Saver when the sink does not encode an image

**Untrusted operation**:
An operation that libvips marks unsafe to expose to untrusted input without an explicit trust decision.

### Multipage and animated images

**Multipage image**:
An image containing ordered logical pages. libvips represents equal-height
pages as one vertical strip with page metadata.

**Page**:
One logical image within a multipage image. Use this format-neutral term for documents and other non-animated content.
_Avoid_: Frame unless the page participates in an animation

**Animated image**:
A multipage image whose pages are displayed over time according to frame delays and a loop count.

**Frame**:
A page of an animated image. Use this term when timing or animation behavior matters.
_Avoid_: Page when discussing animation timing

**Page count**:
The number of logical pages represented by a multipage image.
_Avoid_: Frame count unless the image is animated

**Page height**:
The pixel height of each logical page in an equal-height multipage image.
_Avoid_: Image height (the full image height spans every page)

**Frame delay**:
The time in milliseconds for which an animation frame is displayed before the next frame.
_Avoid_: Page delay

**Loop count**:
The number of times an animation repeats; `0` means forever for formats that use that convention.
_Avoid_: Iterations, repetitions
