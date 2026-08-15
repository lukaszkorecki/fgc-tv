(ns fgc.poll
  "Fetch every channel feed and fold the results into state."
  (:require
   [fgc.classify :as classify]
   [fgc.config :as config]
   [fgc.feed :as feed]
   [fgc.state :as state]))

(defn- now-iso [] (str (java.time.Instant/now)))

(defn prune!
  "Manual maintenance. Never called by the hourly run."
  [{:keys [state-path]} date]
  (let [rows (state/read-state state-path)
        kept (state/prune-before rows date)]
    (println (format "prune: %d -> %d rows (dropped first-seen before %s)"
                     (count rows) (count kept) date))
    (state/write-state! state-path kept)
    {:ok true}))

(defn poll!
  "Returns {:ok bool :feeds-ok n :feeds-total n :rows n :failures [...]}.

  Partial failure is normal and non-fatal — one channel being unreachable must
  not take the site down. Only a total wipeout is reported as not-ok."
  [{:keys [state-path channels-path patterns-path request-delay-ms] :as cfg}]
  (let [channels (filter :channel-id (config/read-edn channels-path))
        rules (classify/compile-rules (config/read-edn patterns-path))
        channels-by-id (into {} (map (juxt :channel-id identity)) channels)
        now (now-iso)]
    (println (format "poll: %d channels" (count channels)))
    (loop [[{:keys [channel-id feed name] :as ch} & more] channels
           rows (state/read-state state-path)
           ok 0
           failures []]
      (if-not ch
        (do
          (state/write-state! state-path rows)
          (println (format "poll: %d/%d feeds ok, %d rows in store"
                           ok (count channels) (count rows)))
          (doseq [f failures] (println (str "poll: WARNING " f)))
          {:ok (or (pos? ok) (empty? channels))
           :feeds-ok ok :feeds-total (count channels)
           :rows (count rows) :failures failures})
        (let [_ (println (format "  fetching %s" (or name channel-id)))
              {:keys [videos error]} (feed/fetch (assoc cfg :url feed))]
          (when (seq more) (Thread/sleep request-delay-ms))
          (if error
            (recur more rows ok (conj failures (str (or name channel-id) ": " error)))
            (let [rows' (reduce (fn [acc v]
                                  ;; class/region are stored so the JSONL reads
                                  ;; standalone, but render always recomputes them.
                                  (state/upsert acc (classify/annotate rules channels-by-id v) now))
                                rows videos)]
              (println (format "    %d entries" (count videos)))
              (recur more rows' (inc ok) failures))))))))
