#!/usr/bin/env bb
;; Local preview server for OUTPUT_DIR. Development only — production hosting is
;; external, and nothing in the container ever serves anything.
;;
;;   bb serve.clj              ; http://localhost:8000
;;   bb serve.clj --port 9000 --dir public
;;
;; Uses the http-kit that ships inside babashka rather than the separate
;; org.babashka/http-server library, so there is no dependency to resolve.

(require
 '[babashka.fs :as fs]
 '[clojure.java.io :as io]
 '[clojure.string :as str]
 '[fgc.config :as config]
 '[org.httpkit.server :as srv])

(def content-types
  {"html" "text/html; charset=utf-8"
   "xml"  "application/atom+xml; charset=utf-8"
   "txt"  "text/plain; charset=utf-8"
   "css"  "text/css; charset=utf-8"
   "js"   "application/javascript; charset=utf-8"
   "svg"  "image/svg+xml"
   "png"  "image/png"
   "jpg"  "image/jpeg"
   "ico"  "image/x-icon"})

(defn- content-type [path]
  (get content-types (str/lower-case (or (fs/extension path) "")) "application/octet-stream"))

(defn- resolve-path
  "Maps a request URI to a file under root, refusing anything that escapes it."
  [root uri]
  (let [decoded (java.net.URLDecoder/decode uri "UTF-8")
        rel (str/replace decoded #"^/+" "")
        rel (if (str/blank? rel) "index.html" rel)
        f (fs/path root rel)
        f (if (fs/directory? f) (fs/path f "index.html") f)]
    (when (str/starts-with? (str (fs/canonicalize f {:nofollow-links true}))
                            (str (fs/canonicalize root {:nofollow-links true})))
      f)))

(defn handler [root]
  (fn [{:keys [uri]}]
    (let [f (resolve-path root uri)]
      (if (and f (fs/regular-file? f))
        {:status 200
         :headers {"Content-Type" (content-type f)
                   ;; No caching: the whole point is reloading after a re-render.
                   "Cache-Control" "no-store"}
         :body (io/input-stream (fs/file f))}
        {:status 404
         :headers {"Content-Type" "text/plain; charset=utf-8"}
         :body (str "404 " uri "\n")}))))

(let [{:strs [--port --dir]} (apply hash-map *command-line-args*)
      port (parse-long (or --port (config/env "PORT" "8000")))
      root (or --dir (:output-dir (config/config)))]
  (when-not (fs/directory? root)
    (println (str "serve: " root " does not exist — run `bb render.clj` first"))
    (System/exit 1))
  (srv/run-server (handler root) {:port port})
  (println (format "serve: %s -> http://localhost:%d  (ctrl-c to stop)" root port))
  @(promise))
