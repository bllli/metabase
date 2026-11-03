(ns metabase.asset-transfer.dashboard-test
  (:require
   [clojure.test :refer :all]
   [metabase.asset-transfer.transfer :as transfer]
   [metabase.asset-transfer.yaml :as yaml]
   [metabase.events.core :as events]
   [metabase.queries.models.card.metadata :as card.metadata]
   [metabase.test :as mt]
   [metabase.test.util.thread-local :as tu.thread-local]
   [toucan2.core :as t2]))

(defn- with-dashboard-transfer! [f]
  (binding [tu.thread-local/*thread-local* false]
    (mt/with-temp [:model/Database source {:engine :postgres}
                   :model/Database target {:engine :postgres}
                   :model/Table source-table {:db_id (:id source) :schema "public" :name "orders"}
                   :model/Table target-table {:db_id (:id target) :schema "public" :name "orders"}
                   :model/Field source-field {:table_id (:id source-table) :name "amount" :base_type :type/Integer}
                   :model/Field target-field {:table_id (:id target-table) :name "amount" :base_type :type/Integer}
                   :model/Collection collection {}
                   :model/Dashboard dashboard {:name "Sales" :parameters [{:id "amount" :name "Amount" :slug "amount" :type :number/=}]}
                   :model/DashboardTab tab {:dashboard_id (:id dashboard) :name "Overview" :position 0}
                   :model/Card card {:type :question :display :table :dashboard_id (:id dashboard) :result_metadata nil
                                     :dataset_query {:database (:id source) :type :query
                                                     :query {:source-table (:id source-table)
                                                             :fields [[:field (:id source-field) nil]]}}}
                   :model/DashboardCard _dc {:dashboard_id (:id dashboard) :dashboard_tab_id (:id tab)
                                             :card_id (:id card) :row 2 :col 0 :size_x 12 :size_y 6
                                             :parameter_mappings [{:parameter_id "amount" :card_id (:id card)
                                                                   :target [:dimension [:field (:id source-field) nil]]}]}
                   :model/DashboardCard _text {:dashboard_id (:id dashboard) :dashboard_tab_id (:id tab)
                                               :row 0 :col 0 :size_x 24 :size_y 2
                                               :visualization_settings {:virtual_card {:display "text" :name nil :archived false :visualization_settings {}}
                                                                        :text "Sales report"}}]
      (mt/with-model-cleanup [:model/Dashboard :model/Card :model/AssetTransferImport
                              :model/AssetTransferBinding :model/AssetTransferDashboardBinding :model/AssetResourceBinding]
        (mt/with-test-user :crowberto
          (let [bundle (transfer/export-bundle {:dashboard_ids [(:id dashboard)] :card_ids [] :database_ids []})
                request {:bundle bundle :database_bindings {(keyword (str (:id source))) (:id target)}
                         :target_collection_id (:id collection) :request_id (str (random-uuid))}
                request (assoc request :plan_hash (:plan_hash (transfer/preview request)))]
            (f request dashboard card target-field)))))))

(deftest dashboard-roundtrip-test
  (with-dashboard-transfer!
    (fn [request source-dashboard source-card target-field]
      (is (vector? (get-in request [:bundle :dashboards])))
      (is (= [(:entity_id source-card)] (mapv :entity_id (get-in request [:bundle :cards]))))
      (is (true? (:can_import (transfer/preview request))))
      (with-redefs [events/publish-event! (fn [& _])
                    card.metadata/maybe-async-result-metadata (fn [& _] (throw (ex-info "Must not execute queries" {})))]
        (let [result (transfer/import-bundle! request)
              dash-id (:dashboard_id (first (filter :dashboard_id (:items result))))
              card-id (:card_id (first (filter :card_id (:items result))))
              dashboard (t2/select-one :model/Dashboard :id dash-id)
              card (t2/select-one :model/Card :id card-id)
              dc (t2/select-one :model/DashboardCard :dashboard_id dash-id :card_id card-id)
              text (t2/select-one :model/DashboardCard :dashboard_id dash-id :card_id nil)
              tab (t2/select-one :model/DashboardTab :dashboard_id dash-id)]
          (is (= "complete" (:status result)))
          (is (not= (:id source-dashboard) dash-id))
          (is (= "Sales" (:name dashboard)))
          (is (= dash-id (:dashboard_id card)))
          (is (= (:id tab) (:dashboard_tab_id dc) (:dashboard_tab_id text)))
          (is (= "Overview" (:name tab)))
          (is (= [2 0 12 6] (mapv dc [:row :col :size_x :size_y])))
          (is (= "Sales report" (get-in text [:visualization_settings :text])))
          (is (= card-id (get-in dc [:parameter_mappings 0 :card_id])))
          (is (= (:id target-field) (get-in dc [:parameter_mappings 0 :target 1 1])))
          (is (= result (transfer/import-bundle! request)))
          (is (= ["skip" "skip"] (mapv :action (:items (transfer/preview request)))))
          (t2/update! :model/DashboardCard (:id dc) {:row 10})
          (is (false? (:can_import (transfer/preview request))))
          (is (some #(= "conflict" (:action %)) (:items (transfer/preview request)))))))))

(deftest dashboard-rollback-test
  (with-dashboard-transfer!
    (fn [request _ _ _]
      (let [before [(t2/count :model/Dashboard) (t2/count :model/Card)]
            insert! t2/insert!]
        (with-redefs [t2/insert! (fn [model & args]
                                   (if (= model :model/AssetTransferDashboardBinding)
                                     (throw (ex-info "Injected dashboard failure" {}))
                                     (apply insert! model args)))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Injected dashboard failure"
                                (transfer/import-bundle! request))))
        (is (= before [(t2/count :model/Dashboard) (t2/count :model/Card)]))
        (is (nil? (t2/select-one :model/AssetTransferImport :request_id (:request_id request))))))))

(deftest invalid-dashboard-references-test
  (with-dashboard-transfer!
    (fn [request _ _ _]
      (doseq [bad-request [(assoc-in request [:bundle :dashboards 0 :dashcards 0 :card_id] "MissingQuestion000001")
                           (assoc-in request [:bundle :dashboards 0 :dashcards 0 :dashboard_tab_id] 999999)
                           (assoc-in request [:bundle :dashboards 0 :dashcards 0 :visualization_settings :click_behavior] {:type "link"})
                           (assoc-in request [:bundle :dashboards 0 :parameters 0 :values_source_type] "card")]]
        (is (false? (:can_import (transfer/preview bad-request))))))))

(deftest dashboard-http-and-event-retry-test
  (with-dashboard-transfer!
    (fn [request source-dashboard _ _]
      (let [exported (mt/user-http-request :crowberto :post 200 "asset-transfer/export"
                                           {:dashboard_ids [(:id source-dashboard)]})
            doc (yaml/parse (:yaml exported))
            request {:yaml (:yaml exported) :request_id (:request_id request)
                     :target_collection_id (:target_collection_id request)
                     :database_bindings {(keyword (get-in doc [:spec :databases 0 :key]))
                                         (first (vals (:database_bindings request)))}}
            preview (mt/user-http-request :crowberto :post 200 "asset-transfer/import/preview" (dissoc request :request_id :plan_hash))
            request (assoc request :plan_hash (:plan_hash preview))]
        (is (true? (:can_import preview)) (pr-str preview))
        (with-redefs [events/publish-event! (fn [& _] (throw (ex-info "Unavailable" {})))]
          (let [result (mt/user-http-request :crowberto :post 200 "asset-transfer/import" request)]
            (is (= "events_pending" (:status result)))
            (is (= 2 (count (:items result))))))
        (let [delivered (atom [])]
          (with-redefs [events/publish-event! (fn [topic _] (swap! delivered conj topic))]
            (is (= "complete" (:status (transfer/retry-import-events! (:request_id request)))))
            (is (= #{:event/card-create :event/dashboard-create} (set @delivered)))
            (transfer/retry-import-events! (:request_id request))
            (is (= 2 (count @delivered)))))))))

(deftest shared-series-and-multiple-databases-test
  (with-dashboard-transfer!
    (fn [request _ _ _]
      (mt/with-temp [:model/Database other-source {:engine :postgres}
                     :model/Database other-target {:engine :postgres}]
        (let [native {:entity_id "NativeQuestion0000001" :name "Native series" :description nil :display "bar"
                      :visualization_settings {} :parameters []
                      :dataset_query {:database (:id other-source) :type "native"
                                      :native {:query "select {{amount}}" :template-tags {:amount {:name "amount" :display-name "Amount" :type "number"}}}}}
              bundle (-> (:bundle request)
                         (update :databases conj {:id (:id other-source) :name "Second source" :engine "postgres" :details {}})
                         (update :cards conj native)
                         (assoc-in [:dashboards 0 :dashcards 0 :series] [(:entity_id native)])
                         (update-in [:dashboards 0 :dashcards 0 :parameter_mappings]
                                    conj {:card_id (:entity_id native) :parameter_id "amount" :target ["variable" ["template-tag" "amount"]]})
                         (update :dashboards conj {:entity_id "SharedDashboard000001" :name "Shared dashboard" :description nil
                                                   :width "full" :auto_apply_filters true :parameters [] :tabs []
                                                   :dashcards [{:card_id (:entity_id native) :series [] :action_id nil :dashboard_tab_id nil
                                                                :row 0 :col 0 :size_x 12 :size_y 6 :visualization_settings {}
                                                                :parameter_mappings [] :inline_parameters []}]}))
              request (-> request (assoc :bundle bundle)
                          (assoc-in [:database_bindings (keyword (str (:id other-source)))] (:id other-target)))
              preview (transfer/preview request)
              request (assoc request :plan_hash (:plan_hash preview))]
          (is (true? (:can_import preview)) (pr-str preview))
          (with-redefs [events/publish-event! (fn [& _])]
            (let [result (transfer/import-bundle! request)
                  native-id (:card_id (first (filter #(= (:entity_id native) (:source_entity_id %)) (:items result))))
                  dashboards (mapv :dashboard_id (filter :dashboard_id (:items result)))
                  series (t2/select :model/DashboardCardSeries :card_id native-id)]
              (is (= 4 (count (:items result))))
              (is (= 1 (count series)))
              (is (= (:id other-target) (:database_id (t2/select-one :model/Card :id native-id))))
              (is (= native-id (:card_id (t2/select-one :model/DashboardCard :dashboard_id (second dashboards)))))
              (is (= ["skip" "skip" "skip" "skip"] (mapv :action (:items (transfer/preview request)))))
              (is (false? (:can_import (transfer/preview (assoc-in request [:database_bindings (keyword (str (:id other-source)))]
                                                                   (:id other-source)))))))))))))

(deftest empty-dashboard-test
  (mt/with-temp [:model/Dashboard source {}
                 :model/Collection collection {}]
    (mt/with-model-cleanup [:model/Dashboard :model/AssetTransferDashboardBinding :model/AssetTransferImport]
      (mt/with-test-user :crowberto
        (let [request {:bundle (transfer/export-bundle {:dashboard_ids [(:id source)] :card_ids [] :database_ids []})
                       :database_bindings {} :target_collection_id (:id collection) :request_id (str (random-uuid))}
              preview (transfer/preview request)]
          (is (true? (:can_import preview)) (pr-str preview))
          (with-redefs [events/publish-event! (fn [& _])]
            (is (= "created" (-> (transfer/import-bundle! (assoc request :plan_hash (:plan_hash preview))) :items first :action)))))))))
