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

;; ---------------------------------------------------------------- formatting

(defn region-label [region]
  (if (nil? region)
    "Unsorted"
    (->> (str/split (name region) #"-")
         (map str/capitalize)
         (str/join " ")
         ;; a couple of names that title-casing gets wrong
         (#(-> %
               (str/replace "Uk Ireland" "UK & Ireland")
               (str/replace "Us East" "US / Canada East")
               (str/replace "Us West" "US / Canada West")
               (str/replace "Us Midwest" "US Midwest"))))))

(def class-label
  {:world-warrior "World Warrior"
   :sfl "SFL"
   :premier "Premier"
   :other "Other"})

(def round-label
  {:r1 "Round 1" :r2 "Round 2" :r3 "Round 3" :r4 "Round 4" :r5 "Round 5"
   :finals "Regional Final"})

(defn utc-date
  "Renders an ISO-8601 instant as a plain UTC day. The audience is global, so
  everything on the page is UTC and says so."
  [iso]
  (try
    (subs (str (.truncatedTo (java.time.Instant/parse iso) java.time.temporal.ChronoUnit/DAYS)) 0 10)
    (catch Exception _ (or iso "?"))))

(defn utc-stamp [inst]
  (-> (str (.truncatedTo inst java.time.temporal.ChronoUnit/MINUTES))
      (str/replace "T" " ")
      (str/replace "Z" " UTC")))

;; -------------------------------------------------------------------- styles

(def css "
:root{--bg:#fbfaf8;--fg:#1a1a1a;--dim:#5f5f66;--line:#e2e0da;--card:#fff;
--accent:#a4262c;--accent2:#1d4e89;--chip:#f0eee9}
@media(prefers-color-scheme:dark){:root{--bg:#131316;--fg:#e9e8e4;--dim:#9b9aa3;
--line:#2b2b32;--card:#1b1b20;--accent:#e8646a;--accent2:#7fb2ea;--chip:#26262d}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);
font:16px/1.55 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;
-webkit-text-size-adjust:100%}
.wrap{max-width:56rem;margin:0 auto;padding:1.5rem 1rem 4rem}
header{border-bottom:2px solid var(--line);padding-bottom:1rem;margin-bottom:1.5rem}
h1{font-size:1.6rem;margin:0 0 .3rem;letter-spacing:-.01em}
h2{font-size:1.15rem;margin:2.5rem 0 .75rem;padding-bottom:.3rem;
border-bottom:1px solid var(--line)}
h3{font-size:1rem;margin:0 0 .15rem}
a{color:var(--accent2)}
.sub{color:var(--dim);font-size:.9rem;margin:0}
.stamp{display:inline-block;margin-top:.6rem;font-size:.8rem;color:var(--dim);
background:var(--chip);border-radius:999px;padding:.15rem .6rem}
.nav{margin:.9rem 0 0;font-size:.85rem;line-height:2}
.nav a{margin-right:.9rem;white-space:nowrap}
.card{background:var(--card);border:1px solid var(--line);border-radius:10px;
padding:.9rem 1rem;margin-bottom:1rem}
.rhead{display:flex;flex-wrap:wrap;gap:.5rem;align-items:baseline;
justify-content:space-between;margin-bottom:.5rem}
.countries{color:var(--dim);font-size:.78rem;margin:.1rem 0 0}
.links{font-size:.85rem;white-space:nowrap}
ul.vids{list-style:none;margin:.6rem 0 0;padding:0}
ul.vids li{padding:.4rem 0;border-top:1px solid var(--line);display:flex;
gap:.6rem;align-items:baseline;flex-wrap:wrap}
.date{color:var(--dim);font-size:.78rem;font-variant-numeric:tabular-nums;
flex:0 0 5.5rem}
.vt{flex:1 1 16rem;min-width:0}
.tag{font-size:.65rem;text-transform:uppercase;letter-spacing:.05em;
padding:.1rem .4rem;border-radius:4px;background:var(--chip);color:var(--dim);
white-space:nowrap}
.tag.ww{color:var(--accent)}
.tag.up{background:var(--accent2);color:#fff}
.sched{margin:.7rem 0 0;padding:.6rem 0 0;border-top:1px dashed var(--line);
font-size:.82rem;color:var(--dim);display:flex;flex-wrap:wrap;gap:.4rem}
.ev{background:var(--chip);border-radius:4px;padding:.1rem .45rem;white-space:nowrap}
.ev.fin{outline:1px solid var(--accent);color:var(--fg)}
.note{font-size:.82rem;color:var(--dim);font-style:italic;margin:.4rem 0 0}
.empty{font-size:.85rem;color:var(--dim);margin:.5rem 0 0}
footer{margin-top:3rem;padding-top:1rem;border-top:1px solid var(--line);
font-size:.8rem;color:var(--dim)}
table{width:100%;border-collapse:collapse;font-size:.82rem}
td,th{text-align:left;padding:.3rem .5rem;border-bottom:1px solid var(--line);
vertical-align:top}
.scroll{overflow-x:auto}
")

;; ------------------------------------------------------------------ fragments

(defn- video-li [{:keys [title url published class upcoming]}]
  [:li
   [:span.date (utc-date published)]
   [:span.vt [:a {:href url :rel "noopener"} title]]
   (when upcoming [:span.tag.up "upcoming"])
   [:span {:class (str "tag" (when (= :world-warrior class) " ww"))}
    (class-label class)]])

(defn- event-chips [events]
  (when (seq events)
    [:div.sched
     (for [{:keys [round date entry]} events]
       [:a.ev {:class (when (= :finals round) "fin") :href entry :rel "noopener"}
        (str (round-label round (name round)) " · " date)])]))

(defn- channel-links [{:keys [youtube twitch]}]
  [:span.links
   (interpose " · "
              (concat
               (for [u (distinct youtube)] [:a {:href u :rel "noopener"} "YouTube"])
               (for [u (distinct twitch)] [:a {:href u :rel "noopener"} "Twitch"])))])

(defn- region-card [{:keys [region organizer countries events videos note youtube twitch]}]
  [:section.card {:id (name region)}
   [:div.rhead
    [:div
     [:h3 (region-label region)]
     [:p.countries (str (when organizer (str organizer " · ")) countries)]]
    (channel-links {:youtube youtube :twitch twitch})]
   (when note [:p.note note])
   (if (seq videos)
     [:ul.vids (for [v videos] (video-li v))]
     [:p.empty "No broadcasts picked up from YouTube yet."])
   (event-chips events)])

;; ---------------------------------------------------------------------- page

(defn- head [{:keys [title description site-url path]}]
  (list
   [:meta {:charset "utf-8"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
   [:title title]
   [:meta {:name "description" :content description}]
   [:meta {:property "og:title" :content title}]
   [:meta {:property "og:description" :content description}]
   [:meta {:property "og:type" :content "website"}]
   [:meta {:property "og:url" :content (str site-url path)}]
   [:meta {:name "twitter:card" :content "summary"}]
   [:link {:rel "alternate" :type "application/atom+xml"
           :href (str site-url "/feed.xml") :title "New broadcasts"}]
   [:style (h/raw css)]))

(defn- footer []
  [:footer
   [:p "Unofficial fan project. Not affiliated with, endorsed by, or sponsored by "
    "Capcom. All broadcasts are hosted by their respective organizers on YouTube "
    "and Twitch; nothing is mirrored here."]
   [:p "Schedule data derived from the "
    [:a {:href capcom-schedule-url :rel "noopener"} "official Capcom Pro Tour schedule"] ". "
    "Event dates are shown exactly as Capcom publishes them (month/day, no year "
    "or timezone given). Video timestamps are UTC."]])

(defn index-page
  [{:keys [site-url generated regions upcoming latest premier]}]
  (str "<!doctype html>"
       (h/html
        [:html {:lang "en"}
         [:head (head {:title site-title :description site-description
                       :site-url site-url :path "/"})]
         [:body
          [:div.wrap
           [:header
            [:h1 "CPT World Warrior & SFL broadcasts"]
            [:p.sub "Every Capcom Pro Tour 2026 World Warrior region and Street "
             "Fighter League broadcast the organizers put on YouTube, in one list."]
            [:p [:span.stamp (str "Updated " (utc-stamp generated))]]
            [:nav.nav
             [:a {:href "#latest"} "Latest"]
             (when (seq upcoming) [:a {:href "#upcoming"} "Upcoming"])
             (when (seq premier) [:a {:href "#premier"} "Premier & majors"])
             (for [{:keys [region]} regions]
               [:a {:href (str "#" (name region))} (region-label region)])]]

           (when (seq upcoming)
             (list
              [:h2 {:id "upcoming"} "Upcoming"]
              [:section.card
               [:p.note "Detected from streams that are scheduled but have not "
                "aired yet. YouTube's feed gives no start time, so check the link "
                "for the actual schedule."]
               [:ul.vids (for [v upcoming] (video-li v))]]))

           [:h2 {:id "latest"} "Latest broadcasts"]
           [:section.card
            (if (seq latest)
              [:ul.vids (for [v latest] (video-li v))]
              [:p.empty "Nothing yet — run the poller."])]

           (when (seq premier)
             (list
              [:h2 {:id "premier"} "CPT Premier & majors"]
              [:section.card
               [:p.note "Global events rather than a single World Warrior region."]
               [:ul.vids (for [v premier] (video-li v))]]))

           [:h2 "By region"]
           (for [r regions] (region-card r))

           (footer)]]])))

(defn debug-page
  [{:keys [site-url generated rejected counts]}]
  (str "<!doctype html>"
       (h/html
        [:html {:lang "en"}
         [:head
          [:meta {:name "robots" :content "noindex"}]
          (head {:title "Filter debug" :description "Rejected titles"
                 :site-url site-url :path "/_debug.html"})]
         [:body
          [:div.wrap
           [:header
            [:h1 "Filter debug"]
            [:p.sub "Everything the classifier rejected, newest first. Tune "
             [:code "patterns.edn"] " and re-render — reclassification is a pure "
             "function over stored state, so no refetch is needed."]
            [:p [:span.stamp (str "Updated " (utc-stamp generated))]]]
           [:h2 "Counts"]
           [:div.scroll
            [:table
             [:tr [:th "class"] [:th "rows"]]
             (for [[k v] (sort-by val > counts)]
               [:tr [:td (str (name k))] [:td (str v)]])]]
           [:h2 (str "Rejected (" (count rejected) ")")]
           [:div.scroll
            [:table
             [:tr [:th "published"] [:th "channel"] [:th "title"]]
             (for [{:keys [published channel-name title url]} rejected]
               [:tr
                [:td (utc-date published)]
                [:td (or channel-name "?")]
                [:td [:a {:href url :rel "noopener"} title]]])]]]]])))

;; ----------------------------------------------------------------- atom feed

(defn atom-feed
  "Atom feed of newly-seen broadcasts, ordered by when we first saw them —
  which is the useful ordering for a subscriber, unlike YouTube's published
  date (for a scheduled stream that is its creation time)."
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
        [:summary (str (class-label class) (when region (str " · " (region-label region))))]])])))

(def robots-txt
  "User-agent: *\nAllow: /\nDisallow: /_debug.html\n")
