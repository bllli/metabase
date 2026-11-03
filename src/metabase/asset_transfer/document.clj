(ns metabase.asset-transfer.document
  "The public AssetSet schema and conversion to validated internal references."
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [metabase.asset-transfer.dashboard :as dashboard]
   [metabase.asset-transfer.schema :as s]))

(def api-version "Supported asset file version." "silinex.metabase/v1")

(defn key!
  "Validate a stable, namespaced resource key."
  [key]
  (s/check! (and (string? key) (re-matches #"[A-Za-z0-9_-]{1,100}" key))
            "INVALID_KEY" [:key] "Keys must contain 1–100 letters, digits, underscores or hyphens.")
  key)

(defn identity-for
  "Produce a bounded transfer identity from a document namespace and resource key."
  [namespace kind key]
  (subs (s/digest [namespace kind key]) 0 21))

(defn- reference! [mapping key]
  (or (get mapping key) (s/fail! "INVALID_REFERENCE" [] "A resource reference is missing.")))

(defn- resources! [resources limit]
  (s/check! (and (vector? resources) (<= (count resources) limit))
            "INVALID_FORMAT" [:spec] "Invalid resource list or resource limit exceeded.")
  (doseq [resource resources] (key! (:key resource)))
  (s/check! (= (count resources) (count (distinct (map :key resources))))
            "DUPLICATE_REFERENCE" [:spec] "Duplicate resource key."))

(defn- secret! [value]
  (s/closed! value #{:valueFrom} [:details :password])
  (let [ref (:valueFrom value)]
    (s/closed! ref #{:env :file} [:details :password :valueFrom])
    (s/check! (and (= 1 (count ref)) (string? (first (vals ref)))
                   (not (str/blank? (first (vals ref)))))
              "INVALID_SECRET" [:details :password] "Use one nonempty environment variable or mounted file reference.")))

(defn validate!
  "Validate the complete public document and return its internal bundle, without accessing a database."
  [document]
  (s/closed! document #{:apiVersion :kind :metadata :spec} [])
  (s/check! (and (= api-version (:apiVersion document)) (= "AssetSet" (:kind document)))
            "VERSION_MISMATCH" [] "Expected silinex.metabase/v1 AssetSet.")
  (s/closed! (:metadata document) #{:key} [:metadata])
  (let [namespace (key! (get-in document [:metadata :key]))
        {:keys [databases collections questions dashboards tables fields] :as spec} (:spec document)]
    (s/closed! spec #{:databases :collections :questions :dashboards :tables :fields} [:spec])
    (doseq [[items limit] [[databases 100] [collections 1] [questions 100] [dashboards 20]]]
      (resources! items limit))
    (s/check! (= 1 (count collections)) "INVALID_COLLECTION" [:collections] "Provide one ordinary destination collection.")
    (doseq [collection collections]
      (s/closed! collection #{:key :name} [:collections])
      (s/check! (and (string? (:name collection)) (not (str/blank? (:name collection))) (<= (count (:name collection)) 254))
                "INVALID_COLLECTION" [:collections] "Provide a collection name."))
    (doseq [database databases]
      (s/closed! database #{:key :name :engine :details} [:databases])
      (s/closed! (:details database) #{:host :port :dbname :db :user :ssl :ssl-mode :password} [:details])
      (when (contains? (:details database) :password) (secret! (get-in database [:details :password]))))
    (doseq [question questions]
      (s/closed! question (conj s/card-keys :key :dashboard_entity_id) [:questions]))
    (doseq [dash dashboards]
      (s/closed! dash #{:key :name :description :parameters :width :auto_apply_filters :tabs :dashcards} [:dashboards]))
    (let [db-ids (zipmap (map :key databases) (range 1 (inc (count databases))))
          card-ids (into {} (map (fn [{:keys [key]}] [key (identity-for namespace "card" key)]) questions))
          dash-ids (into {} (map (fn [{:keys [key]}] [key (identity-for namespace "dashboard" key)]) dashboards))
          cards (mapv (fn [question]
                        (cond-> (-> question (set/rename-keys {:key :entity_id})
                                    (assoc :description (:description question))
                                    (update :entity_id #(reference! card-ids %))
                                    (update-in [:dataset_query :database] #(reference! db-ids %)))
                          (= "native" (get-in question [:dataset_query :type]))
                          (update-in [:dataset_query :native :template-tags] #(or % {}))
                          (:dashboard_entity_id question)
                          (update :dashboard_entity_id #(reference! dash-ids %)))) questions)
          bundle {:databases (mapv #(-> % (dissoc :key) (assoc :id (db-ids (:key %)))
                                        (update :details dissoc :password)) databases)
                  :tables (mapv #(update % :database_id (partial reference! db-ids)) tables)
                  :fields fields :cards cards
                  :dashboards (mapv (fn [dash]
                                      (-> dash (set/rename-keys {:key :entity_id})
                                          (update :entity_id #(reference! dash-ids %))
                                          (update :dashcards
                                                  (fn [tiles]
                                                    (mapv (fn [tile]
                                                            (-> tile
                                                                (update :card_id #(when % (reference! card-ids %)))
                                                                (update :series #(mapv (partial reference! card-ids) %))
                                                                (update :parameter_mappings
                                                                        #(mapv (fn [mapping] (update mapping :card_id (partial reference! card-ids))) %))))
                                                          tiles))))) dashboards)}]
      (s/check! (and (vector? tables) (vector? fields)) "INVALID_FORMAT" [:spec] "Provide table and field descriptor arrays.")
      (s/bundle! bundle)
      (let [dbs (set (map :id (:databases bundle)))
            table-map (into {} (map (juxt :id identity) (:tables bundle)))
            field-map (into {} (map (juxt :id identity) fields))
            cards-by-id (into {} (map (juxt :entity_id identity) cards))]
        (doseq [table (:tables bundle)] (reference! dbs (:database_id table)))
        (doseq [field fields] (reference! table-map (:table_id field)))
        (doseq [card cards]
          (let [database-id (get-in card [:dataset_query :database])
                table-id (get-in card [:dataset_query :query :source-table])
                table-ref (fn [id]
                            (s/check! (= database-id (:database_id (reference! table-map id)))
                                      "INVALID_REFERENCE" [:tables] "Table belongs to another database.") id)]
            (s/card card #(reference! dbs %) table-ref
                    (fn [id _]
                      (let [field (reference! field-map id)]
                        (table-ref (:table_id field))
                        (s/check! (or (nil? table-id) (= table-id (:table_id field)))
                                  "INVALID_REFERENCE" [:fields] "Field belongs to another table.") id)))))
        (doseq [dash (:dashboards bundle)]
          (dashboard/rewrite dash cards-by-id identity identity
                             (fn [id _] (:id (reference! field-map id))) field-map))
        (doseq [card cards :when (:dashboard_entity_id card)]
          (let [owner (some #(when (= (:entity_id %) (:dashboard_entity_id card)) %) (:dashboards bundle))]
            (s/check! (some #{(:entity_id card)} (dashboard/referenced-cards owner))
                      "INVALID_REFERENCE" [:questions] "An owned question must appear in its dashboard."))))
      bundle)))
