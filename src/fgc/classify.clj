(ns fgc.classify
  "Title-based classification, driven entirely by patterns.edn.

  Everything here is a pure function of a stored record plus the pattern file,
  so tuning a regex and re-rendering fixes history retroactively — no refetch.")

(defn- compile-any [patterns]
  (mapv re-pattern patterns))

(defn compile-rules
  "Turns the raw patterns.edn map into compiled matchers, once per run."
  [raw]
  {:classes (mapv (fn [{:keys [class any]}]
                    {:class class :any (compile-any any)})
                  (:classes raw))
   :exclude (compile-any (:exclude raw))
   :region-hints (into {} (map (fn [[region pats]] [region (compile-any pats)]))
                       (:region-hints raw))})

(defn- matches-any? [patterns s]
  (boolean (some #(re-find % s) patterns)))

(defn classify
  "Returns :world-warrior, :sfl or :other for a title."
  [{:keys [classes exclude]} title]
  (let [title (or title "")]
    (if (matches-any? exclude title)
      :other
      (or (some (fn [{:keys [class any]}]
                  (when (matches-any? any title) class))
                classes)
          :other))))

(defn region-for
  "Picks the region for a video.

  A channel serving one region needs no guessing. For a multi-region channel
  (Saltmine covers four, Capcom Fighters three) we look for a region hint in the
  title, but only among regions that channel actually broadcasts — so a stray
  country name cannot misfile a video into someone else's region. An ambiguous
  or hintless title returns nil and renders under the channel with no region."
  [{:keys [region-hints]} channel-regions title]
  (let [title (or title "")]
    (if (= 1 (count channel-regions))
      (first channel-regions)
      (let [hits (filter (fn [region]
                           (matches-any? (get region-hints region []) title))
                         channel-regions)]
        (when (= 1 (count hits)) (first hits))))))

(defn annotate
  "Attaches :class and :region to a record. `channels-by-id` maps channel-id to
  its channels.edn entry."
  [rules channels-by-id record]
  (let [channel (get channels-by-id (:channel-id record))
        regions (:regions channel)]
    (assoc record
           :class (classify rules (:title record))
           :region (region-for rules regions (:title record))
           :channel-name (:name channel))))

(def shown-classes
  "Classes the public page renders. Anything else is kept in state but only
  appears in _debug.html."
  #{:world-warrior :sfl :premier})

(defn broadcast?
  "Is this something the public page should show?"
  [record]
  (contains? shown-classes (:class record)))

(defn upcoming?
  "Best-effort 'scheduled but not yet aired'.

  views=0 is the only signal the Atom feed gives (a live stream already reports
  a real count), so this is advisory: a brand-new upload also reads as 0 for a
  few minutes. Bounded to recently published items so an abandoned zero-view
  placeholder does not sit at the top of the page forever."
  [record now-ms]
  (and (some-> (:views record) zero?)
       (when-let [p (:published record)]
         (try
           (let [t (.toEpochMilli (java.time.Instant/parse p))]
             (< (- now-ms t) (* 14 24 60 60 1000)))
           (catch Exception _ false)))))
