(ns metabase.asset-transfer.db
  "Application database access for asset transfer. No source warehouse queries."
  (:require
   [metabase.asset-transfer.models.transfer]
   [metabase.asset-transfer.schema :as s]
   [metabase.warehouse-schema-overlay.core :as overlay]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(defn card "Read a question including its managed definition." [id] (t2/select-one :model/Card :id id))
(defn database "Read a database; callers must allowlist details before exporting." [id] (t2/select-one :model/Database :id id))
(defn table "Read an active physical table." [id]
  (t2/select-one :model/Table :id id :active true {:from [(overlay/table-query)]}))
(defn field "Read an active field with effective user metadata." [id]
  (t2/select-one :model/Field :id id :active true {:from [(overlay/field-query)]}))
(defn collection "Read a target collection." [id] (t2/select-one :model/Collection :id id))

(defn matching-tables "Resolve physical names without guessing among multiple matches." [database-id schema table-name]
  (t2/select :model/Table :db_id database-id :schema schema :name table-name :active true {:from [(overlay/table-query)]}))

(defn matching-fields "Resolve top-level fields including effective types." [table-id field-name]
  (t2/select :model/Field :table_id table-id :name field-name :parent_id nil :active true {:from [(overlay/field-query)]}))

(defn asset-binding "Look up a source identity within one destination scope." [source-id collection-id database-id]
  (t2/select-one :model/AssetTransferBinding :source_entity_id source-id
                 :collection_id collection-id :database_id database-id))

(defn insert-binding! "Save a mapping in the same transaction as its question." [row] (t2/insert! :model/AssetTransferBinding row))
(defn import-record "Find a committed request." [request-id] (t2/select-one :model/AssetTransferImport :request_id request-id))
(defn insert-import! "Record a committed batch and pending events." [row] (t2/insert! :model/AssetTransferImport row))
(defn update-import! "Checkpoint creation event delivery." [request-id row] (t2/update! :model/AssetTransferImport :request_id request-id row))

(defn lock-collection!
  "Serialize transfers into the same collection, including requests with different idempotency keys."
  [id]
  (t2/select-one :model/Collection :id id {:for :update}))

(defn lock-import! "Serialize event delivery and retries for one request." [request-id]
  (t2/select-one :model/AssetTransferImport :request_id request-id {:for :update}))

(defn dashboard "Read a dashboard including runtime flags for eligibility checks." [id]
  (t2/select-one :model/Dashboard :id id))
(defn dashboard-tabs "Read tabs in stable presentation order." [id]
  (t2/select :model/DashboardTab :dashboard_id id {:order-by [[:position :asc] [:id :asc]]}))
(defn dashcards "Read the complete dashboard layout including series." [id]
  (t2/hydrate (t2/select :model/DashboardCard :dashboard_id id {:order-by [[:id :asc]]}) :series))
(defn dashboard-binding "Look up a dashboard within a destination collection." [source-id collection-id]
  (t2/select-one :model/AssetTransferDashboardBinding :source_entity_id source-id :collection_id collection-id))
(defn insert-dashboard-binding! "Persist the dashboard mapping inside its import transaction." [row]
  (t2/insert! :model/AssetTransferDashboardBinding row))
(defn insert-dashboard! "Insert a validated dashboard; caller defers events until commit." [row]
  (t2/insert-returning-instance! :model/Dashboard row))
(defn insert-tab! "Create a validated tab and return its new ID." [row]
  (t2/insert-returning-pk! :model/DashboardTab row))

(defn resource-binding "Read one durable YAML resource binding." [namespace kind key]
  (t2/select-one :model/AssetResourceBinding :identity_hash [:auto/param (s/digest [namespace kind key])]))
(defn target-bindings "Read source identities for an exported target." [kind id]
  (t2/select :model/AssetResourceBinding :resource_type [:auto/param kind] :target_id [:auto/param id]
             {:order-by [[:id :asc]]}))
(defn insert-resource-binding! "Persist a resource identity in its creation transaction." [row]
  (t2/insert! :model/AssetResourceBinding
              (assoc row :identity_hash (s/digest ((juxt :namespace :resource_type :resource_key) row)))))
(defn provisioning-owner "Find an active administrator by case-insensitive email." [email]
  (t2/select-one :model/User :is_active true :is_superuser true
                 {:where [:= [:lower :email] [:auto/param (.toLowerCase ^String email java.util.Locale/ROOT)]]}))

(defn complete-resource-events! "Checkpoint resource creation event delivery." [id]
  (t2/update! :model/AssetResourceBinding :id [:auto/param id] {:events_pending false}))

(defn card-by-entity "Read the exported question by entity identity." [entity]
  (t2/select-one :model/Card :entity_id [:auto/param entity]))
(defn dashboard-by-entity "Read the exported dashboard by entity identity." [entity]
  (t2/select-one :model/Dashboard :entity_id [:auto/param entity]))

(defn resource-name-exists? "Whether an unbound resource name already exists in the destination scope." [kind resource-name]
  (case kind
    "database" (t2/exists? :model/Database :name [:auto/param resource-name])
    "collection" (t2/exists? :model/Collection :name [:auto/param resource-name] :location "/" :archived false)))
