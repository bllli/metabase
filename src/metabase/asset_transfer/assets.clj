(ns metabase.asset-transfer.assets
  "Shared YAML export, preview and execution for interactive and startup imports."
  (:require
   [clojure.set :as set]
   [metabase.api.common :as api]
   [metabase.app-db.cluster-lock :as cluster-lock]
   [metabase.asset-transfer.dashboard :as dashboard]
   [metabase.asset-transfer.db :as db]
   [metabase.asset-transfer.document :as document]
   [metabase.asset-transfer.resources :as resources]
   [metabase.asset-transfer.schema :as s]
   [metabase.asset-transfer.transfer :as transfer]
   [metabase.asset-transfer.yaml :as yaml]
   [metabase.system.core :as system]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(defn inspect
  "Parse and validate a YAML file without writes or source database access."
  [text]
  (api/check-superuser)
  (let [doc (yaml/parse text)] (document/validate! doc) doc))

(defn- export-identity [namespace kind object fallback]
  (or (:resource_key (first (filter #(= namespace (:namespace %)) (db/target-bindings kind (:id object))))) fallback))

(defn- canonical-references [bundle db-keys]
  (let [tables (sort-by (juxt (comp db-keys :database_id) #(or (:schema %) "") :name) (:tables bundle))
        table-ids (zipmap (map :id tables) (range 1 (inc (count tables))))
        fields (sort-by (juxt (comp table-ids :table_id) :name) (:fields bundle))
        field-ids (zipmap (map :id fields) (range 1 (inc (count fields))))
        field-map (into {} (map (juxt :id identity) (:fields bundle)))
        cards (into {} (map (juxt :entity_id identity) (:cards bundle)))]
    (-> bundle
        (assoc :tables (mapv #(-> % (update :id table-ids)) tables)
               :fields (mapv #(-> % (update :id field-ids) (update :table_id table-ids)) fields))
        (update :cards #(mapv (fn [card] (s/card card identity table-ids (fn [id _] (field-ids id)))) %))
        (update :dashboards
                #(mapv (fn [dash]
                         (let [tabs (:tabs dash)
                               tab-ids (zipmap (map :id tabs) (range 1 (inc (count tabs))))]
                           (-> (dashboard/rewrite dash cards identity identity (fn [id _] (field-ids id)) field-map)
                               (update :tabs (fn [items] (mapv (fn [tab] (update tab :id tab-ids)) items)))
                               (update :dashcards (fn [items] (mapv (fn [tile] (update tile :dashboard_tab_id tab-ids)) items)))))) %)))))

(defn- export-secret [namespace database key]
  (if-let [binding (first (filter #(= namespace (:namespace %)) (db/target-bindings "database" (:id database))))]
    (:secret_reference binding)
    {:valueFrom {:env (str "MB_ASSET_PASSWORD_" (.toUpperCase ^String (subs (s/digest [namespace key]) 0 16) java.util.Locale/ROOT))}}))

(defn export-yaml
  "Export selected assets and their dependency templates into one YAML document."
  [selection]
  (let [bundle (transfer/export-bundle selection)
        objects (concat (map #(vector "card" (db/card %)) (:card_ids selection))
                        (map #(vector "dashboard" (db/dashboard %)) (:dashboard_ids selection))
                        (map #(vector "database" %) (:databases bundle)))
        namespaces (set (mapcat (fn [[kind object]] (map :namespace (db/target-bindings kind (:id object)))) objects))
        _ (s/check! (<= (count namespaces) 1) "MIXED_NAMESPACES" [] "Export assets from one YAML namespace at a time.")
        namespace (or (first namespaces) (str "instance-" (system/site-uuid)))
        db-keys (into {} (map (fn [database] [(:id database) (export-identity namespace "database" database (str "database-" (:id database)))]) (:databases bundle)))
        bundle (canonical-references bundle db-keys)
        ;; Entity IDs are stable across renames. Imported resources use the original YAML keys.
        card-keys (into {} (map (fn [card]
                                  (let [object (db/card-by-entity (:entity_id card))]
                                    [(:entity_id card) (export-identity namespace "card" object (:entity_id card))])) (:cards bundle)))
        dash-keys (into {} (map (fn [dash]
                                  (let [object (db/dashboard-by-entity (:entity_id dash))]
                                    [(:entity_id dash) (export-identity namespace "dashboard" object (:entity_id dash))])) (:dashboards bundle)))
        collection-ids (set (keep (fn [[kind object]] (when (contains? #{"card" "dashboard"} kind) (:collection_id object))) objects))
        collection (when (= 1 (count collection-ids)) (db/collection (first collection-ids)))
        doc {:apiVersion document/api-version :kind "AssetSet" :metadata {:key namespace}
             :spec {:databases (mapv (fn [database]
                                       (let [key (db-keys (:id database))
                                             secret (export-secret namespace database key)]
                                         (cond-> (-> database (dissoc :id) (assoc :key key))
                                           secret (assoc-in [:details :password] secret))))
                                     (:databases bundle))
                    :collections [{:key (export-identity namespace "collection" collection
                                                         (if collection (str "collection-" (:id collection)) "collection"))
                                   :name (or (:name collection) "Imported assets")}]
                    :tables (mapv #(update % :database_id db-keys) (:tables bundle))
                    :fields (:fields bundle)
                    :questions (mapv (fn [card]
                                       (cond-> (-> card (set/rename-keys {:entity_id :key})
                                                   (update :key card-keys) (update-in [:dataset_query :database] db-keys))
                                         (:dashboard_entity_id card) (update :dashboard_entity_id dash-keys))) (:cards bundle))
                    :dashboards (mapv (fn [dash]
                                        (-> dash (set/rename-keys {:entity_id :key}) (update :key dash-keys)
                                            (update :dashcards
                                                    #(mapv (fn [tile]
                                                             (-> tile (update :card_id card-keys) (update :series (partial mapv card-keys))
                                                                 (update :parameter_mappings (fn [mappings] (mapv (fn [m] (update m :card_id card-keys)) mappings))))) %))))
                                      (:dashboards bundle))}}]
    (document/validate! doc)
    {:yaml (yaml/generate doc)}))

(defn- compiled-request [bundle plans]
  {:bundle bundle
   :database_bindings (into {} (map-indexed (fn [index item] [(keyword (str (inc index))) (:target_id item)]) (filter #(= "database" (:model %)) plans)))
   :target_collection_id (:target_id (first (filter #(= "collection" (:model %)) plans)))})

(defn- public-resource [plan]
  (dissoc plan :source_hash :target_hash))

(defn- plan* [{:keys [yaml database_bindings target_collection_id] :as request}]
  (s/closed! request #{:yaml :database_bindings :target_collection_id :plan_hash :request_id} [])
  (let [doc (inspect yaml)
        bundle (document/validate! doc)
        namespace (get-in doc [:metadata :key])
        databases (get-in doc [:spec :databases])
        bindings (s/wire (or database_bindings {}))
        _ (s/check! (and (map? bindings) (every? (set (map (comp keyword :key) databases)) (keys bindings))
                         (every? pos-int? (vals bindings)) (or (nil? target_collection_id) (pos-int? target_collection_id)))
                    "INVALID_BINDINGS" [] "Bindings must reference declared resources and positive target IDs.")
        plans (conj (mapv #(resources/plan-resource namespace "database" % (get bindings (keyword (:key %)))) databases)
                    (resources/plan-resource namespace "collection" (first (get-in doc [:spec :collections])) target_collection_id))
        all-present? (every? #(contains? #{"skip" "bind"} (:action %)) plans)
        asset-plan (when all-present? (transfer/preview (compiled-request bundle plans)))
        incomplete? (some #(and (= "database" (:model %)) (= "skip" (:action %))
                                (not= "complete" (some-> (db/database (:target_id %)) :initial_sync_status name))) plans)
        needs-sync? (or incomplete? (not all-present?) (some #(= "METADATA_NOT_FOUND" (:code %)) (:errors asset-plan)))
        asset-items (if (and asset-plan (not needs-sync?)) (:items asset-plan)
                        (into (mapv #(hash-map :model "card" :source_entity_id (:entity_id %) :name (:name %) :action "create") (:cards bundle))
                              (mapv #(hash-map :model "dashboard" :source_entity_id (:entity_id %) :name (:name %) :action "create") (:dashboards bundle))))
        valid? (and (not-any? #(= "conflict" (:action %)) plans) (or needs-sync? (:can_import asset-plan)))
        items (into (mapv public-resource plans) asset-items)]
    {:document doc :bundle bundle :resources plans
     :public {:can_import (boolean valid?) :requires_sync (boolean needs-sync?) :items items
              :errors (when-not needs-sync? (:errors asset-plan))
              :plan_hash (s/digest {:document doc :bindings bindings :collection target_collection_id
                                    :resources plans :assets asset-plan})}}))

(defn preview
  "Plan resource creation and asset import without persistence or source queries."
  [request]
  (api/check-superuser)
  (try (:public (plan* request))
       (catch clojure.lang.ExceptionInfo e
         (if (= 400 (:status-code (ex-data e)))
           {:can_import false :items [] :errors [(merge {:message (ex-message e)} (select-keys (ex-data e) [:code :path]))]}
           (throw e)))))

(defn- save-asset-identities! [doc result]
  (let [namespace (get-in doc [:metadata :key])]
    (t2/with-transaction [_]
      (doseq [[kind definitions id-key] [["card" (get-in doc [:spec :questions]) :card_id]
                                         ["dashboard" (get-in doc [:spec :dashboards]) :dashboard_id]]
              definition definitions
              :let [identity (document/identity-for namespace kind (:key definition))
                    item (first (filter #(and (= kind (:model %)) (= identity (:source_entity_id %))) (:items result)))]]
        (when-not (db/resource-binding namespace kind (:key definition))
          (db/insert-resource-binding! {:namespace namespace :resource_type kind :resource_key (:key definition)
                                        :target_id (id-key item) :source_hash (s/digest definition) :target_hash (s/digest definition)}))))))

(defn- execute!* [request plan]
  (let [{doc :document bundle :bundle resource-plans :resources} plan
        namespace (get-in doc [:metadata :key])
        definitions (conj (get-in doc [:spec :databases]) (first (get-in doc [:spec :collections])))
        ready-plans (mapv (fn [definition item]
                            (assoc item :target_id (resources/ensure-resource! namespace (:model item) definition
                                                                               (when (= "bind" (:action item)) (:target_id item))))) definitions resource-plans)
        compiled (compiled-request bundle ready-plans)]
    (doseq [[definition item] (map vector (get-in doc [:spec :databases]) ready-plans)]
      (resources/deliver-database-event! namespace definition)
      (when (get-in plan [:public :requires_sync]) (resources/sync-metadata! (:target_id item))))
    (let [asset-plan (transfer/preview compiled)]
      (s/check! (:can_import asset-plan) "ASSET_PREFLIGHT_FAILED" [] "Asset references or existing definitions conflict after metadata sync. Run preflight for details.")
      (let [result (transfer/import-bundle! (assoc compiled :plan_hash (:plan_hash asset-plan) :request_id (:request_id request)))]
        (save-asset-identities! doc result)
        result))))

(defn apply!
  "Execute an approved YAML plan. Earlier resource stages survive failure and converge on the next preflight."
  [request]
  (api/check-superuser)
  (s/check! (and (string? (:request_id request)) (re-matches #"[A-Za-z0-9-]{16,64}" (:request_id request)))
            "INVALID_REQUEST_ID" [] "Provide a stable request identifier.")
  ;; A detached lock serializes a namespace without keeping resource writes or network calls in one transaction.
  (let [doc (inspect (:yaml request))]
    (cluster-lock/with-detached-cluster-lock {:lock (keyword "yaml-assets" (get-in doc [:metadata :key]))
                                              :timeout-seconds 5 :retry-config {:max-retries 60 :delay-ms 1000}}
      (let [plan (plan* request)]
        (s/check! (get-in plan [:public :can_import]) "IMPORT_CONFLICT" [] "Resolve preflight errors before importing.")
        (api/check (or (= (:plan_hash request) (get-in plan [:public :plan_hash]))
                       (and (db/import-record (:request_id request))
                            (every? #(= "skip" (:action %)) (get-in plan [:public :items]))))
                   [409 "The plan changed. Run preflight again."])
        (execute!* request plan)))))

(defn provision!
  "Reconcile one document at startup, inspecting live state on every execution."
  [text]
  (api/check-superuser)
  (let [doc (inspect text)]
    (cluster-lock/with-detached-cluster-lock {:lock (keyword "yaml-assets" (get-in doc [:metadata :key]))
                                              :timeout-seconds 5 :retry-config {:max-retries 60 :delay-ms 1000}}
      (let [request {:yaml text :request_id (s/digest doc)}
            plan (plan* request)]
        (s/check! (get-in plan [:public :can_import]) "IMPORT_CONFLICT" [] "Startup assets conflict with the current configuration.")
        (execute!* request plan)))))
