(ns metabase.asset-transfer.db
  "Application database access for asset transfer. No source warehouse queries."
  (:require
   [metabase.asset-transfer.models.transfer]
   [metabase.warehouse-schema-overlay.core :as overlay]
   [toucan2.core :as t2]))

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
