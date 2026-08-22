(ns fgc.site
  "Turns stored state + the schedule into the page model, then writes the site."
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [fgc.classify :as classify]
   [fgc.config :as config]
   [fgc.render :as render]
   [fgc.state :as state]))

(def latest-limit 40)
(def per-region-limit 25)
(def premier-limit 20)
(def feed-limit 50)
(def debug-limit 400)

(defn- newest-first [videos]
  (sort-by :published #(compare %2 %1) videos))

(defn- region-note
  "The 'no YouTube channel' note for regions Capcom lists without one."
  [channels region]
  (some :note (filter #(some #{region} (:regions %)) channels)))

(defn build-model
  [{:keys [state-path schedule-path channels-path patterns-path site-url]}]
  (let [schedule (config/read-edn schedule-path)
        channels (config/read-edn channels-path)
        rules (classify/compile-rules (config/read-edn patterns-path))
        channels-by-id (into {} (map (juxt :channel-id identity)) channels)
        rows (vals (state/read-state state-path))
        now (java.time.Instant/now)
        now-ms (.toEpochMilli now)
        ;; Recomputed here rather than trusting the poller's stored values —
        ;; this is what makes a patterns.edn edit fix history on re-render.
        annotated (map (fn [r]
                         (let [a (classify/annotate rules channels-by-id r)]
                           (assoc a :upcoming (classify/upcoming? a now-ms))))
                       rows)
        shown (filter classify/broadcast? annotated)
        rejected (remove classify/broadcast? annotated)
        by-region (group-by :region shown)
        upcoming (filter :upcoming shown)]
    {:site-url site-url
     :generated now
     :shown-count (count shown)
     :stats {:broadcasts (count shown)
             :regions-total (count schedule)
             :regions-live (count (filter #(seq (get by-region (:region %))) schedule))
             :upcoming (count upcoming)}
     :latest (take latest-limit (newest-first (remove :upcoming shown)))
     :upcoming (newest-first upcoming)
     ;; Premier stops are global events, so they have no World Warrior region
     ;; and get their own section rather than being filed under one.
     :premier (take premier-limit
                    (newest-first (filter #(and (= :premier (:class %)) (nil? (:region %)))
                                          shown)))
     :regions (for [{:keys [region organizer countries events youtube twitch]} schedule]
                {:region region
                 :organizer organizer
                 :countries (str/trim (str/replace (or countries "") #"\s+" " "))
                 :events events
                 :youtube youtube
                 :twitch twitch
                 :note (region-note channels region)
                 :videos (take per-region-limit (newest-first (get by-region region)))})
     :feed-videos (->> shown
                       (sort-by #(or (:first-seen %) (:published %)) #(compare %2 %1))
                       (take feed-limit))
     :rejected (take debug-limit (newest-first rejected))
     :counts (frequencies (map :class annotated))}))

(defn- write-file! [dir name content]
  (spit (io/file dir name) content)
  (println (format "  %-12s %6.1f KB" name (/ (count content) 1024.0))))

(defn write-site!
  "Renders every output file. Returns {:ok bool}."
  [{:keys [output-dir] :as cfg}]
  (let [model (build-model cfg)]
    (fs/create-dirs output-dir)
    (println (format "render: %d broadcasts shown, %d rejected -> %s"
                     (:shown-count model) (count (:rejected model)) output-dir))
    (write-file! output-dir "index.html" (render/index-page model))
    (write-file! output-dir "_debug.html" (render/debug-page model))
    (write-file! output-dir "feed.xml"
                 (render/atom-feed (assoc model :videos (:feed-videos model))))
    (write-file! output-dir "robots.txt" render/robots-txt)
    (println (str "render: counts " (pr-str (:counts model))))
    {:ok true :shown (:shown-count model)}))
