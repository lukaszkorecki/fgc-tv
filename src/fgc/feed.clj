(ns fgc.feed
  "YouTube Atom feed fetching and parsing.

  The feed returns the ~15 most recent items and supports no paging, which is
  why state exists. It also serves no Last-Modified or ETag and ignores
  If-Modified-Since (verified 2026-08), so there is no conditional-request
  saving to be had; we send the header anyway in case that ever changes."
  (:require
   [babashka.http-client :as http]
   [clojure.data.xml :as xml]))

(defn- els [node] (filter map? (:content node)))
(defn- tag= [t node] (= t (name (:tag node))))
(defn- child [node t] (first (filter #(tag= t %) (els node))))
(defn- txt [node t] (some-> (child node t) :content first))

(defn- parse-entry [entry]
  (let [group (child entry "group")
        stats (some-> group (child "community") (child "statistics"))
        link (first (filter #(tag= "link" %) (els entry)))]
    {:video-id   (txt entry "videoId")
     :channel-id (txt entry "channelId")
     :title      (txt entry "title")
     :published  (txt entry "published")
     :updated    (txt entry "updated")
     :url        (or (get-in link [:attrs :href])
                     (str "https://www.youtube.com/watch?v=" (txt entry "videoId")))
     ;; A scheduled-but-not-yet-aired stream reports views="0" while a live one
     ;; reports a real count. It is the only upcoming/aired signal the feed
     ;; carries — there is no yt:liveBroadcastContent and no scheduled start time.
     :views      (some-> stats :attrs :views parse-long)}))

(defn parse
  "Atom XML string -> seq of video maps. Entries missing a video id are dropped."
  [body]
  (let [doc (xml/parse-str body)]
    (->> (els doc)
         (filter #(tag= "entry" %))
         (map parse-entry)
         (filter :video-id))))

(defn- retry-delay-ms [attempt] (* 2000 (long (Math/pow 2 attempt))))

(defn fetch
  "Fetches one feed with bounded exponential backoff on 429 and 5xx.

  Returns {:videos [...]} or {:error \"...\"}. Never throws: one dead channel
  must not take the site down, so the caller logs and carries on."
  [{:keys [url user-agent max-retries]}]
  (loop [attempt 0]
    (let [result
          (try
            (let [{:keys [status body]}
                  (http/get url {:headers {"User-Agent" user-agent
                                           "Accept" "application/atom+xml, text/xml"}
                                 :timeout 20000
                                 :throw false})]
              (cond
                (= 200 status) {:videos (parse body)}
                (or (= 429 status) (<= 500 status 599)) {:retry (str "HTTP " status)}
                :else {:error (str "HTTP " status)}))
            (catch Exception e {:retry (or (.getMessage e) (str (class e)))}))]
      (cond
        (:videos result) result
        (:error result) result
        (< attempt max-retries)
        (let [ms (retry-delay-ms attempt)]
          (println (format "  retrying in %dms (%s)" ms (:retry result)))
          (Thread/sleep ms)
          (recur (inc attempt)))
        :else {:error (str "gave up after " (inc max-retries) " attempts: " (:retry result))}))))
