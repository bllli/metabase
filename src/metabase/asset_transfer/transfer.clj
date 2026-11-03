(ns metabase.asset-transfer.transfer
  "Independent OSS transfer of a bounded set of question definitions."
  (:require
   [metabase.api.common :as api]
   [metabase.asset-transfer.dashboard :as dashboard]
   [metabase.asset-transfer.db :as db]
   [metabase.asset-transfer.schema :as s]
   [metabase.events.core :as events]
   [metabase.lib-be.core :as lib-be]
   [metabase.lib.core :as lib]
   [metabase.queries.core :as queries]
   [toucan2.core :as t2]))

(defn- present! [object path]
  (s/check! object "MISSING_OBJECT" path "A referenced object is missing or inactive.")
  object)

(defn- card-definition
  [card]
  (-> card
      (select-keys s/card-keys)
      (assoc :description (:description card) :parameters (vec (or (:parameters card) []))
             :visualization_settings (or (:visualization_settings card) {}))
      ;; The v1 wire protocol deliberately pins the legacy shape; conversion never rewrites SQL.
      #_{:clj-kondo/ignore [:discouraged-var]}
      (update :dataset_query lib/->legacy-MBQL)
      s/wire))

(defn- exportable-card!
  [card dashboard-ids]
  (s/check! (and (= :question (:type card)) (not (:archived card))
                 (or (nil? (:dashboard_id card)) (contains? dashboard-ids (:dashboard_id card))) (nil? (:document_id card))
                 (empty? (:parameter_mappings card)))
            "UNSUPPORTED_CARD" [:card] "Select the owning dashboard for dashboard questions. Only unarchived questions without parameter mappings can be exported.")
  (cond-> (assoc (card-definition card) :entity_id (:entity_id card))
    (:dashboard_id card) (assoc :dashboard_entity_id (get dashboard-ids (:dashboard_id card)))))

(defn- table-descriptor [table]
  {:id (:id table) :database_id (:db_id table) :schema (:schema table) :name (:name table)})

(defn- field-descriptor [field]
  (s/check! (nil? (:parent_id field)) "UNSUPPORTED_FIELD" [:fields] "Nested fields are not supported.")
  (s/wire (select-keys field [:id :table_id :name :base_type :effective_type])))

(defn- checked-card
  "Every callback must resolve a reference, not return nil. Check cross-table references before rewriting."
  [card databases tables fields]
  (let [db-id (get-in card [:dataset_query :database])
        source-table (get-in card [:dataset_query :query :source-table])
        resolve-db #(present! (get databases %) [:dataset_query :database])
        resolve-table (fn [id]
                        (let [t (present! (get tables id) [:tables id])]
                          (s/check! (= db-id (:database_id t)) "INVALID_REFERENCE" [:tables id] "Table belongs to another database.")
                          (:target_id t)))
        resolve-field (fn [id path]
                        (let [f (present! (get fields id) path)
                              table-id (:table_id f)]
                          (resolve-table table-id)
                          (when source-table
                            (s/check! (= source-table table-id) "UNSUPPORTED_QUERY" path "Fields must belong to the source table."))
                          (:target_id f)))]
    (s/card card resolve-db resolve-table resolve-field)))

(defn export-bundle
  "Export selected questions and optional connection templates. Never exports warehouse rows."
  [{:keys [card_ids database_ids dashboard_ids] :or {dashboard_ids []}}]
  (api/check-superuser)
  (s/check! (and (vector? card_ids) (<= (count card_ids) s/max-cards) (every? pos-int? card_ids)
                 (vector? database_ids) (<= (count database_ids) 100) (every? pos-int? database_ids)
                 (vector? dashboard_ids) (<= (count dashboard_ids) 20) (every? pos-int? dashboard_ids)
                 (or (seq card_ids) (seq database_ids) (seq dashboard_ids)))
            "INVALID_SELECTION" [] "Select up to 20 dashboards and 100 questions or databases.")
  (let [dashboards (mapv dashboard/export-definition (distinct dashboard_ids))
        dashboard-identities (zipmap (distinct dashboard_ids) (map :entity_id dashboards))
        card-ids (vec (distinct (concat card_ids (mapcat dashboard/referenced-cards dashboards))))
        _ (s/check! (<= (count card-ids) s/max-cards) "TOO_MANY_CARDS" [:cards] "Dashboards reference more than 100 questions.")
        references (atom {:databases (set database_ids) :tables #{} :fields #{}})
        collect (fn [kind id] (swap! references update kind conj id) id)
        cards (mapv (fn [id]
                      (api/read-check :model/Card id)
                      (let [card (exportable-card! (present! (db/card id) [:cards id]) dashboard-identities)]
                        (try
                          (s/card card #(collect :databases %) #(collect :tables %)
                                  (fn [field-id _] (collect :fields field-id)))
                          (catch clojure.lang.ExceptionInfo e
                            (throw (ex-info (ex-message e) (assoc (ex-data e) :object_ref (:entity_id card))))))))
                    card-ids)
        card-identities (zipmap card-ids (map :entity_id cards))
        card-definitions (into {} (map (juxt :entity_id identity) cards))
        dashboards (mapv (fn [dash]
                           (let [portable (update dash :dashcards
                                                  (fn [dashcards]
                                                    (mapv (fn [dc]
                                                            (-> dc
                                                                (update :card_id card-identities)
                                                                (update :series (partial mapv card-identities))
                                                                (update :parameter_mappings
                                                                        (fn [mappings]
                                                                          (mapv #(update % :card_id card-identities) mappings)))))
                                                          dashcards)))]
                             (dashboard/rewrite portable card-definitions identity identity
                                                (fn [id _] (collect :fields id)) db/field))) dashboards)
        fields (mapv #(field-descriptor (present! (db/field %) [:fields %])) (sort (:fields @references)))
        table-ids (into (:tables @references) (map :table_id fields))
        tables (mapv #(table-descriptor (present! (db/table %) [:tables %])) (sort table-ids))
        databases (mapv (fn [id]
                          (api/read-check :model/Database id)
                          (let [database (present! (db/database id) [:databases id])]
                            {:id id :name (:name database) :engine (name (:engine database))
                             :details (s/connection-template (:details database))}))
                        (sort (:databases @references)))
        bundle {:databases databases :tables tables :fields fields :cards cards :dashboards dashboards}]
    (s/bundle! bundle)
    (doseq [card cards]
      (checked-card card (into {} (map (juxt :id :id) databases))
                    (into {} (map (fn [t] [(:id t) (assoc t :target_id (:id t))]) tables))
                    (into {} (map (fn [f] [(:id f) (assoc f :target_id (:id f))]) fields))))
    bundle))

(defn- one-match! [matches path]
  (s/check! (= 1 (count matches)) (if (empty? matches) "METADATA_NOT_FOUND" "AMBIGUOUS_METADATA")
            path "Expected one matching active table or field. Check schema names and finish metadata synchronization.")
  (first matches))

(defn- target-collection!
  [id]
  (s/check! (pos-int? id) "INVALID_COLLECTION" [:target_collection_id] "Choose an existing ordinary collection.")
  (api/write-check :model/Collection id)
  (let [collection (present! (db/collection id) [:target_collection_id])]
    (s/check! (and (not (:archived collection)) (nil? (:personal_owner_id collection))
                   (nil? (:type collection)) (nil? (:namespace collection))
                   (not (:is_remote_synced collection)))
              "INVALID_COLLECTION" [:target_collection_id] "Choose an unarchived ordinary collection.")
    collection))

(defn- target-references
  [bundle bindings]
  (s/check! (map? bindings) "INVALID_BINDINGS" [:database_bindings] "Provide database bindings.")
  (s/check! (= (set (keys bindings)) (set (map #(keyword (str (:id %))) (:databases bundle))))
            "INVALID_BINDINGS" [:database_bindings] "Bind every source database, without extra bindings.")
  (let [database-revisions (into {} (for [source (:databases bundle)
                                          :let [id (get bindings (keyword (str (:id source))))
                                                _ (s/check! (pos-int? id) "INVALID_BINDINGS" [:database_bindings] "Invalid database binding.")
                                                _ (api/read-check :model/Database id)
                                                target (present! (db/database id) [:database_bindings])]]
                                      (do (s/check! (and (= (:engine source) (name (:engine target))) (not (:is_stub target)))
                                                    "ENGINE_MISMATCH" [:database_bindings] "Bind a real database with the same engine.")
                                          [(:id source) {:id id :updated_at (str (:updated_at target))}])))
        databases (update-vals database-revisions :id)
        tables (into {} (for [source (:tables bundle)
                              :let [database-id (present! (get databases (:database_id source)) [:tables])
                                    target (one-match! (db/matching-tables database-id (:schema source) (:name source)) [:tables (:id source)])]]
                          [(:id source) (assoc source :target_id (:id target))]))
        fields (into {} (for [source (:fields bundle)
                              :let [table (present! (get tables (:table_id source)) [:fields])
                                    target (one-match! (db/matching-fields (:target_id table) (:name source)) [:fields (:id source)])]]
                          (do
                            (s/check! (= (select-keys source [:base_type :effective_type])
                                         (s/wire (select-keys target [:base_type :effective_type])))
                                      "FIELD_TYPE_MISMATCH" [:fields (:id source)] "Field types differ between source and target.")
                            [(:id source) (assoc source :target_id (:id target))])))]
    {:databases databases :database_revisions database-revisions :tables tables :fields fields}))

(defn- plan-card
  [source collection-id {:keys [databases tables fields]} dashboard-plans]
  (let [rewritten (checked-card source databases tables fields)
        owner (when-let [entity (:dashboard_entity_id source)] (get dashboard-plans entity))
        target (-> rewritten (dissoc :entity_id :dashboard_entity_id) (assoc :collection_id collection-id :type :question))
        _ (queries/check-allowed-to-create-card!
           (update target :dataset_query #(lib-be/normalize-query nil % {:strict? true})) :question)
        database-id (get-in target [:dataset_query :database])
        binding (db/asset-binding (:entity_id source) collection-id database-id)
        source-hash (s/digest source)
        existing (when binding (db/card (:card_id binding)))
        target-hash (when existing (s/digest (card-definition existing)))
        unchanged? (and binding existing (= source-hash (:source_hash binding))
                        (= target-hash (:target_hash binding))
                        (= collection-id (:collection_id existing)) (not (:archived existing))
                        (= :question (:type existing)) (nil? (:document_id existing))
                        (if (:dashboard_entity_id source)
                          (and (= "skip" (:action owner)) (= (:dashboard_id owner) (:dashboard_id existing)))
                          (nil? (:dashboard_id existing))))]
    {:model "card" :dashboard_entity_id (:dashboard_entity_id source)
     :source_entity_id (:entity_id source) :name (:name source)
     :action (cond (nil? binding) "create" unchanged? "skip" :else "conflict")
     :card_id (:card_id binding) :source_hash source-hash :target_hash target-hash :definition target
     :error (when (and binding (not unchanged?))
              {:code "ASSET_CHANGED" :message "The source or previously imported target changed. Imports never overwrite."})}))

(defn- build-plan
  [request]
  (let [{:keys [bundle database_bindings target_collection_id]} (s/wire request)
        _ (s/bundle! bundle)
        collection (target-collection! target_collection_id)
        references (target-references bundle database_bindings)
        cards (into {} (map (juxt :entity_id identity) (:cards bundle)))
        dashboards (into {} (map (juxt :entity_id identity) (:dashboards bundle)))
        _ (doseq [card (:cards bundle) :when (:dashboard_entity_id card)]
            (s/check! (and (contains? dashboards (:dashboard_entity_id card))
                           (some #{(:entity_id card)} (dashboard/referenced-cards (get dashboards (:dashboard_entity_id card)))))
                      "INVALID_REFERENCE" [:cards :dashboard_entity_id] "An owned question must appear in its dashboard."))
        _ (doseq [dash (:dashboards bundle)]
            (dashboard/rewrite dash cards identity identity
                               (fn [id path] (:target_id (present! (get (:fields references) id) path))) (:fields references)))
        dashboard-items (mapv (fn [dash]
                                (dashboard/plan dash target_collection_id
                                                (select-keys (:databases references)
                                                             (map #(get-in cards [% :dataset_query :database])
                                                                  (dashboard/referenced-cards dash)))))
                              (:dashboards bundle))
        dashboard-plans (into {} (map (juxt :source_entity_id identity) dashboard-items))
        items (into (mapv #(plan-card % target_collection_id references dashboard-plans) (:cards bundle)) dashboard-items)]
    {:references references :cards cards
     :plan_hash (s/digest {:bundle bundle :references references
                           :collection (select-keys collection [:id :archived :personal_owner_id :type :namespace :is_remote_synced])
                           :items items})
     :can_import (not-any? #(= "conflict" (:action %)) items)
     :items items}))

(defn- public-plan [plan]
  (-> plan (dissoc :references :cards)
      (update :items #(mapv (fn [item] (dissoc item :definition :source_hash :target_hash :dashboard_entity_id)) %))))

(defn preview
  "Pure preflight with respect to persisted application state and source warehouses."
  [request]
  (api/check-superuser)
  (try
    (public-plan (build-plan request))
    (catch clojure.lang.ExceptionInfo e
      (if (= 400 (:status-code (ex-data e)))
        {:can_import false :items []
         :errors [(merge {:code "INVALID_FORMAT" :message (ex-message e)}
                         (select-keys (ex-data e) [:code :path :object_ref]))]}
        (throw e)))))

(defn- replay
  [record request-hash]
  (api/check (= request-hash (:request_hash record)) [409 "Request ID was already used with different input."])
  (:result record))

(defn- deliver-events!
  "Pending notifications are recoverable by retrying the original import request. At-least-once delivery."
  [request-id]
  (t2/with-transaction [_]
    (let [record (db/lock-import! request-id)
          result (:result record)]
      (if (= "complete" (:status record))
        result
        (let [items (mapv (fn [item]
                            (if (and (= "created" (:action item)) (not (:event_delivered item)))
                              (try
                                (let [dashboard? (= "dashboard" (:model item))
                                      object (if dashboard? (db/dashboard (:dashboard_id item)) (db/card (:card_id item)))]
                                  (when object
                                    (events/publish-event! (if dashboard? :event/dashboard-create :event/card-create)
                                                           {:object object :user-id (:user_id record)})))
                                (assoc item :event_delivered true)
                                (catch Exception _ item))
                              item)) (:items result))
              pending? (some #(and (= "created" (:action %)) (not (:event_delivered %))) items)
              result (assoc result :items items :status (if pending? "events_pending" "complete"))]
          (db/update-import! request-id {:result result :status (:status result)})
          result)))))

(defn import-bundle!
  "Create definitions atomically. No warehouse query runs; source connection creation is separate."
  [{:keys [request_id plan_hash target_collection_id] :as request}]
  (api/check-superuser)
  (s/check! (and (string? request_id) (re-matches #"[A-Za-z0-9-]{16,64}" request_id))
            "INVALID_REQUEST_ID" [:request_id] "Provide a stable request ID of 16–64 letters, digits, or hyphens.")
  (let [request-hash (s/digest (dissoc request :request_id :plan_hash))]
    (if-let [existing (db/import-record request_id)]
      (replay existing request-hash)
      (try
        (t2/with-transaction [_]
          (target-collection! target_collection_id)
          (db/lock-collection! target_collection_id)
          (if-let [existing (db/import-record request_id)]
            (replay existing request-hash)
            (let [plan (build-plan request)]
              (api/check (= plan_hash (:plan_hash plan)) [409 "The import plan changed. Run preflight again."])
              (api/check (:can_import plan) [409 "Resolve import conflicts before continuing."])
              (let [dashboard-items (filterv #(= "dashboard" (:model %)) (:items plan))
                    dashboard-ids (into {} (map (fn [item]
                                                  [(:source_entity_id item)
                                                   (if (= "skip" (:action item)) (:dashboard_id item)
                                                       (:id (dashboard/create-shell! (:definition item) target_collection_id)))])
                                                dashboard-items))
                    card-items (mapv
                                (fn [{:keys [action definition source_entity_id source_hash card_id name dashboard_entity_id]}]
                                  (if (= "skip" action)
                                    {:model "card" :source_entity_id source_entity_id :name name :action "skipped" :card_id card_id}
                                    (let [definition (cond-> definition dashboard_entity_id
                                                             (assoc :dashboard_id (get dashboard-ids dashboard_entity_id)))
                                          card (queries/create-card! definition @api/*current-user* true false
                                                                     {:skip-query-metadata? true})]
                                      (db/insert-binding!
                                       {:source_entity_id source_entity_id :collection_id target_collection_id
                                        :database_id (get-in definition [:dataset_query :database])
                                        :card_id (:id card) :source_hash source_hash
                                        :target_hash (s/digest (card-definition card))})
                                      {:model "card" :source_entity_id source_entity_id :name name :action "created"
                                       :card_id (:id card) :event_delivered false})))
                                (filterv #(= "card" (:model %)) (:items plan)))
                    card-ids (into {} (map (juxt :source_entity_id :card_id) card-items))
                    items (into card-items
                                (mapv (fn [item]
                                        (let [id (get dashboard-ids (:source_entity_id item))]
                                          (when (= "create" (:action item))
                                            (dashboard/finish! item id target_collection_id (:cards plan) card-ids (:references plan)))
                                          (cond-> {:model "dashboard" :source_entity_id (:source_entity_id item) :name (:name item)
                                                   :action (if (= "skip" (:action item)) "skipped" "created") :dashboard_id id}
                                            (= "create" (:action item)) (assoc :event_delivered false)))) dashboard-items))
                    result {:request_id request_id :status "events_pending" :items items}]
                (db/insert-import! {:request_id request_id :request_hash request-hash :user_id api/*current-user-id*
                                    :status "events_pending" :result result})
                result))))
        (catch Exception e
          ;; A competing request may have committed under another collection lock. Its unique request key wins.
          (if-let [existing (db/import-record request_id)] (replay existing request-hash) (throw e)))))
    (deliver-events! request_id)))

(defn import-result
  "Read a completed request without rerunning it or executing warehouse SQL."
  [request-id]
  (api/check-superuser)
  (api/check-404 (:result (db/import-record request-id))))

(defn retry-import-events!
  "Retry committed creation events without requiring the uploaded file or creating any questions."
  [request-id]
  (api/check-superuser)
  (api/check-404 (db/import-record request-id))
  (deliver-events! request-id))
