(ns metabase.asset-transfer.dashboard
  "Portable dashboard layouts and exact reference visitors. No warehouse queries."
  (:require
   [metabase.api.common :as api]
   [metabase.asset-transfer.db :as db]
   [metabase.asset-transfer.schema :as s]
   [metabase.dashboards.models.dashboard-card :as dashboard-card]))

(def ^:private dashboard-keys #{:name :description :parameters :width :auto_apply_filters})
(def ^:private dashcard-keys #{:card_id :dashboard_tab_id :row :col :size_x :size_y
                               :parameter_mappings :visualization_settings :inline_parameters})

(defn definition
  "Read managed content, excluding generated identities, access settings, and subscription configuration."
  [id]
  (s/wire
   (assoc (select-keys (db/dashboard id) dashboard-keys)
          :tabs (mapv #(select-keys % [:id :name :position]) (db/dashboard-tabs id))
          :dashcards (mapv #(assoc (select-keys % dashcard-keys)
                                   :series (mapv :id (:series %))
                                   :action_id (:action_id %))
                           (db/dashcards id)))))

(defn referenced-cards
  "All main and additional series question IDs referenced by a layout."
  [dashboard]
  (distinct (mapcat #(cond-> (:series %) (:card_id %) (conj (:card_id %))) (:dashcards dashboard))))

(defn- resolve! [references id path]
  (let [value (get references id)]
    (s/check! value "INVALID_REFERENCE" path "A referenced dashboard, tab, or question is missing.")
    value))

(defn- identity! [id]
  (s/check! (and (string? id) (re-matches #"[A-Za-z0-9_-]{21}" id))
            "INVALID_FORMAT" [:dashboards] "Invalid entity identity."))

(defn- parameters! [parameters]
  (s/check! (and (vector? parameters) (<= (count parameters) 100))
            "UNSUPPORTED_PARAMETER" [:dashboards :parameters] "Expected at most 100 filters.")
  (let [ids (mapv :id parameters)]
    (s/check! (= (count ids) (count (distinct ids))) "DUPLICATE_REFERENCE" [:parameters] "Duplicate filter ID.")
    (doseq [parameter parameters]
      (s/closed! parameter #{:id :name :slug :type :default :required :isMultiSelect :sectionId
                             :filteringParameters :values_query_type} [:dashboards :parameters])
      (s/check! (and (every? #(and (string? %) (seq %)) ((juxt :id :name :slug) parameter))
                     (contains? #{"category" "id" "string/=" "string/!=" "string/contains" "string/does-not-contain"
                                  "string/starts-with" "string/ends-with" "number/=" "number/!=" "number/>=" "number/<="
                                  "number/between" "date/single" "date/range" "date/relative" "date/all-options"
                                  "date/month-year" "date/quarter-year" "date/=" "date/!=" "date/<" "date/>" "date/between"}
                                (:type parameter))
                     (every? #(or (nil? %) (boolean? %)) ((juxt :required :isMultiSelect) parameter))
                     (every? #(or (nil? %) (string? %)) ((juxt :sectionId :values_query_type) parameter))
                     (every? #(or (nil? %) (string? %) (number? %) (boolean? %))
                             (let [value (:default parameter)] (if (vector? value) value [value])))
                     (or (nil? (:filteringParameters parameter))
                         (and (vector? (:filteringParameters parameter))
                              (every? (set ids) (:filteringParameters parameter)))))
                "UNSUPPORTED_PARAMETER" [:dashboards :parameters] "Unsupported dashboard filter configuration."))))

(defn- visualization [settings field-map virtual?]
  (s/check! (map? settings) "INVALID_FORMAT" [:dashcards :visualization_settings] "Expected display settings.")
  (let [extras (select-keys settings [:dashcard.background :card.title :card.description :text :virtual_card])
        virtual (:virtual_card extras)]
    (s/check! (and (or (nil? (:dashcard.background extras)) (boolean? (:dashcard.background extras)))
                   (every? #(or (nil? %) (string? %)) ((juxt :card.title :card.description :text) extras)))
              "UNSUPPORTED_OPTION" [:dashcards :visualization_settings] "Unsupported display setting.")
    (if virtual?
      (do
        (s/closed! virtual #{:name :display :visualization_settings :archived} [:dashcards :virtual_card])
        (s/check! (and (contains? #{"text" "heading"} (:display virtual))
                       (empty? (:visualization_settings virtual)) (not (:archived virtual))
                       (or (nil? (:name virtual)) (string? (:name virtual))))
                  "UNSUPPORTED_DASHCARD" [:dashcards] "Only text and heading virtual cards are supported."))
      (s/check! (and (nil? virtual) (not (contains? extras :text)))
                "UNSUPPORTED_DASHCARD" [:dashcards] "A question cannot also be a virtual card."))
    (merge (s/visualization (apply dissoc settings (keys extras)) field-map) extras)))

(defn- mapping
  [value cards allowed-cards parameter-ids card-map field-map fields]
  (s/closed! value #{:parameter_id :card_id :target} [:dashcards :parameter_mappings])
  (let [{:keys [parameter_id card_id target]} value
        card (resolve! cards card_id [:parameter_mappings :card_id])
        query (:dataset_query card)]
    (s/check! (and (contains? allowed-cards card_id) (contains? parameter-ids parameter_id)
                   (vector? target) (= 2 (count target)))
              "INVALID_REFERENCE" [:parameter_mappings] "Filter must reference its dashboard and a question on this tile.")
    (assoc value :card_id (card-map card_id)
           :target
           (case (first target)
             "dimension"
             (do
               (s/check! (= "query" (:type query)) "UNSUPPORTED_PARAMETER" [:parameter_mappings] "Expected a physical query.")
               ["dimension" (s/field-ref (second target)
                                         (fn [id path]
                                           (s/check! (= (get-in query [:query :source-table]) (:table_id (fields id)))
                                                     "INVALID_REFERENCE" path "Filter field must belong to its question's table.")
                                           (field-map id path)) [:parameter_mappings :target] false)])
             "variable"
             (let [tag (second target)
                   _ (s/check! (vector? tag) "INVALID_REFERENCE" [:parameter_mappings] "Expected a template tag reference.")
                   [kind tag-name] tag]
               (s/check! (and (= "native" (:type query)) (vector? tag) (= 2 (count tag))
                              (= "template-tag" kind) (string? tag-name)
                              (get-in query [:native :template-tags (keyword tag-name)]))
                         "INVALID_REFERENCE" [:parameter_mappings] "Unknown scalar SQL template tag.")
               target)
             (s/fail! "UNSUPPORTED_PARAMETER" [:parameter_mappings] "Unsupported filter target.")))))

(defn rewrite
  "Validate a portable dashboard and rewrite only declared card, tab, and physical-field slots."
  [dashboard cards card-map tab-map field-map fields]
  (s/closed! dashboard (conj dashboard-keys :entity_id :tabs :dashcards) [:dashboards])
  (identity! (:entity_id dashboard))
  (s/check! (and (string? (:name dashboard)) (<= 1 (count (:name dashboard)) 254)
                 (or (nil? (:description dashboard)) (string? (:description dashboard)))
                 (contains? #{"fixed" "full"} (:width dashboard)) (boolean? (:auto_apply_filters dashboard)))
            "INVALID_FORMAT" [:dashboards] "Invalid dashboard properties.")
  (parameters! (:parameters dashboard))
  (s/check! (and (vector? (:tabs dashboard)) (<= (count (:tabs dashboard)) 100)
                 (vector? (:dashcards dashboard)) (<= (count (:dashcards dashboard)) 200))
            "INVALID_FORMAT" [:dashboards] "At most 100 tabs and 200 tiles per dashboard are supported.")
  (let [tabs (into {} (map (juxt :id identity) (:tabs dashboard)))
        parameter-ids (set (map :id (:parameters dashboard)))]
    (s/check! (= (count tabs) (count (:tabs dashboard))) "DUPLICATE_REFERENCE" [:tabs] "Duplicate tab ID.")
    (doseq [tab (:tabs dashboard)]
      (s/closed! tab #{:id :name :position} [:tabs])
      (s/check! (and (pos-int? (:id tab)) (string? (:name tab)) (seq (:name tab)) (nat-int? (:position tab)))
                "INVALID_FORMAT" [:tabs] "Invalid dashboard tab."))
    (assoc dashboard :dashcards
           (mapv
            (fn [{:keys [card_id dashboard_tab_id series parameter_mappings inline_parameters] :as dc}]
              (s/closed! dc (conj dashcard-keys :series :action_id) [:dashcards])
              (s/check! (and (nil? (:action_id dc)) (every? nat-int? ((juxt :row :col) dc))
                             (every? pos-int? ((juxt :size_x :size_y) dc)) (<= (+ (:col dc) (:size_x dc)) 24)
                             (vector? series) (= (count series) (count (distinct series)))
                             (vector? parameter_mappings) (vector? inline_parameters)
                             (every? parameter-ids inline_parameters))
                        "UNSUPPORTED_DASHCARD" [:dashcards] "Invalid layout, filter, or unsupported action tile.")
              (s/check! (if (seq tabs) (contains? tabs dashboard_tab_id) (nil? dashboard_tab_id))
                        "INVALID_REFERENCE" [:dashcards :dashboard_tab_id] "Tile must belong to a tab in its dashboard.")
              (let [ids (cond-> (set series) card_id (conj card_id))]
                (doseq [id ids]
                  (let [card (resolve! cards id [:dashcards :card_id])]
                    (s/check! (or (nil? (:dashboard_entity_id card))
                                  (= (:entity_id dashboard) (:dashboard_entity_id card)))
                              "INVALID_REFERENCE" [:dashcards] "Question belongs to another dashboard.")))
                (s/check! (or card_id (and (empty? ids) (empty? parameter_mappings) (empty? inline_parameters)))
                          "UNSUPPORTED_DASHCARD" [:dashcards] "Text tiles cannot have question series or filters.")
                (-> dc
                    (assoc :card_id (when card_id (card-map card_id))
                           :dashboard_tab_id (when dashboard_tab_id (tab-map dashboard_tab_id))
                           :series (mapv card-map series)
                           :visualization_settings (visualization (:visualization_settings dc) field-map (nil? card_id))
                           :parameter_mappings (mapv #(mapping % cards ids parameter-ids card-map field-map fields) parameter_mappings)))))
            (:dashcards dashboard)))))

(defn export-definition
  "Export one dashboard; query ownership and references are checked once the full dependency set is available."
  [id]
  (let [dashboard (api/read-check :model/Dashboard id)]
    (s/check! (not (:archived dashboard)) "UNSUPPORTED_DASHBOARD" [:dashboards] "Archived dashboards cannot be exported.")
    (assoc (definition id) :entity_id (:entity_id dashboard))))

(defn plan
  "Detect source/binding changes and target layout edits before creating any objects."
  [dashboard collection-id databases]
  (let [binding (db/dashboard-binding (:entity_id dashboard) collection-id)
        existing (when binding (db/dashboard (:dashboard_id binding)))
        source-hash (s/digest {:definition dashboard :databases databases})
        target-hash (when existing (s/digest (definition (:id existing))))
        unchanged? (and existing (= collection-id (:collection_id existing)) (not (:archived existing))
                        (= source-hash (:source_hash binding)) (= target-hash (:target_hash binding)))]
    {:model "dashboard" :source_entity_id (:entity_id dashboard) :name (:name dashboard)
     :action (cond (nil? binding) "create" unchanged? "skip" :else "conflict")
     :dashboard_id (:dashboard_id binding) :source_hash source-hash :target_hash target-hash :definition dashboard
     :error (when (and binding (not unchanged?)) {:code "ASSET_CHANGED" :message "The dashboard or its database bindings changed. Imports never overwrite."})}))

(defn create-shell!
  "Create a validated dashboard without publishing an event before the transaction commits."
  [definition collection-id]
  (api/create-check :model/Dashboard {:collection_id collection-id})
  (db/insert-dashboard! (assoc (select-keys definition dashboard-keys)
                               :collection_id collection-id :creator_id api/*current-user-id*)))

(defn finish!
  "Create tabs and layout after all referenced questions exist, and persist the final target hash."
  [item id collection-id cards card-map references]
  (let [definition (:definition item)
        tabs (into {} (for [tab (:tabs definition)]
                        [(:id tab) (db/insert-tab! (assoc (dissoc tab :id) :dashboard_id id))]))
        field-map (fn [field-id path] (:target_id (resolve! (:fields references) field-id path)))
        rewritten (rewrite definition cards card-map tabs field-map (:fields references))]
    ;; The convenience dashboard writer synchronizes filter values against warehouses. Transfer deliberately does not.
    (dashboard-card/create-dashboard-cards! (mapv #(dashboard-card/from-parsed-json (assoc % :dashboard_id id)) (:dashcards rewritten)))
    (db/insert-dashboard-binding! {:source_entity_id (:source_entity_id item) :collection_id collection-id
                                   :dashboard_id id :source_hash (:source_hash item) :target_hash (s/digest (metabase.asset-transfer.dashboard/definition id))})))
