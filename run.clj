#!/usr/bin/env bb
;; Container entrypoint: poll, then render, then exit.
;;
;; Runs to completion and exits. Scheduling and hosting are external concerns —
;; there is no loop, no daemon, no cron here.
;;
;; Exit codes:
;;   0  success, including partial feed failures (one dead channel is a warning)
;;   1  total failure: every feed failed, state unreadable, or render blew up

(require '[fgc.config :as config]
         '[fgc.poll :as poll]
         '[fgc.site :as site])

(defn- fatal [msg e]
  (println (str "run: FATAL " msg (when e (str " — " (ex-message e)))))
  (System/exit 1))

(let [cfg (config/config)
      started (System/currentTimeMillis)]
  (println (str "run: starting " (java.time.Instant/now)))

  (let [{:keys [ok feeds-ok feeds-total failures]}
        (try (poll/poll! cfg)
             (catch Exception e (fatal "poll" e)))]
    (when-not ok
      (fatal (format "every feed failed (%d/%d)" feeds-ok feeds-total) nil))
    (when (seq failures)
      (println (format "run: %d/%d feeds ok, continuing with a partial update"
                       feeds-ok feeds-total))))

  (try (site/write-site! cfg)
       (catch Exception e (fatal "render" e)))

  (println (format "run: done in %.1fs" (/ (- (System/currentTimeMillis) started) 1000.0))))
