(ns metabase.asset-transfer.schema-test
  (:require
   [clojure.test :refer :all]
   [metabase.asset-transfer.schema :as s]))

(def basic-card
  {:entity_id "source-question-00001" :name "Revenue" :description nil :display "bar" :parameters []
   :visualization_settings {:graph.dimensions ["created_at"] :graph.metrics ["sum"]}
   :dataset_query {:database 1 :type "query"
                   :query {:source-table 2 :aggregation [["sum" ["field" 3 nil]]]
                           :breakout [["field" 4 {:temporal-unit "month"}]]
                           :filter ["=" ["field" 5 nil] 3]
                           :order-by [["asc" ["aggregation" 0]]]}}})

(defn- remap [card]
  (s/card card {1 101} {2 202} (fn [id _] ({3 303 4 404 5 505} id))))

(deftest physical-reference-roundtrip-test
  (let [mapped (remap basic-card)]
    (is (= 101 (get-in mapped [:dataset_query :database])))
    (is (= 202 (get-in mapped [:dataset_query :query :source-table])))
    (is (= [["sum" ["field" 303 nil]]] (get-in mapped [:dataset_query :query :aggregation])))
    (is (= ["=" ["field" 505 nil] 3] (get-in mapped [:dataset_query :query :filter]))
        "A numeric filter literal equal to a source field ID must not be rewritten")
    (is (= [["asc" ["aggregation" 0]]] (get-in mapped [:dataset_query :query :order-by])))))

(deftest display-reference-test
  (let [settings {:table.columns [{:name "total" :fieldRef ["field" 3 nil] :enabled true}]
                  :column_settings {(keyword "[\"ref\",[\"field\",3,null]]") {:decimals 2}
                                    (keyword "[\"name\",\"3\"]") {:prefix "$"}}}
        mapped (s/visualization settings (fn [id _] (+ 100 id)))]
    (is (= ["field" 103 nil] (get-in mapped [:table.columns 0 :fieldRef])))
    (is (= {:decimals 2} (get-in mapped [:column_settings (keyword "[\"ref\",[\"field\",103,null]]")])))
    (is (= {:prefix "$"} (get-in mapped [:column_settings (keyword "[\"name\",\"3\"]")])))))

(deftest unsupported-query-and-display-test
  (doseq [card [(assoc-in basic-card [:dataset_query :query :source-table] "card__2")
                (assoc-in basic-card [:dataset_query :query :joins] [])
                (assoc-in basic-card [:dataset_query :query :expressions] {:x 1})
                (assoc-in basic-card [:dataset_query :query :fields] [["field" 3 {:source-field 4}]])
                (assoc-in basic-card [:dataset_query :query :filter] ["segment" 1])
                (assoc-in basic-card [:visualization_settings :click_behavior] {:type "link"})
                (assoc basic-card :display "map")]]
    (is (thrown? clojure.lang.ExceptionInfo (remap card)))))

(deftest native-parameters-test
  (let [q {:database 1 :type "native" :native {:query "select {{n}}"
                                               :template-tags {:n {:name "n" :type "number" :default 3}}}}]
    (is (= "select {{n}}" (get-in (s/query q {1 2} {} (fn [id _] id)) [:native :query])))
    (doseq [bad [(assoc-in q [:native :query] "select * from {{#12}}")
                 (assoc-in q [:native :query] "{{ snippet: shared }}")
                 (assoc-in q [:native :template-tags :n :type] "dimension")
                 (assoc-in q [:native :template-tags :n :card-id] 3)]]
      (is (thrown? clojure.lang.ExceptionInfo (s/query bad {1 2} {} (fn [id _] id)))))))

(deftest connection-template-allowlist-test
  (is (= {:host "db" :port 5432 :dbname "analytics" :user "reader" :ssl true}
         (s/connection-template {:host "db" :port 5432 :dbname "analytics" :user "reader" :ssl true
                                 :password "secret" :ssh-tunnel-private-key "secret" :token "secret"
                                 :jdbc-options "password=secret" :connection-string "secret"
                                 :unknown-option "secret"}))))

(deftest stable-digest-test
  (is (= (s/digest {:a 1 :b {:c :query}}) (s/digest {:b {:c "query"} :a 1})))
  (is (not= (s/digest [1 2]) (s/digest [2 1]))))

(deftest bundle-boundary-test
  (let [bundle {:format "metabase-asset-transfer" :format_version 1
                :producer {:metabase_version "test" :transfer_version 1}
                :cards [basic-card] :databases [{:id 1 :name "db" :engine "postgres" :details {}}]
                :tables [] :fields []}]
    (is (= bundle (s/bundle! bundle "test")))
    (doseq [bad [(assoc bundle :format_version 2)
                 (assoc-in bundle [:databases 0 :details :password] "secret")
                 (assoc bundle :cards [basic-card basic-card])
                 (assoc bundle :cards (vec (repeat 101 basic-card)))
                 (assoc bundle :unexpected true)]]
      (is (thrown? clojure.lang.ExceptionInfo (s/bundle! bad "test"))))
    (is (thrown? clojure.lang.ExceptionInfo (s/bundle! bundle "other")))))
