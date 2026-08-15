(ns fgc.config
  "Environment-driven configuration. Every value has a working default so the
  container needs no environment at all to do something sensible."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(defn env
  ([k default] (or (System/getenv k) default)))

(defn config []
  {:state-path    (env "STATE_PATH" "./state/videos.jsonl")
   :output-dir    (env "OUTPUT_DIR" "./public")
   :channels-path (env "CHANNELS_PATH" "./channels.edn")
   :schedule-path (env "SCHEDULE_PATH" "./ww2026.edn")
   :patterns-path (env "PATTERNS_PATH" "./patterns.edn")
   :site-url      (env "SITE_URL" "https://example.invalid")
   :user-agent    (env "USER_AGENT"
                       "fgc-tv/1.0 (CPT World Warrior broadcast aggregator; fan project)")
   :request-delay-ms (parse-long (env "REQUEST_DELAY_MS" "1200"))
   :max-retries      (parse-long (env "MAX_RETRIES" "3"))})

(defn read-edn
  "Reads an EDN file, throwing an ex-info that names the file if it is missing —
  a bare FileNotFoundException from deep in a pipeline is useless in container logs."
  [path]
  (if (.exists (io/file path))
    (edn/read-string (slurp path))
    (throw (ex-info (str "required file not found: " path) {:path path}))))
