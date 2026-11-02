(ns metabase.asset-transfer.core-test
  (:require
   [clojure.test :refer :all]
   [metabase.asset-transfer.core :as transfer]
   [metabase.events.core :as events]
   [metabase.lib.core :as lib]
   [metabase.queries.core :as queries]
   [metabase.queries.models.card.metadata :as card.metadata]
   [metabase.test :as mt]
   [metabase.test.util.thread-local :as tu.thread-local]
   [toucan2.core :as t2]))

(defn- request-for [source target collection cards]
  (let [bundle (transfer/export-bundle {:card_ids (mapv :id cards) :database_ids []})
        request {:bundle bundle :database_bindings {(keyword (str (:id source))) (:id target)}
                 :target_collection_id (:id collection)}]
    (assoc request :request_id (str (random-uuid)) :plan_hash (:plan_hash (transfer/preview request)))))

(defn- with-native-transfer! [f]
  ;; Independent connections are required to exercise commit/rollback and row locking, not a shared fixture transaction.
  (binding [tu.thread-local/*thread-local* false]
    (mt/with-temp [:model/Database source {:engine :postgres}
                   :model/Database target {:engine :postgres}
                   :model/Collection collection {}
                   :model/Card card {:type :question :display :table :result_metadata nil
                                     :dataset_query {:database (:id source) :type :native
                                                     :native {:query "select 1" :template-tags {}}}}]
      (mt/with-model-cleanup [:model/Card :model/AssetTransferImport :model/AssetTransferBinding]
        (mt/with-test-user :crowberto
          (f source target collection card))))))

(deftest native-import-and-idempotency-test
  (with-native-transfer! (fn [source target collection card]
                           (with-redefs [card.metadata/maybe-async-result-metadata (fn [& _] (throw (ex-info "Must not execute query metadata" {})))
                                         events/publish-event! (fn [& _])]
                             (let [request (request-for source target collection [card])
                                   before (t2/count :model/Card)
                                   result (transfer/import-bundle! request)
                                   target-card (t2/select-one :model/Card :id (-> result :items first :card_id))]
                               (is (= "complete" (:status result)))
                               (is (= (inc before) (t2/count :model/Card)))
                               (is (not= (:entity_id card) (:entity_id target-card)))
                               (is (= (:id target) (:database_id target-card)))
                               (is (= result (transfer/import-bundle! request)))
                               (is (= (inc before) (t2/count :model/Card)))
                               (is (= "skip" (-> (transfer/preview request) :items first :action)))
                               (is (thrown? clojure.lang.ExceptionInfo
                                            (transfer/import-bundle! (assoc-in request [:bundle :cards 0 :name] "Changed"))))
                               (t2/update! :model/Card (:id target-card) {:name "Locally changed"})
                               (is (= "conflict" (-> (transfer/preview request) :items first :action)))
                               (is (= result (transfer/import-result (:request_id request)))))))))

(deftest transaction-rollback-test
  (with-native-transfer! (fn [source target collection card]
                           (let [request (request-for source target collection [card])
                                 other (assoc (-> request :bundle :cards first) :entity_id "SecondSourceQuestion1" :name "Second")
                                 request (update-in request [:bundle :cards] conj other)
                                 request (assoc request :plan_hash (:plan_hash (transfer/preview request)))
                                 create! queries/create-card!
                                 calls (atom 0)
                                 before (t2/count :model/Card)]
                             (with-redefs [queries/create-card! (fn [& args]
                                                                  (if (= 2 (swap! calls inc))
                                                                    (throw (ex-info "Injected second-card failure" {}))
                                                                    (apply create! args)))
                                           events/publish-event! (fn [& _])]
                               (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Injected" (transfer/import-bundle! request))))
                             (is (= before (t2/count :model/Card)))
                             (is (zero? (t2/count :model/AssetTransferBinding :collection_id (:id collection))))
                             (is (nil? (t2/select-one :model/AssetTransferImport :request_id (:request_id request))))))))

(deftest stale-plan-test
  (with-native-transfer! (fn [source target collection card]
                           (let [request (request-for source target collection [card])]
                             (is (thrown-with-msg? clojure.lang.ExceptionInfo #"plan changed"
                                                   (transfer/import-bundle! (assoc request :plan_hash (apply str (repeat 64 "0"))))))))))

(deftest normal-creation-still-requests-metadata-test
  (with-native-transfer! (fn [source _target collection _card]
                           (let [calls (atom 0)]
                             (with-redefs [card.metadata/maybe-async-result-metadata (fn [_] (swap! calls inc) {:metadata nil})
                                           events/publish-event! (fn [& _])]
                               (queries/create-card! {:name "Normal save" :display :table :type :question
                                                      :collection_id (:id collection) :visualization_settings {}
                                                      :dataset_query {:database (:id source) :type :native :native {:query "select 1"}}}
                                                     {:id (mt/user->id :crowberto)})
                               (is (= 1 @calls)))))))

(deftest event-retry-test
  (with-native-transfer! (fn [source target collection card]
                           (let [request (request-for source target collection [card])
                                 before (t2/count :model/Card)]
                             (with-redefs [events/publish-event! (fn [& _] (throw (ex-info "Unavailable" {})))]
                               (is (= "events_pending" (:status (transfer/import-bundle! request)))))
                             (with-redefs [events/publish-event! (fn [& _])]
                               (is (= "complete" (:status (transfer/retry-import-events! (:request_id request)))))
                               (is (= "complete" (:status (transfer/import-bundle! request)))))
                             (is (= (inc before) (t2/count :model/Card)))))))

(deftest concurrent-retry-test
  (with-native-transfer!
    (fn [source target collection card]
      (let [request (request-for source target collection [card])
            before (t2/count :model/Card)
            start (promise)]
        (with-redefs [events/publish-event! (fn [& _])]
          (let [a (future @start (transfer/import-bundle! request))
                b (future @start (transfer/import-bundle! request))]
            (deliver start true)
            (try
              (is (= (deref a 30000 ::timeout) (deref b 30000 ::timeout)))
              (is (realized? a))
              (is (realized? b))
              (is (= (inc before) (t2/count :model/Card)))
              (finally (future-cancel a) (future-cancel b)))))))))

(deftest physical-metadata-resolution-test
  (mt/with-temp [:model/Database source {:engine :postgres}
                 :model/Database target {:engine :postgres}
                 :model/Table source-table {:db_id (:id source) :schema "public" :name "orders"}
                 :model/Table target-table {:db_id (:id target) :schema "public" :name "orders"}
                 :model/Field source-field {:table_id (:id source-table) :name "amount" :base_type :type/Integer}
                 :model/Field target-field {:table_id (:id target-table) :name "amount" :base_type :type/Integer}
                 :model/Collection collection {}
                 :model/Card card {:type :question :display :table :result_metadata nil
                                   :dataset_query {:database (:id source) :type :query
                                                   :query {:source-table (:id source-table)
                                                           :fields [[:field (:id source-field) nil]]}}}]
    (mt/with-model-cleanup [:model/Card :model/AssetTransferImport :model/AssetTransferBinding]
      (mt/with-test-user :crowberto
        (let [request (request-for source target collection [card])]
          (is (true? (:can_import (transfer/preview request))))
          (with-redefs [events/publish-event! (fn [& _])]
            (let [result (transfer/import-bundle! request)
                  imported (t2/select-one :model/Card :id (-> result :items first :card_id))
                  ;; Inspect the pinned wire representation, not unstable MBQL 5 clause UUIDs.
                  query (lib/->legacy-MBQL (:dataset_query imported))]
              (is (= (:id target-table) (get-in query [:query :source-table])))
              (is (= (:id target-field) (get-in query [:query :fields 0 1])))))
          (t2/update! :model/Field (:id target-field) {:name "renamed"})
          (is (= "METADATA_NOT_FOUND" (-> (transfer/preview request) :errors first :code)))
          (is (thrown? clojure.lang.ExceptionInfo (transfer/import-bundle! (assoc request :request_id (str (random-uuid)))))))))))
