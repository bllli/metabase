(ns metabase.asset-transfer-rest.api-test
  (:require
   [clojure.test :refer :all]
   [metabase.events.core :as events]
   [metabase.queries.models.card.metadata :as card.metadata]
   [metabase.test :as mt]))

(def empty-bundle
  {:format "metabase-asset-transfer" :format_version 1
   :producer {:metabase_version "test" :transfer_version 1}
   :databases [] :tables [] :fields [] :cards []})

(deftest administrators-only-test
  (doseq [[method path body] [[:post "asset-transfer/export" {:card_ids [] :database_ids []}]
                              [:post "asset-transfer/import/preview" {:bundle empty-bundle :database_bindings {} :target_collection_id 1}]
                              [:post "asset-transfer/import" {:bundle empty-bundle :database_bindings {} :target_collection_id 1
                                                              :plan_hash (apply str (repeat 64 "a"))
                                                              :request_id "12345678-1234-1234-1234-123456789012"}]]]
    (is (some? (mt/user-http-request :rasta method 403 path body))))
  (is (some? (mt/user-http-request :rasta :get 403 "asset-transfer/imports/12345678-1234-1234-1234-123456789012")))
  (is (some? (mt/user-http-request :rasta :post 403 "asset-transfer/imports/12345678-1234-1234-1234-123456789012/retry-events"))))

(deftest http-roundtrip-test
  (mt/with-temp [:model/Database source {:engine :postgres}
                 :model/Database target {:engine :postgres}
                 :model/Collection collection {}
                 :model/Card card {:type :question :display :table :result_metadata nil
                                   :dataset_query {:database (:id source) :type :native
                                                   :native {:query "select 1" :template-tags {}}}}]
    (mt/with-model-cleanup [:model/Card :model/AssetTransferImport :model/AssetTransferBinding]
      (with-redefs [card.metadata/maybe-async-result-metadata (fn [& _] (throw (ex-info "Import must not execute SQL" {})))
                    events/publish-event! (fn [& _])]
        (let [bundle (mt/user-http-request :crowberto :post 200 "asset-transfer/export"
                                           {:card_ids [(:id card)] :database_ids []})
              request {:bundle bundle :database_bindings {(str (:id source)) (:id target)}
                       :target_collection_id (:id collection)}
              preview (mt/user-http-request :crowberto :post 200 "asset-transfer/import/preview" request)
              request (assoc request :plan_hash (:plan_hash preview) :request_id (str (random-uuid)))
              result (mt/user-http-request :crowberto :post 200 "asset-transfer/import" request)]
          (is (true? (:can_import preview)))
          (is (= "complete" (:status result)))
          (is (= "created" (-> result :items first :action)))
          (is (= result (mt/user-http-request :crowberto :post 200 "asset-transfer/import" request)))
          (is (= result (mt/user-http-request :crowberto :post 200 (str "asset-transfer/imports/" (:request_id request) "/retry-events"))))
          (is (= result (mt/user-http-request :crowberto :get 200 (str "asset-transfer/imports/" (:request_id request))))))))))
