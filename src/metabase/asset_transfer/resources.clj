(ns metabase.asset-transfer.resources
  "Durable data source and collection provisioning shared by HTTP and boot imports."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [metabase.api.common :as api]
   [metabase.app-db.cluster-lock :as cluster-lock]
   [metabase.asset-transfer.db :as db]
   [metabase.asset-transfer.schema :as s]
   [metabase.collections.core :as collections]
   [metabase.events.core :as events]
   [metabase.sync.core :as sync]
   [metabase.warehouses.core :as warehouses]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(defn bounded-file
  "Read a UTF-8 file with a strict byte limit, without exposing paths or contents on failure."
  [path limit]
  (try
    (with-open [stream (io/input-stream path)]
      (let [bytes (.readNBytes stream (int (inc limit)))]
        (s/check! (<= (alength bytes) limit) "FILE_TOO_LARGE" [] "File exceeds its size limit.")
        (String. bytes java.nio.charset.StandardCharsets/UTF_8)))
    (catch Exception _ (s/fail! "FILE_UNAVAILABLE" [] "A configured file is unavailable or exceeds its size limit."))))

(defn connection-details
  "Resolve a connection secret only for resource creation. Never include the result in a plan or log."
  [definition]
  (let [details (:details definition)]
    (if-let [ref (get-in details [:password :valueFrom])]
      (let [password (if-let [env (:env ref)] (System/getenv env)
                             (str/replace (bounded-file (:file ref) 65536) #"\r?\n$" ""))]
        (s/check! (and (string? password) (not (str/blank? password)))
                  "SECRET_UNAVAILABLE" [:details :password] "A required connection secret is unavailable.")
        (assoc details :password password))
      details)))

(defn target-definition
  "Read properties managed by YAML, excluding resolved credentials."
  [kind id]
  (case kind
    "database" (when-let [database (db/database id)]
                 (when-not (:is_stub database)
                   {:name (:name database) :engine (name (:engine database))
                    :details (s/connection-template (:details database))}))
    "collection" (when-let [collection (db/collection id)]
                   (when (and (not (:archived collection)) (nil? (:personal_owner_id collection))
                              (nil? (:type collection)) (nil? (:namespace collection)) (not (:is_remote_synced collection)))
                     (select-keys collection [:name :location])))
    nil))

(defn plan-resource
  "Plan one resource. Explicit bindings adopt existing resources without changing their properties."
  [namespace kind definition explicit-id]
  (let [key (:key definition)
        binding (db/resource-binding namespace kind key)
        id (or explicit-id (:target_id binding))
        target (when id (target-definition kind id))
        source-hash (s/digest definition)
        target-hash (when target (s/digest target))
        compatible? (and target (or (= kind "collection") (= (:engine definition) (:engine target))))
        unchanged? (and compatible? (= id (:target_id binding))
                        (= source-hash (:source_hash binding)) (= target-hash (:target_hash binding)))
        action (cond binding (if unchanged? "skip" "conflict")
                     explicit-id (if compatible? "bind" "conflict")
                     :else (if (db/resource-name-exists? kind (:name definition)) "conflict" "create"))]
    (when (= action "create")
      (when (= kind "database") (connection-details definition)))
    {:model kind :source_entity_id key :name (:name definition) :action action
     :target_id id :source_hash source-hash :target_hash target-hash
     :error (when (= action "conflict")
              {:code "RESOURCE_CHANGED" :message "Resource is missing, changed, incompatible, or its name already exists. Bind existing resources explicitly."})}))

(defn- checked-connection [definition]
  (let [details (connection-details definition)]
    (try
      (let [checked (warehouses/test-connection-details (:engine definition) details)]
        (s/check! (not= false (:valid checked)) "CONNECTION_FAILED" [:databases] "Data source connection failed.")
        checked)
      (catch Exception _ (s/fail! "CONNECTION_FAILED" [:databases] "Data source connection failed.")))))

(defn ensure-resource!
  "Create or adopt one resource atomically. Connection checks run outside the transaction."
  [namespace kind definition explicit-id]
  (s/check! (not (.isInterrupted (Thread/currentThread))) "PROVISIONING_CANCELLED" [] "Asset import was cancelled.")
  (let [plan (plan-resource namespace kind definition explicit-id)
        details (when (and (= kind "database") (= "create" (:action plan))) (checked-connection definition))]
    (cluster-lock/with-cluster-lock (keyword "asset-resource" (s/digest [namespace kind (:key definition)]))
      (t2/with-transaction [_]
        (let [plan (plan-resource namespace kind definition explicit-id)]
          (s/check! (not= "conflict" (:action plan)) "RESOURCE_CHANGED" [] "Resource changed; run preflight again.")
          (if (= "skip" (:action plan))
            (:target_id plan)
            (let [id (or explicit-id
                         (:id (case kind
                                "database" (warehouses/create-database!
                                            {:name (:name definition) :engine (:engine definition) :details details
                                             :creator_id api/*current-user-id* :is_full_sync false :is_on_demand false})
                                "collection" (collections/create-collection! {:name (:name definition)}))))]
              (db/insert-resource-binding!
               {:namespace namespace :resource_type kind :resource_key (:key definition) :target_id id
                :source_hash (:source_hash plan) :target_hash (s/digest (target-definition kind id))
                :secret_reference (get-in definition [:details :password])
                :events_pending (and (= kind "database") (nil? explicit-id))})
              id)))))))

(defn deliver-database-event!
  "Deliver a recoverable creation event; metadata synchronization is owned by the import executor."
  [namespace definition]
  (cluster-lock/with-cluster-lock (keyword "asset-resource" (s/digest [namespace "database" (:key definition)]))
    (t2/with-transaction [_]
      (let [binding (db/resource-binding namespace "database" (:key definition))]
        (when (:events_pending binding)
          (try
            (events/publish-event! :event/database-create
                                   {:object (db/database (:target_id binding)) :user-id api/*current-user-id* :skip-auto-sync? true})
            (db/complete-resource-events! (:id binding))
            (catch Exception _ (s/fail! "EVENTS_PENDING" [] "Data source was saved; retry to finish creation events."))))))))

(defn sync-metadata!
  "Synchronize metadata explicitly. The asset preflight subsequently checks every required table and field."
  [id]
  (try
    (sync/sync-db-metadata-explicit! (db/database id))
    (s/check! (= "complete" (name (:initial_sync_status (db/database id))))
              "SYNC_FAILED" [:databases] "Metadata synchronization did not complete.")
    (catch Exception _ (s/fail! "SYNC_FAILED" [:databases] "Metadata synchronization did not complete."))))
