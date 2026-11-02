(ns metabase.asset-transfer.schema
  "The deliberately small, versioned OSS transfer format. All values use JSON wire types."
  (:require
   [clojure.string :as str]
   [metabase.util.json :as json])
  (:import
   (java.nio.charset StandardCharsets)
   (java.security MessageDigest)))

(set! *warn-on-reflection* true)

(def max-bytes "Maximum encoded bundle size." (* 10 1024 1024))
(def max-cards "Maximum cards in one transaction." 100)
(def engines "Drivers with reviewed connection-template fields." #{"postgres" "mysql" "clickhouse"})
(def displays "Display types covered by the v1 reference visitor." #{"table" "scalar" "line" "bar"})

(defn fail!
  "Throw a structured validation error without echoing query text or connection details."
  [code path message]
  (throw (ex-info message {:status-code 400 :code code :path path})))

(defn check!
  "Assert a transfer invariant."
  [pred code path message]
  (when-not pred (fail! code path message)))

(defn closed!
  "Reject unknown definition keys before persisting or resolving references."
  [value allowed path]
  (check! (map? value) "INVALID_FORMAT" path "Expected an object.")
  (check! (every? allowed (keys value)) "UNSUPPORTED_OPTION" path "This object contains unsupported options."))

(defn wire
  "Normalize keywords and JSON object keys to the representation received through HTTP."
  [value]
  (json/decode+kw (json/encode value)))

(defn digest
  "Order-independent digest of JSON data; preserves array order."
  [value]
  (letfn [(canonical [x]
            (cond
              (map? x) (into (sorted-map-by #(compare (str %1) (str %2)))
                             (map (fn [[k v]] [k (canonical v)])) x)
              (sequential? x) (mapv canonical x)
              :else x))]
    (let [bytes (.digest (MessageDigest/getInstance "SHA-256")
                         (.getBytes (json/encode (canonical (wire value))) StandardCharsets/UTF_8))]
      (apply str (map #(format "%02x" (bit-and 255 %)) bytes)))))

(def ^:private connection-keys
  ;; Never export connection strings, JDBC options, files, SSH settings, or arbitrary driver properties.
  #{:host :port :dbname :db :user :ssl :ssl-mode})

(defn connection-template
  "Only reviewed, scalar connection properties leave the source instance."
  [details]
  (into {} (filter (fn [[_ v]] (or (string? v) (number? v) (boolean? v))))
        (select-keys details connection-keys)))

(defn- short-string? [x] (and (string? x) (not (str/blank? x)) (<= (count x) 254)))
(defn- scalar? [x] (or (nil? x) (string? x) (number? x) (boolean? x)))
(defn- tuple! [x path] (check! (vector? x) "UNSUPPORTED_QUERY" path "Expected a query clause."))

(defn field-ref
  "Validate and rewrite only a field-reference slot. String fields are allowed only in display settings."
  [ref remap path allow-name?]
  (tuple! ref path)
  (let [[op id options] ref]
    (check! (and (= "field" op) (= 3 (count ref))
                 (or (pos-int? id) (and allow-name? (string? id))))
            "UNSUPPORTED_QUERY" path "Only physical field references are supported here.")
    (when (some? options)
      (closed! options #{:base-type :temporal-unit} path)
      (check! (or (nil? (:base-type options)) (string? (:base-type options)))
              "INVALID_FORMAT" path "Invalid field base type.")
      (when-let [unit (:temporal-unit options)]
        (check! (contains? #{"default" "minute" "hour" "day" "week" "month" "quarter" "year"
                             "minute-of-hour" "hour-of-day" "day-of-week" "day-of-month" "day-of-year"
                             "week-of-year" "month-of-year" "quarter-of-year"} unit)
                "UNSUPPORTED_QUERY" path "Unsupported temporal unit.")))
    [op (if (integer? id) (remap id path) id) options]))

(defn- literal!
  [value path]
  (check! (or (scalar? value)
              (and (vector? value) (= 3 (count value))
                   (contains? #{"relative-datetime" "absolute-datetime"} (first value))
                   (or (number? (second value)) (string? (second value)))
                   (contains? #{"minute" "hour" "day" "week" "month" "quarter" "year"} (last value))))
          "UNSUPPORTED_QUERY" path "Unsupported filter value.")
  value)

(defn- filter-clause
  [clause remap path]
  (tuple! clause path)
  (let [[op & args] clause]
    (cond
      (contains? #{"and" "or" "not"} op)
      (do (check! (if (= op "not") (= 1 (count args)) (<= 1 (count args)))
                  "INVALID_FORMAT" path "Invalid logical filter.")
          (into [op] (map-indexed #(filter-clause %2 remap (conj path (inc %1))) args)))

      (contains? #{"=" "!=" "<" ">" "<=" ">=" "between" "is-null" "not-null"} op)
      (let [[field & values] args
            valid-arity? (cond
                           (contains? #{"is-null" "not-null"} op) (empty? values)
                           (= "between" op) (= 2 (count values))
                           (contains? #{"=" "!="} op) (seq values)
                           :else (= 1 (count values)))]
        (check! valid-arity? "INVALID_FORMAT" path "Invalid filter arguments.")
        (into [op (field-ref field remap (conj path 1) false)]
              (map #(literal! % path) values)))

      :else (fail! "UNSUPPORTED_QUERY" path "Unsupported filter operator."))))

(defn- aggregation
  [clause remap path]
  (tuple! clause path)
  (let [[op field] clause]
    (check! (or (and (= "count" op) (= 1 (count clause)))
                (and (contains? #{"count" "sum" "avg" "min" "max" "distinct"} op) (= 2 (count clause))))
            "UNSUPPORTED_QUERY" path "Unsupported aggregation.")
    (if field [op (field-ref field remap (conj path 1) false)] [op])))

(defn query
  "Validate a legacy MBQL wire query and remap its database, table, and field slots. Never walks literals."
  [value database-map table-map field-map]
  (closed! value #{:database :type :query :native} [:dataset_query])
  (check! (pos-int? (:database value)) "INVALID_FORMAT" [:dataset_query :database] "Invalid database reference.")
  (let [value (update value :database database-map)]
    (case (:type value)
      "native"
      (let [{:keys [query template-tags] :as native} (:native value)]
        (check! (nil? (:query value)) "INVALID_FORMAT" [:dataset_query] "Mixed query types.")
        (closed! native #{:query :template-tags} [:dataset_query :native])
        (check! (and (string? query) (not (str/blank? query))) "INVALID_FORMAT" [:dataset_query :native :query] "SQL must not be empty.")
        ;; Saved-question and snippet expansion lives in SQL text, not only in template-tags.
        (check! (not (re-find #"(?i)\{\{\s*(?:#|snippet\s*:)" query))
                "UNSUPPORTED_DEPENDENCY" [:dataset_query :native :query] "Saved questions and snippets are not supported.")
        (check! (or (nil? template-tags) (map? template-tags)) "INVALID_FORMAT" [:dataset_query :native] "Invalid template tags.")
        (doseq [[k tag] template-tags]
          (closed! tag #{:id :name :display-name :type :default :required} [:dataset_query :native :template-tags k])
          (check! (and (contains? #{"text" "number" "date"} (:type tag))
                       (= (name k) (:name tag)) (scalar? (:default tag))
                       (or (nil? (:required tag)) (boolean? (:required tag))))
                  "UNSUPPORTED_PARAMETER" [:dataset_query :native :template-tags k] "Only scalar SQL template tags are supported."))
        value)

      "query"
      (let [q (:query value)
            path [:dataset_query :query]]
        (check! (nil? (:native value)) "INVALID_FORMAT" [:dataset_query] "Mixed query types.")
        (closed! q #{:source-table :fields :filter :aggregation :breakout :order-by :limit} path)
        (check! (pos-int? (:source-table q)) "UNSUPPORTED_DEPENDENCY" (conj path :source-table) "A single physical source table is required.")
        (doseq [k [:fields :aggregation :breakout :order-by] :when (contains? q k)]
          (check! (vector? (k q)) "INVALID_FORMAT" (conj path k) "Expected an array."))
        (when (contains? q :limit)
          (check! (pos-int? (:limit q)) "INVALID_FORMAT" (conj path :limit) "Limit must be positive."))
        (assoc value :query
               (cond-> (update q :source-table table-map)
                 (:fields q) (update :fields #(mapv (fn [f] (field-ref f field-map (conj path :fields) false)) %))
                 (:breakout q) (update :breakout #(mapv (fn [f] (field-ref f field-map (conj path :breakout) false)) %))
                 (:filter q) (update :filter filter-clause field-map (conj path :filter))
                 (:aggregation q) (update :aggregation #(mapv (fn [a] (aggregation a field-map (conj path :aggregation))) %))
                 (:order-by q)
                 (update :order-by
                         #(mapv (fn [[direction ref :as order]]
                                  (check! (and (= 2 (count order)) (contains? #{"asc" "desc"} direction))
                                          "UNSUPPORTED_QUERY" (conj path :order-by) "Unsupported ordering.")
                                  (tuple! ref (conj path :order-by))
                                  [direction
                                   (if (= "aggregation" (first ref))
                                     (do (check! (and (= 2 (count ref)) (nat-int? (second ref))
                                                      (< (second ref) (count (:aggregation q))))
                                                 "INVALID_FORMAT" path "Invalid aggregation index.") ref)
                                     (field-ref ref field-map (conj path :order-by) false))]) %)))))
      (fail! "UNSUPPORTED_QUERY" [:dataset_query :type] "Unsupported query type."))))

(def ^:private simple-style-keys
  #{:graph.dimensions :graph.metrics :graph.colors :graph.show_values :graph.show_goal :graph.goal_value
    :graph.goal_label :graph.show_trendline :graph.x_axis.title_text :graph.y_axis.title_text
    :graph.x_axis.axis_enabled :graph.y_axis.axis_enabled :graph.x_axis.labels_enabled :graph.y_axis.labels_enabled
    :graph.x_axis.scale :graph.y_axis.scale :graph.label_value_formatting :graph.show_legend
    :line.interpolate :line.marker_enabled :line.missing :stackable.stack_type
    :table.pivot :table.cell_column :table.pivot_column :table.row_index :table.column_widths
    :scalar.field :scalar.scale :scalar.decimals :scalar.separator_style :scalar.prefix :scalar.suffix})

(def ^:private column-style-keys
  #{:column_title :number_style :currency :currency_style :decimals :scale :prefix :suffix :separator_style
    :date_style :date_separator :date_abbreviate :time_style :time_enabled :show_mini_bar :text_wrapping})

(defn- display-ref
  [ref remap path]
  (if (and (vector? ref) (= "aggregation" (first ref)))
    (do (check! (and (= 2 (count ref)) (nat-int? (second ref)))
                "INVALID_FORMAT" path "Invalid display aggregation reference.")
        ref)
    (field-ref ref remap path true)))

(defn visualization
  "Rewrite field references in supported display settings, including JSON-encoded column keys."
  [settings remap]
  (closed! settings (into simple-style-keys #{:table.columns :column_settings}) [:visualization_settings])
  (doseq [[k v] (select-keys settings simple-style-keys)]
    (check! (or (scalar? v) (and (vector? v) (every? scalar? v)))
            "UNSUPPORTED_OPTION" [:visualization_settings k] "Unsupported display setting value."))
  (cond-> settings
    (contains? settings :table.columns)
    (update :table.columns
            (fn [columns]
              (check! (vector? columns) "INVALID_FORMAT" [:visualization_settings :table.columns] "Expected columns.")
              (mapv (fn [column]
                      (closed! column #{:name :fieldRef :enabled} [:visualization_settings :table.columns])
                      (check! (and (or (nil? (:name column)) (string? (:name column)))
                                   (or (nil? (:enabled column)) (boolean? (:enabled column))))
                              "INVALID_FORMAT" [:visualization_settings :table.columns] "Invalid column setting.")
                      (cond-> column
                        (:fieldRef column) (update :fieldRef display-ref remap [:visualization_settings :table.columns]))) columns)))
    (contains? settings :column_settings)
    (update :column_settings
            (fn [columns]
              (check! (map? columns) "INVALID_FORMAT" [:visualization_settings :column_settings] "Expected column settings.")
              (into {} (for [[k style] columns
                             :let [ref (try (json/decode (name k))
                                            (catch Exception _ (fail! "INVALID_FORMAT" [:visualization_settings :column_settings] "Invalid column key.")))]]
                         (do
                           (closed! style column-style-keys [:visualization_settings :column_settings])
                           (check! (every? scalar? (vals style)) "UNSUPPORTED_OPTION" [:visualization_settings :column_settings] "Unsupported formatting value.")
                           (check! (and (vector? ref) (= 2 (count ref))
                                        (or (and (= "name" (first ref)) (string? (second ref)))
                                            (= "ref" (first ref))))
                                   "UNSUPPORTED_OPTION" [:visualization_settings :column_settings] "Unsupported column key.")
                           [(keyword (json/encode (if (= "ref" (first ref))
                                                    ["ref" (display-ref (second ref) remap [:visualization_settings :column_settings])] ref)))
                            style])))))))

(def card-keys "Managed definition fields; runtime and access configuration are never transferred."
  #{:name :description :dataset_query :display :visualization_settings :parameters})

(defn card
  "Validate a portable card and rewrite its references."
  [value database-map table-map field-map]
  (closed! value (conj card-keys :entity_id :dashboard_entity_id) [:card])
  (check! (and (string? (:entity_id value)) (re-matches #"[A-Za-z0-9_-]{21}" (:entity_id value)) (short-string? (:name value))
               (or (nil? (:description value)) (string? (:description value)))
               (displays (:display value))) "UNSUPPORTED_CARD" [:card] "Invalid identity, name, or unsupported display type.")
  (check! (vector? (:parameters value)) "INVALID_FORMAT" [:parameters] "Expected parameters.")
  (let [q (query (:dataset_query value) database-map table-map field-map)]
    (doseq [p (:parameters value)]
      (closed! p #{:id :type :name :slug :target :default} [:parameters])
      (let [target (:target p)
            kind (when (vector? target) (first target))
            tag-ref (when (vector? target) (second target))
            tag-name (when (vector? tag-ref) (second tag-ref))]
        (check! (and (string? tag-name) (= "native" (:type q)) (= "variable" kind)
                     (= 2 (count target)) (= ["template-tag" tag-name] (second target))
                     (contains? #{"category" "number/=" "date/single"} (:type p))
                     (get-in q [:native :template-tags (keyword tag-name)]) (scalar? (:default p)))
                "UNSUPPORTED_PARAMETER" [:parameters] "Only scalar SQL parameters are supported.")))
    (assoc value :dataset_query q :visualization_settings (visualization (:visualization_settings value) field-map))))

(defn bundle!
  "Validate structural integrity before resolving any references or touching a target database."
  [bundle version]
  (check! (<= (alength (.getBytes (json/encode bundle) StandardCharsets/UTF_8)) max-bytes)
          "FILE_TOO_LARGE" [] "Bundle exceeds 10 MiB.")
  (closed! bundle #{:format :format_version :producer :databases :tables :fields :cards :dashboards} [])
  (check! (and (= "metabase-asset-transfer" (:format bundle)) (contains? #{1 2} (:format_version bundle))
               (= {:metabase_version version :transfer_version (:format_version bundle)} (:producer bundle)))
          "VERSION_MISMATCH" [:producer] "Export and import must use the same Metabase and transfer version.")
  (check! (if (= 1 (:format_version bundle))
            (and (not (contains? bundle :dashboards)) (not-any? :dashboard_entity_id (:cards bundle)))
            (and (vector? (:dashboards bundle)) (<= (count (:dashboards bundle)) 20)))
          "INVALID_FORMAT" [:dashboards] "V2 supports at most 20 dashboards; V1 cannot contain dashboards.")
  (doseq [k (cond-> [:databases :tables :fields :cards] (= 2 (:format_version bundle)) (conj :dashboards))]
    (check! (vector? (k bundle)) "INVALID_FORMAT" [k] "Expected an array.")
    (let [ids (map (if (contains? #{:cards :dashboards} k) :entity_id :id) (k bundle))]
      (check! (= (count ids) (count (distinct ids))) "DUPLICATE_REFERENCE" [k] "Duplicate object reference.")))
  (check! (<= (count (:cards bundle)) max-cards) "TOO_MANY_CARDS" [:cards] "At most 100 cards can be imported.")
  (check! (<= (count (:databases bundle)) 100) "INVALID_FORMAT" [:databases] "Too many databases.")
  (doseq [db (:databases bundle)]
    (closed! db #{:id :name :engine :details} [:databases])
    (check! (and (pos-int? (:id db)) (short-string? (:name db)) (engines (:engine db)))
            "UNSUPPORTED_DATABASE" [:databases] "Unsupported database template.")
    (closed! (:details db) connection-keys [:databases :details])
    (check! (= (:details db) (connection-template (:details db))) "INVALID_FORMAT" [:databases :details] "Invalid connection template."))
  (doseq [table (:tables bundle)]
    (closed! table #{:id :database_id :schema :name} [:tables])
    (check! (and (pos-int? (:id table)) (pos-int? (:database_id table)) (string? (:name table))
                 (or (nil? (:schema table)) (string? (:schema table)))) "INVALID_FORMAT" [:tables] "Invalid table reference."))
  (doseq [field (:fields bundle)]
    (closed! field #{:id :table_id :name :base_type :effective_type} [:fields])
    (check! (and (pos-int? (:id field)) (pos-int? (:table_id field)) (string? (:name field))
                 (string? (:base_type field)) (or (nil? (:effective_type field)) (string? (:effective_type field))))
            "INVALID_FORMAT" [:fields] "Invalid field reference."))
  bundle)
