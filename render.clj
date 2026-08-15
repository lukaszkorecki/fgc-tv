#!/usr/bin/env bb
;; Reads state + ww2026.edn, writes the static site to OUTPUT_DIR.
;;
;;   bb render.clj
;;
;; Never touches the network. Classification is recomputed from patterns.edn on
;; every render, so editing a regex and re-running fixes history retroactively.

(require '[fgc.config :as config]
         '[fgc.site :as site])

(site/write-site! (config/config))
