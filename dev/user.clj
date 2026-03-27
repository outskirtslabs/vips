(ns user
  (:require
   #_[portal.api :as p]
   [clj-reload.core :as clj-reload]))

((requiring-resolve 'hashp.install/install!))

(set! *warn-on-reflection* true)

;; Configure the paths containing clojure sources we want clj-reload to reload
(clj-reload/init {:dirs      ["src" "dev" "test"]
                  :no-reload #{'user 'dev 'ol.dev.portal}})

(comment
  (defonce ps ((requiring-resolve 'ol.dev.portal/open-portals)))

  (clj-reload/reload)
  (clj-reload/reload {:only :all}) ;; rcf
  #_(reset! my-portal/portal-state nil)
  (clojure.repl.deps/sync-deps)
  ;;;
  )
