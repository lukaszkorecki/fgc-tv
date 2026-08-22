(ns fgc.render
  "Static site generation. Hiccup data -> string, inline CSS, no JavaScript."
  (:require
   [clojure.string :as str]
   [fgc.html :as h]))

(def site-title "FGC.tv — CPT World Warrior & SFL broadcasts")
(def site-description
  (str "Every Capcom Pro Tour 2026 World Warrior and Street Fighter League "
       "broadcast, across all 25 regions, in one place. Unofficial fan project."))
(def capcom-schedule-url "https://sf.esports.capcom.com/cpt/schedule/")

(def favicon
  (str "data:image/svg+xml,"
       "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'>"
       "<rect width='32' height='32' rx='8' fill='rgb(200,16,46)'/>"
       "<text x='16' y='23' text-anchor='middle' font-family='Helvetica,Arial,sans-serif'"
       " font-size='17' font-weight='bold' fill='white'>W</text></svg>"))

;; ---------------------------------------------------------------- formatting

(defn region-label
  "Title-cases the region keyword, with overrides for the few that come out
  wrong — \"Uk Ireland\", \"Us East\" and friends."
  [region]
  (if (nil? region)
    "Unsorted"
    (let [titled (->> (str/split (name region) #"-")
                      (map str/capitalize)
                      (str/join " "))]
      (case titled
        "Uk Ireland" "UK & Ireland"
        "Us East" "US / Canada East"
        "Us West" "US / Canada West"
        "Us Midwest" "US Midwest"
        titled))))

(def class-label
  {:world-warrior "World Warrior" :sfl "SFL" :premier "Premier" :other "Other"})

(def class-css
  {:world-warrior "ww" :sfl "sfl" :premier "pr" :other "ot"})

(def round-label
  {:r1 "R1" :r2 "R2" :r3 "R3" :r4 "R4" :r5 "R5" :finals "Final"})

(defn utc-date
  "ISO-8601 instant -> compact UTC day like \"Aug 15\". The audience is global,
  so every timestamp on the page is UTC and the footer says so."
  [iso]
  (try
    (let [d (java.time.LocalDate/ofInstant (java.time.Instant/parse iso)
                                           java.time.ZoneOffset/UTC)
          months ["Jan" "Feb" "Mar" "Apr" "May" "Jun"
                  "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"]]
      (str (nth months (dec (.getMonthValue d))) " " (.getDayOfMonth d)))
    (catch Exception _ "—")))

(defn utc-year [iso]
  (try (str (.getYear (java.time.LocalDate/ofInstant (java.time.Instant/parse iso)
                                                     java.time.ZoneOffset/UTC)))
       (catch Exception _ "")))

(defn utc-stamp [inst]
  (-> (str (.truncatedTo inst java.time.temporal.ChronoUnit/MINUTES))
      (str/replace "T" " ")
      (str/replace "Z" " UTC")))

;; -------------------------------------------------------------------- styles

(def css "
:root{
--bg:#f6f5f2;--panel:#fff;--panel2:#faf9f6;--fg:#15151a;--dim:#63636e;--faint:#95959f;
--line:#e4e2dc;--line2:#efedE8;--accent:#c8102e;--ink:#fff;
--blue:#1f5fa9;--gold:#8a6300;--chip:#f1efea;
--shadow:0 1px 2px rgba(0,0,0,.04),0 10px 26px -14px rgba(0,0,0,.16)}
@media(prefers-color-scheme:dark){:root{
--bg:#0d0d10;--panel:#16161b;--panel2:#121217;--fg:#eceae6;--dim:#9a9aa6;--faint:#6a6a77;
--line:#25252d;--line2:#1d1d24;--accent:#ff4d5e;--ink:#1a0206;
--blue:#74aeee;--gold:#e2b545;--chip:#20202a;
--shadow:0 1px 2px rgba(0,0,0,.4),0 12px 32px -16px rgba(0,0,0,.8)}}
*{box-sizing:border-box}
html{scroll-behavior:smooth;scroll-padding-top:4rem}
body{margin:0;background:var(--bg);color:var(--fg);
font:16px/1.5 -apple-system,BlinkMacSystemFont,'Segoe UI',Inter,Roboto,Helvetica,Arial,sans-serif;
-webkit-text-size-adjust:100%;-webkit-font-smoothing:antialiased}
body::before{content:'';position:fixed;inset:0 0 auto 0;height:3px;z-index:50;
background:linear-gradient(90deg,var(--accent),var(--gold) 55%,var(--blue))}
.wrap{max-width:74rem;margin:0 auto;padding:0 1.1rem 5rem}
a{color:var(--blue)}

/* hero */
.hero{padding:2.6rem 0 1.4rem}
.kicker{font-size:.68rem;font-weight:700;letter-spacing:.16em;text-transform:uppercase;
color:var(--accent);margin:0 0 .5rem}
h1{font-size:clamp(1.75rem,4.4vw,2.7rem);font-weight:800;letter-spacing:-.033em;
line-height:1.03;margin:0;max-width:20ch}
.lede{color:var(--dim);font-size:.95rem;margin:.7rem 0 0;max-width:56ch}
.stats{display:flex;gap:2.2rem;flex-wrap:wrap;margin:1.5rem 0 0;padding:1.1rem 0 0;
border-top:1px solid var(--line)}
.stat b{display:block;font-size:1.45rem;font-weight:800;letter-spacing:-.02em;
line-height:1;font-variant-numeric:tabular-nums}
.stat span{display:block;font-size:.65rem;letter-spacing:.1em;text-transform:uppercase;
color:var(--faint);margin-top:.35rem}
.stat.live b{color:var(--accent)}

/* sticky region jump bar */
.jump{position:sticky;top:0;z-index:30;background:var(--bg);border-bottom:1px solid var(--line);
margin:0 -1.1rem;padding:.55rem 1.1rem;overflow-x:auto;white-space:nowrap;
scrollbar-width:thin}
.jump a{display:inline-block;font-size:.73rem;font-weight:500;padding:.24rem .55rem;
margin-right:.15rem;border-radius:7px;color:var(--dim);text-decoration:none}
.jump a:hover{background:var(--chip);color:var(--fg)}
.jump a.on{color:var(--accent);font-weight:700}

/* section headings */
h2{font-size:.72rem;font-weight:700;letter-spacing:.14em;text-transform:uppercase;
color:var(--faint);margin:2.6rem 0 .9rem;display:flex;align-items:center;gap:.8rem}
h2::after{content:'';flex:1;height:1px;background:var(--line)}

.card{background:var(--panel);border:1px solid var(--line);border-radius:14px;
box-shadow:var(--shadow);overflow:hidden}
.card.hot{border-color:var(--accent)}
.blurb{font-size:.78rem;color:var(--dim);margin:0;padding:.8rem 1rem;
border-bottom:1px solid var(--line2);background:var(--panel2)}

/* video rows */
ul.vids{list-style:none;margin:0;padding:0}
ul.vids li{display:grid;grid-template-columns:3.6rem minmax(0,1fr);gap:.1rem .8rem;
padding:.6rem 1rem;border-top:1px solid var(--line2)}
ul.vids li:first-child{border-top:0}
ul.vids li:hover{background:var(--panel2)}
.d{grid-row:span 2;font-size:.72rem;font-weight:600;color:var(--faint);
font-variant-numeric:tabular-nums;padding-top:.12rem}
.d small{display:block;font-size:.62rem;font-weight:400;opacity:.75}
.t{font-size:.9rem;font-weight:500;line-height:1.38;overflow-wrap:anywhere}
.t a{color:var(--fg);text-decoration:none}
.t a:hover{color:var(--accent);text-decoration:underline;text-underline-offset:2px}
.meta{display:flex;flex-wrap:wrap;align-items:center;gap:.4rem;margin-top:.22rem;
font-size:.7rem;color:var(--faint)}
.tg{font-size:.6rem;font-weight:700;letter-spacing:.07em;text-transform:uppercase;
padding:.06rem .34rem;border-radius:4px;border:1px solid currentColor;white-space:nowrap}
.tg.ww{color:var(--accent)}.tg.sfl{color:var(--blue)}.tg.pr{color:var(--gold)}
.tg.ot{color:var(--faint)}
.tg.up{background:var(--accent);border-color:var(--accent);color:var(--ink)}

/* region grid */
.regions{display:grid;gap:1rem;grid-template-columns:1fr;align-items:start}
@media(min-width:54rem){.regions{grid-template-columns:1fr 1fr}}
.rc>header{padding:.85rem 1rem .75rem;border-bottom:1px solid var(--line2)}
.rtop{display:flex;align-items:flex-start;justify-content:space-between;gap:.6rem}
.rname{font-size:1rem;font-weight:700;letter-spacing:-.015em;margin:0}
.org{font-size:.72rem;color:var(--dim);margin:.15rem 0 0}
.count{font-size:.62rem;font-weight:700;letter-spacing:.06em;text-transform:uppercase;
padding:.16rem .45rem;border-radius:999px;background:var(--chip);color:var(--dim);
white-space:nowrap}
.count.has{background:var(--accent);color:var(--ink)}
.countries{font-size:.7rem;line-height:1.45;color:var(--faint);margin:.45rem 0 0;
display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.chan{display:flex;gap:.4rem;margin:.5rem 0 0;flex-wrap:wrap}
.chan a{font-size:.68rem;font-weight:600;text-decoration:none;color:var(--dim);
border:1px solid var(--line);border-radius:6px;padding:.1rem .42rem}
.chan a:hover{border-color:var(--accent);color:var(--accent)}
.note{font-size:.72rem;color:var(--faint);font-style:italic;margin:.5rem 0 0}
.empty{padding:.9rem 1rem;font-size:.78rem;color:var(--faint)}
.rc.quiet{opacity:.68}
.rc.quiet:hover{opacity:1}

/* schedule chips */
.sched{display:flex;flex-wrap:wrap;gap:.28rem;padding:.65rem 1rem .8rem;
border-top:1px solid var(--line2);background:var(--panel2)}
.ev{font-size:.66rem;font-weight:600;color:var(--dim);background:var(--panel);
border:1px solid var(--line);border-radius:6px;padding:.14rem .4rem;
text-decoration:none;white-space:nowrap;font-variant-numeric:tabular-nums}
.ev:hover{border-color:var(--accent);color:var(--fg)}
.ev.fin{border-color:var(--accent);color:var(--accent)}
.ev b{font-weight:700}
.ev i{font-style:normal;opacity:.7;margin-left:.2rem}

footer{margin-top:3.5rem;padding-top:1.2rem;border-top:1px solid var(--line);
font-size:.76rem;line-height:1.6;color:var(--faint)}
footer p{margin:0 0 .6rem;max-width:70ch}
footer a{color:var(--dim)}

/* debug page */
.scroll{overflow-x:auto;-webkit-overflow-scrolling:touch}
table{width:100%;border-collapse:collapse;font-size:.78rem}
td,th{text-align:left;padding:.4rem .6rem;border-bottom:1px solid var(--line2);
vertical-align:top}
th{font-size:.64rem;letter-spacing:.09em;text-transform:uppercase;color:var(--faint)}
")

;; ------------------------------------------------------------------ fragments

(defn- video-li
  [{:keys [title url published class upcoming channel-name region]} {:keys [show-region?]}]
  [:li
   [:span.d (utc-date published) [:small (utc-year published)]]
   [:span.t [:a {:href url :rel "noopener"} title]]
   [:span.meta
    (when upcoming [:span.tg.up "upcoming"])
    [:span {:class (str "tg " (class-css class "ot"))} (class-label class)]
    (when (and show-region? region) [:span (region-label region)])
    (when channel-name [:span (str "· " channel-name)])]])

(defn- event-chips [events]
  (when (seq events)
    [:div.sched
     (for [{:keys [round date entry]} events]
       [:a.ev {:class (when (= :finals round) "fin") :href entry :rel "noopener"}
        [:b (round-label round (name round))] [:i date]])]))

(defn- channel-links [youtube twitch]
  (let [links (concat (for [u (distinct youtube)] [u "YouTube"])
                      (for [u (distinct twitch)] [u "Twitch"]))]
    (when (seq links)
      [:div.chan (for [[u label] links] [:a {:href u :rel "noopener"} label])])))

(defn- region-card
  [{:keys [region organizer countries events videos note youtube twitch]}]
  (let [n (count videos)]
    [:section.card.rc {:id (name region) :class (when (zero? n) "quiet")}
     [:header
      [:div.rtop
       [:div
        [:h3.rname (region-label region)]
        (when organizer [:p.org organizer])]
       [:span.count {:class (when (pos? n) "has")}
        (if (pos? n) (str n " VOD" (when (> n 1) "s")) "no vods")]]
      (when-not (str/blank? countries) [:p.countries {:title countries} countries])
      (channel-links youtube twitch)
      (when note [:p.note note])]
     (if (seq videos)
       [:ul.vids (for [v videos] (video-li v {}))]
       [:p.empty "Nothing on YouTube yet — this region may be streaming on Twitch."])
     (event-chips events)]))

;; ---------------------------------------------------------------------- page

(defn- head [{:keys [title description site-url path]}]
  (list
   [:meta {:charset "utf-8"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
   [:title title]
   [:meta {:name "description" :content description}]
   [:meta {:name "theme-color" :content "#c8102e"}]
   [:meta {:property "og:title" :content title}]
   [:meta {:property "og:description" :content description}]
   [:meta {:property "og:type" :content "website"}]
   [:meta {:property "og:url" :content (str site-url path)}]
   [:meta {:name "twitter:card" :content "summary"}]
   [:link {:rel "icon" :href favicon}]
   [:link {:rel "alternate" :type "application/atom+xml"
           :href (str site-url "/feed.xml") :title "New broadcasts"}]
   [:style (h/raw css)]))

(defn- footer []
  [:footer
   [:p [:strong "Unofficial fan project."] " Not affiliated with, endorsed by, or "
    "sponsored by Capcom. Every broadcast is hosted by its own organizer on "
    "YouTube or Twitch — nothing is mirrored or re-hosted here."]
   [:p "Schedule data derived from the "
    [:a {:href capcom-schedule-url :rel "noopener"} "official Capcom Pro Tour schedule"]
    ". Round dates are shown exactly as Capcom publishes them — month/day, with no "
    "year and no timezone — because inventing precision would be worse. Video "
    "timestamps are UTC."]
   [:p [:a {:href "/feed.xml"} "Atom feed"]]])

(defn index-page
  [{:keys [site-url generated regions upcoming latest premier stats]}]
  (str "<!doctype html>"
       (h/html
        [:html {:lang "en"}
         [:head (head {:title site-title :description site-description
                       :site-url site-url :path "/"})]
         [:body
          [:div.wrap
           [:header.hero
            [:p.kicker "Capcom Pro Tour 2026"]
            [:h1 "World Warrior & SFL broadcasts"]
            [:p.lede "Every World Warrior region and Street Fighter League show "
             "the organizers put on YouTube, collected in one place. "
             "25 regions, 18 channels, one list."]
            [:div.stats
             [:div.stat [:b (str (:broadcasts stats))] [:span "Broadcasts"]]
             [:div.stat [:b (str (:regions-live stats) "/" (:regions-total stats))]
              [:span "Regions with VODs"]]
             (when (pos? (:upcoming stats))
               [:div.stat.live [:b (str (:upcoming stats))] [:span "Upcoming"]])
             [:div.stat [:b (utc-stamp generated)] [:span "Last updated"]]]]

           [:nav.jump
            [:a {:href "#latest" :class "on"} "Latest"]
            (when (seq upcoming) [:a {:href "#upcoming"} "Upcoming"])
            (when (seq premier) [:a {:href "#premier"} "Premier"])
            (for [{:keys [region videos]} regions]
              [:a {:href (str "#" (name region))
                   :class (when (seq videos) "on")}
               (region-label region)])]

           (when (seq upcoming)
             (list
              [:h2 {:id "upcoming"} "Upcoming"]
              [:section.card.hot
               [:p.blurb "Streams that are scheduled but have not aired yet. "
                "YouTube's feed carries no start time, so open the link for the "
                "real schedule — and note that not every scheduled stream shows "
                "up here in time."]
               [:ul.vids (for [v upcoming] (video-li v {:show-region? true}))]]))

           [:h2 {:id "latest"} "Latest broadcasts"]
           [:section.card
            (if (seq latest)
              [:ul.vids (for [v latest] (video-li v {:show-region? true}))]
              [:p.empty "Nothing yet — run the poller."])]

           (when (seq premier)
             (list
              [:h2 {:id "premier"} "CPT Premier & majors"]
              [:section.card
               [:p.blurb "Global events rather than a single World Warrior region."]
               [:ul.vids (for [v premier] (video-li v {}))]]))

           [:h2 "By region"]
           [:div.regions (for [r regions] (region-card r))]

           (footer)]]])))

(defn debug-page
  [{:keys [site-url generated rejected counts]}]
  (str "<!doctype html>"
       (h/html
        [:html {:lang "en"}
         [:head
          [:meta {:name "robots" :content "noindex"}]
          (head {:title "Filter debug — FGC.tv" :description "Rejected titles"
                 :site-url site-url :path "/_debug.html"})]
         [:body
          [:div.wrap
           [:header.hero
            [:p.kicker "Not public"]
            [:h1 "Filter debug"]
            [:p.lede "Everything the classifier rejected, newest first. Tune "
             [:code "patterns.edn"] " and run " [:code "bb render.clj"] " — "
             "reclassification is a pure function over stored state, so no "
             "refetch is needed and history is fixed retroactively."]
            [:div.stats
             (for [[k v] (sort-by val > counts)]
               [:div.stat [:b (str v)] [:span (name k)]])
             [:div.stat [:b (utc-stamp generated)] [:span "Generated"]]]]
           [:h2 (str "Rejected (" (count rejected) ")")]
           [:section.card
            [:div.scroll
             [:table
              [:tr [:th "Date"] [:th "Channel"] [:th "Title"]]
              (for [{:keys [published channel-name title url]} rejected]
                [:tr
                 [:td (utc-date published)]
                 [:td (or channel-name "?")]
                 [:td [:a {:href url :rel "noopener"} title]]])]]]
           [:p.note [:a {:href "/"} "← back to the site"]]]]])))

;; ----------------------------------------------------------------- atom feed

(defn atom-feed
  "Atom feed of newly-seen broadcasts, ordered by when we first saw them — the
  useful ordering for a subscriber, unlike YouTube's published date, which for a
  scheduled stream is its creation time."
  [{:keys [site-url generated videos]}]
  (str
   "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
   (h/xml
    [:feed {:xmlns "http://www.w3.org/2005/Atom"}
     [:title site-title]
     [:subtitle site-description]
     [:link {:href (str site-url "/feed.xml") :rel "self"}]
     [:link {:href site-url}]
     [:id (str site-url "/")]
     [:updated (str generated)]
     (for [{:keys [video-id title url first-seen published class region]} videos]
       [:entry
        [:id (str "urn:ytvideo:" video-id)]
        [:title title]
        [:link {:href url}]
        [:updated (or first-seen published)]
        [:published (or published first-seen)]
        [:category {:term (name (or class :other))}]
        (when region [:category {:term (name region)}])
        [:summary (str (class-label class)
                       (when region (str " · " (region-label region))))]])])))

(def robots-txt
  "User-agent: *\nAllow: /\nDisallow: /_debug.html\n")
