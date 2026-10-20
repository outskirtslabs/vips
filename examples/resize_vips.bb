#!/usr/bin/env bb
;; Put ol.vips and your platform's native companion on the classpath.
;; bb examples/resize_vips.bb input.jpg output.jpg 320
(require '[ol.vips :as v]
         '[ol.vips.operations :as ops])

(let [[input output size] *command-line-args*
      size                (some-> size parse-long)]
  (when-not (and (= 3 (count *command-line-args*)) size (<= 1 size Integer/MAX_VALUE))
    (throw (ex-info "Usage: resize_vips.bb INPUT OUTPUT SIZE (positive pixels)" {})))
  (with-open [image (ops/thumbnail input size)]
    (v/write-to-file image output)))
