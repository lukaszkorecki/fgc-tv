#!/usr/bin/env bb
;; One-shot, run locally. Resolves the YouTube channel URLs in ww2026.edn to
;; Atom-feed channel IDs and writes channels.edn, which is a committed artifact.
;;
;; This must never run in the hourly container: it scrapes channel pages, which
;; is slow and brittle, whereas the poller only touches the stable feed endpoint.
;;
;;   bb resolve_channels.clj [--out channels.edn] [--schedule ww2026.edn]

(require
 '[babashka.http-client :as http]
 '[clojure.edn :as edn]
 '[clojure.java.io :as io]
 '[clojure.pprint :as pp]
 '[clojure.string :as str])

(def UA
  "Channel pages are served differently to obvious bots; the feed endpoint is not
  so fussy but this step needs to look like a browser."
  (str "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
       "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"))

(def REQUEST_DELAY_MS 1500)

(def known-channel-ids
  "Confirmed by hand, so we never refetch them."
  {"https://www.youtube.com/CapcomFighters"     "UCPGuorlvarThSlwJpyTHOmQ"
   "https://www.youtube.com/@CapcomFightersJP"  "UCu2Fxqf37DAZ0ZhIsd1FZwA"
   "https://www.youtube.com/@SaltmineLeague"    "UCgRCRmXJuLKZCFXBK6qd-Jg"})

(def channel-less-regions
  "Regions Capcom lists without a YouTube channel. Modelled explicitly rather
  than dropped, so the site can render them with an honest note."
  {:chile      "Twitch only (twitch.tv/UAFG, shared with South America East)."
   :middle-east (str "No organizer or channel listed by Capcom. Entry links point at "
                     "Capcom-run start.gg pages; likely @CapcomFighters but unconfirmed.")})

;; As of 2026-08 YouTube's channel HTML no longer contains the `"channelId":"UC..."`
;; key that older scrapers relied on. These two both survive.
(def id-patterns
  [#"\"externalId\":\"(UC[A-Za-z0-9_-]{22})\""
   #"<link rel=\"canonical\" href=\"https://www\.youtube\.com/channel/(UC[A-Za-z0-9_-]{22})\""])

(defn extract-channel-id [html]
  (some #(second (re-find % html)) id-patterns))

(defn extract-name [html]
  (or (second (re-find #"<meta property=\"og:title\" content=\"([^\"]+)\"" html))
      (second (re-find #"\"channelMetadataRenderer\":\{\"title\":\"([^\"]+)\"" html))))

(defn fetch-channel
  "Returns {:channel-id .. :name ..} or {:error ..}. Never throws."
  [url]
  (try
    (let [{:keys [status body]} (http/get url {:headers {"User-Agent" UA
                                                         "Accept-Language" "en-US,en;q=0.9"}
                                               :throw false})]
      (if (not= 200 status)
        {:error (str "HTTP " status)}
        (if-let [id (extract-channel-id body)]
          {:channel-id id :name (extract-name body)}
          {:error "no channel id in page"})))
    (catch Exception e
      {:error (.getMessage e)})))

(defn normalize-url
  "The EDN mixes bare and www hosts; canonicalize so the known-id lookup hits."
  [url]
  (-> url
      (str/replace #"^https?://(www\.)?youtube\.com" "https://www.youtube.com")
      (str/replace #"/$" "")))

(defn channel-index
  "Distinct YouTube URL -> sorted regions that broadcast on it."
  [schedule]
  (->> schedule
       (mapcat (fn [{:keys [region youtube]}]
                 (map (fn [u] [(normalize-url u) region]) youtube)))
       (reduce (fn [acc [url region]] (update acc url (fnil conj #{}) region)) {})
       (into (sorted-map))))

(defn resolve-all [schedule]
  (let [idx (channel-index schedule)]
    (println (format "%d regions, %d distinct YouTube channels" (count schedule) (count idx)))
    (doall
     (for [[url regions] idx
           :let [regions (vec (sort regions))]]
       (if-let [id (known-channel-ids url)]
         (do (println "known  " id url)
             {:channel-id id :url url :regions regions})
         (let [_ (Thread/sleep REQUEST_DELAY_MS)
               {:keys [channel-id name error]} (fetch-channel url)]
           (if error
             (do (println "FAILED " error url)
                 {:channel-id nil :url url :regions regions :error error})
             (do (println "resolved" channel-id (pr-str name) url)
                 {:channel-id channel-id :name name :url url :regions regions}))))))))

(defn feed-url [channel-id]
  (str "https://www.youtube.com/feeds/videos.xml?channel_id=" channel-id))

(defn build [schedule]
  (let [resolved (resolve-all schedule)
        with-feeds (map (fn [c] (cond-> c (:channel-id c) (assoc :feed (feed-url (:channel-id c)))))
                        resolved)
        noted (map (fn [[region note]] {:channel-id nil :regions [region] :note note})
                   channel-less-regions)]
    (vec (concat with-feeds (sort-by (comp first :regions) noted)))))

(defn -main [& args]
  (let [{:strs [--out --schedule]} (apply hash-map args)
        out (or --out "channels.edn")
        schedule-path (or --schedule "ww2026.edn")
        schedule (edn/read-string (slurp schedule-path))
        channels (build schedule)
        failures (filter :error channels)]
    (with-open [w (io/writer out)]
      (binding [*out* w]
        (println ";; Generated by resolve_channels.clj — committed artifact, do not")
        (println ";; regenerate in the hourly container. Hand-edit freely.")
        (pp/pprint channels)))
    (println)
    (println (format "wrote %s — %d entries, %d resolved, %d unresolved, %d channel-less regions"
                     out (count channels)
                     (count (filter :channel-id channels))
                     (count failures)
                     (count channel-less-regions)))
    (when (seq failures)
      (println "unresolved — paste IDs into channels.edn by hand:")
      (doseq [f failures] (println "  " (:url f) (:error f))))))

(apply -main *command-line-args*)
