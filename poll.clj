#!/usr/bin/env bb
;; Reads state, fetches every channel feed, upserts, writes state. No rendering.
;;
;;   bb poll.clj
;;   bb poll.clj --prune-before 2026-01-01   ; manual maintenance, does not fetch
;;
;; Exits non-zero only if every feed failed. A single dead channel is a warning.

(require '[fgc.config :as config]
         '[fgc.poll :as poll])

(let [cfg (config/config)
      {:strs [--prune-before]} (apply hash-map *command-line-args*)
      {:keys [ok]} (if --prune-before
                     (poll/prune! cfg --prune-before)
                     (poll/poll! cfg))]
  (when-not ok
    (println "poll: FATAL every feed failed")
    (System/exit 1)))
