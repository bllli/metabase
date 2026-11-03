(ns metabase.asset-transfer.assets-test
  (:require
   [clojure.test :refer :all]
   [metabase.asset-transfer.core :as assets]
   [metabase.asset-transfer.db :as db]
   [metabase.asset-transfer.document :as document]
   [metabase.asset-transfer.resources :as resources]
   [metabase.asset-transfer.startup :as startup]
   [metabase.asset-transfer.yaml :as yaml]
   [metabase.asset-transfer.yaml-test :refer [empty-document]]
   [metabase.config.core :as config]
   [metabase.events.core :as events]
   [metabase.test :as mt]
   [metabase.test.util.thread-local :as tu.thread-local]
   [metabase.warehouses.core :as warehouses]
   [toucan2.core :as t2]))

(def native-document
  (-> empty-document
      (assoc-in [:spec :databases] [{:key "warehouse" :name "Warehouse" :engine "postgres"
                                     :details {:host "warehouse" :dbname "analytics" :user "reader"}}])
      (assoc-in [:spec :questions]
                [{:key "revenue" :name "Revenue" :description nil :display "scalar"
                  :visualization_settings {} :parameters []
                  :dataset_query {:database "warehouse" :type "native" :native {:query "select 1" :template-tags {}}}}])))

(defmacro with-resources [& body]
  `(binding [tu.thread-local/*thread-local* false]
     (mt/with-model-cleanup [:model/Database :model/Collection :model/Card :model/Dashboard
                             :model/AssetResourceBinding :model/AssetTransferBinding
                             :model/AssetTransferDashboardBinding :model/AssetTransferImport]
       (mt/with-test-user :crowberto
         (with-redefs [events/publish-event! (fn [& _#])
                       warehouses/test-connection-details (fn [_# details#] details#)
                       resources/sync-metadata! (fn [id#] (t2/update! :model/Database id# {:initial_sync_status "complete"}))]
           ~@body)))))

(deftest create-restart-and-drift-test
  (with-resources
    (let [text (yaml/generate native-document)
          before [(t2/count :model/Database) (t2/count :model/Collection) (t2/count :model/Card)]
          plan (assets/preview {:yaml text})]
      (is (:can_import plan))
      (is (:requires_sync plan))
      (is (= ["create" "create" "create"] (mapv :action (:items plan))))
      (is (= before [(t2/count :model/Database) (t2/count :model/Collection) (t2/count :model/Card)]))
      (let [result (assets/provision! text)
            card-id (-> result :items first :card_id)]
        (is (= "complete" (:status result)))
        (is (= (mapv inc before) [(t2/count :model/Database) (t2/count :model/Collection) (t2/count :model/Card)]))
        (is (= result (assets/provision! text)))
        (is (= ["skip" "skip" "skip"] (mapv :action (:items (assets/preview {:yaml text})))))
        (is (= (mapv inc before) [(t2/count :model/Database) (t2/count :model/Collection) (t2/count :model/Card)]))
        (t2/update! :model/Card card-id {:name "Manual edit"})
        (is (false? (:can_import (assets/preview {:yaml text}))))
        (is (thrown? clojure.lang.ExceptionInfo (assets/provision! text)))))))

(deftest partial-failure-recovery-test
  (with-resources
    (let [text (yaml/generate native-document)
          before (t2/count :model/Database)]
      (mt/with-dynamic-fn-redefs [resources/sync-metadata! (fn [_] (throw (ex-info "offline" {})))]
        (is (thrown? clojure.lang.ExceptionInfo (assets/provision! text))))
      (is (= (inc before) (t2/count :model/Database)))
      (is (= "complete" (:status (assets/provision! text))))
      (is (= (inc before) (t2/count :model/Database))))))

(deftest concurrent-provision-test
  (with-resources
    (let [text (yaml/generate native-document)
          start (promise)
          before (t2/count :model/Database)
          a (future @start (assets/provision! text))
          b (future @start (assets/provision! text))]
      (deliver start true)
      (try
        (is (= "complete" (:status (deref a 30000 ::timeout))))
        (is (= (deref a 30000 ::timeout) (deref b 30000 ::timeout)))
        (is (= (inc before) (t2/count :model/Database)))
        (finally (future-cancel a) (future-cancel b))))))

(deftest explicit-binding-and-secret-test
  (with-resources
    (mt/with-temp [:model/Database target {:engine :postgres} :model/Collection collection {}]
      (let [doc (assoc-in native-document [:spec :databases 0 :details :password]
                          {:valueFrom {:env "MISSING_YAML_ASSET_TEST_SECRET"}})
            text (yaml/generate doc)
            request {:yaml text :database_bindings {:warehouse (:id target)} :target_collection_id (:id collection)}
            plan (assets/preview request)]
        (is (false? (:can_import (assets/preview {:yaml text}))))
        (is (= "SECRET_UNAVAILABLE" (-> (assets/preview {:yaml text}) :errors first :code)))
        (is (:can_import plan))
        (is (= ["bind" "bind" "create"] (mapv :action (:items plan))))
        (is (= "complete" (:status (assets/apply! (assoc request :plan_hash (:plan_hash plan) :request_id (str (random-uuid)))))))
        (is (false? (:can_import (assets/preview (assoc request :database_bindings {:unknown (:id target)})))))))))

(deftest source-change-and-deletion-test
  (with-resources
    (let [text (yaml/generate native-document)]
      (assets/provision! text)
      (is (false? (:can_import (assets/preview {:yaml (yaml/generate (assoc-in native-document [:spec :databases 0 :name] "Changed"))}))))
      (t2/delete! :model/Card :id (:target_id (db/resource-binding "business" "card" "revenue")))
      (is (thrown? clojure.lang.ExceptionInfo (assets/provision! text))))))

(deftest export-keeps-keys-and-excludes-secrets-test
  (with-resources
    (let [result (assets/provision! (yaml/generate native-document))
          exported (assets/export-yaml {:card_ids [(-> result :items first :card_id)] :database_ids [] :dashboard_ids []})
          doc (assets/inspect (:yaml exported))]
      (is (:can_import (assets/preview {:yaml (:yaml exported)})) (pr-str (assets/preview {:yaml (:yaml exported)})))
      (is (= (document/validate! native-document) (document/validate! doc)))
      (is (= "business" (get-in doc [:metadata :key])))
      (is (= "revenue" (get-in doc [:spec :questions 0 :key])))
      (is (= "warehouse" (get-in doc [:spec :databases 0 :key])))
      (is (nil? (get-in doc [:spec :databases 0 :details :password])))
      (is (= "select 1" (get-in doc [:spec :questions 0 :dataset_query :native :query]))))))

(deftest startup-disabled-and-failure-test
  (mt/with-dynamic-fn-redefs [config/config-str (constantly nil)
                              assets/provision! (fn [_] (throw (ex-info "Must not run" {})))]
    (is (nil? (startup/initialize!))))
  (mt/with-dynamic-fn-redefs [config/config-str (fn [key] (when (= key :mb-provisioning-file) "/missing"))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"existing active administrator" (startup/initialize!)))))

(deftest provisioning-event-recovery-test
  (with-resources
    (let [text (yaml/generate native-document)
          pending (with-redefs [events/publish-event! (fn [topic _]
                                                        (when (= topic :event/card-create)
                                                          (throw (ex-info "Unavailable" {}))))]
                    (assets/provision! text))
          before (t2/count :model/Card)]
      (is (= "events_pending" (:status pending)))
      (is (= "complete" (:status (assets/provision! text))))
      (is (= before (t2/count :model/Card))))))

(deftest existing-name-requires-explicit-binding-test
  (with-resources
    (mt/with-temp [:model/Database existing {:name "Warehouse" :engine :postgres}]
      (is (false? (:can_import (assets/preview {:yaml (yaml/generate native-document)}))))
      (is (:can_import (assets/preview {:yaml (yaml/generate native-document)
                                        :database_bindings {:warehouse (:id existing)}}))))))
