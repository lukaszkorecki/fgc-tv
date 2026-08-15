(ns fgc.html
  "A minimal Hiccup-compatible emitter.

  Hiccup itself is a maven dependency and pulling one in would mean a JVM and a
  dependency fetch at image build time, for roughly this much code. The data
  format is the familiar one: [:tag {:attr \"v\"} & children], nil children are
  dropped, and seqs are spliced.

  Everything is escaped by default. Use `raw` to opt out — the doctype and
  already-serialized fragments are the only legitimate uses."
  (:require
   [clojure.string :as str]))

(defrecord Raw [s])

(defn raw [s] (->Raw (str s)))

(def ^:private void-tags
  #{:area :base :br :col :embed :hr :img :input :link :meta :source :track :wbr})

(defn escape
  "Escapes text content. Safe for XML as well as HTML."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn escape-attr [s]
  (-> (escape s)
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(def ^:private tag-re #"([^.#]+)(?:#([^.]+))?((?:\.[^.#]+)*)")

(defn- parse-tag
  "Splits Hiccup's :tag#id.class.class shorthand.
  Returns [tag-name id classes-string]."
  [tag]
  (let [[_ nm id classes] (re-matches tag-re (name tag))]
    [nm id (when (seq classes) (str/join " " (rest (str/split classes #"\."))))]))

(defn- merge-attrs
  "Shorthand classes come first, then any :class in the attr map — same
  behaviour as hiccup, so [:span.tag {:class \"up\"}] gives class=\"tag up\"."
  [attrs id classes]
  (cond-> attrs
    id (assoc :id (or (:id attrs) id))
    classes (assoc :class (str classes (when-let [c (:class attrs)]
                                         (when-not (str/blank? c) (str " " c)))))))

(defn- attrs->str [attrs]
  (->> attrs
       (keep (fn [[k v]]
               (cond
                 (or (nil? v) (false? v)) nil
                 (true? v) (str " " (name k))
                 :else (str " " (name k) "=\"" (escape-attr v) "\""))))
       (str/join)))

(defn- render*
  "mode :html — void elements emit as <br>, everything else always gets a close tag.
   mode :xml  — any childless element self-closes as <link/>.

  The distinction matters: <link> is void in HTML but must be self-closed in the
  Atom feed, and an unclosed <link> makes the feed unparseable."
  [mode form]
  (cond
    (nil? form) ""
    (instance? Raw form) (:s form)
    (string? form) (escape form)
    (keyword? form) (escape (name form))
    (vector? form)
    (let [[tag & more] form
          has-attrs (map? (first more))
          children (if has-attrs (next more) more)
          [tag-name id classes] (parse-tag tag)
          attrs (merge-attrs (when has-attrs (first more)) id classes)
          body (str/join (map (partial render* mode) children))]
      (cond
        (and (= :html mode) (contains? void-tags (keyword tag-name)))
        (str "<" tag-name (attrs->str attrs) ">")

        (and (= :xml mode) (str/blank? body))
        (str "<" tag-name (attrs->str attrs) "/>")

        :else
        (str "<" tag-name (attrs->str attrs) ">" body "</" tag-name ">")))
    (sequential? form) (str/join (map (partial render* mode) form))
    :else (escape form)))

(defn html
  "Renders a hiccup form as HTML."
  [form]
  (render* :html form))

(defn xml
  "Renders a hiccup form as XML — childless elements self-close."
  [form]
  (render* :xml form))
