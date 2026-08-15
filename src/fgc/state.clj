(ns fgc.state
  "The video store: one JSON object per line, keyed on video-id.

  Deliberately not SQLite. A few hundred rows do not need query planning, and a
  text file can be diffed, grepped and hand-edited when a title or a
  classification comes out wrong."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn read-state
  "Returns a map of video-id -> record. An absent file is empty state, not an
  error: the first run in a fresh container has nothing to read. Individual
  unparseable lines are skipped with a warning rather than killing the run."
  [path]
  (if-not (.exists (io/file path))
    (do (println (format "state: %s absent, starting empty" path))
        {})
    (with-open [r (io/reader path)]
      (let [{:keys [rows bad]}
            (reduce (fn [acc line]
                      (if (str/blank? line)
                        acc
                        (if-let [rec (try (json/parse-string line true)
                                          (catch Exception _ nil))]
                          (update acc :rows assoc (:video-id rec) rec)
                          (update acc :bad inc))))
                    {:rows {} :bad 0}
                    (line-seq r))]
        (when (pos? bad)
          (println (format "state: WARNING skipped %d unparseable line(s)" bad)))
        (println (format "state: read %d rows from %s" (count rows) path))
        rows))))

(defn write-state!
  "Writes to a temp file in the same directory, then renames. A container killed
  mid-write leaves the previous store intact — a partially written JSONL that
  replaced good state would lose history permanently."
  [path rows]
  (let [file (io/file path)
        dir (or (.getParentFile file) (io/file "."))
        _ (fs/create-dirs dir)
        tmp (fs/create-temp-file {:dir (str dir) :prefix "videos-" :suffix ".jsonl"})]
    (with-open [w (io/writer (str tmp))]
      (doseq [rec (sort-by :video-id (vals rows))]
        (.write w (json/generate-string rec))
        (.write w "\n")))
    (fs/move tmp path {:replace-existing true :atomic-move true})
    ;; The temp file is created 0600; without this the store would keep those
    ;; perms after the rename, which surprises anything copying it out of the
    ;; container as a different user.
    (try (fs/set-posix-file-permissions path "rw-r--r--")
         (catch Exception _ nil))
    (println (format "state: wrote %d rows to %s" (count rows) path))))

(defn upsert
  "Merges a freshly fetched video into state.

  Titles are refreshed on every poll because livestream titles are routinely
  edited after the fact (\"LIVE: ...\" becoming \"Top 8 - ...\"). first-seen is
  never overwritten, so the site can order by discovery rather than by YouTube's
  published timestamp, which for a scheduled stream is its creation date."
  [rows {:keys [video-id] :as video} now]
  (let [existing (get rows video-id)]
    (assoc rows video-id
           (merge existing
                  video
                  {:first-seen (or (:first-seen existing) now)
                   :last-seen now}))))

(defn prune-before
  "Drops rows first seen before `date` (an ISO-8601 prefix such as 2026-01-01).
  Manual maintenance only; the hourly run never deletes anything."
  [rows date]
  (into {} (remove (fn [[_ v]] (neg? (compare (str (:first-seen v)) date))) rows)))
