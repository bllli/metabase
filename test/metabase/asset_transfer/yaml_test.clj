(ns metabase.asset-transfer.yaml-test
  (:require
   [clojure.test :refer :all]
   [metabase.asset-transfer.core :as assets]
   [metabase.asset-transfer.document :as document]
   [metabase.asset-transfer.yaml :as yaml]))

(def empty-document
  {:apiVersion "silinex.metabase/v1" :kind "AssetSet" :metadata {:key "business"}
   :spec {:databases [] :collections [{:key "reports" :name "Reports"}]
          :tables [] :fields [] :questions [] :dashboards []}})

(deftest document-roundtrip-test
  (is (= empty-document (yaml/parse (yaml/generate empty-document))))
  (is (= {:databases [] :tables [] :fields [] :cards [] :dashboards []}
         (document/validate! empty-document)))
  (is (fn? assets/provision!)))

(deftest unsafe-yaml-test
  (doseq [input ["a: 1\na: 2" "a: &a [1]\nb: *a" "a: &a hello\nb: *a"
                 "a: !!java.net.URL ['https://example.com']" "a: !!str hello"
                 "a: {<<: {x: 1}}" "---\na: 1\n---\nb: 2"
                 (str "a: " (apply str (repeat 100 "[")) "1" (apply str (repeat 100 "]")))]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid YAML" (yaml/parse input)))))

(deftest literal-sql-test
  (let [value {:sql "select '{{n}}', '&anchor', ': value'\n-- hello\n"}]
    (is (= value (yaml/parse (yaml/generate value))))))

(deftest closed-document-test
  (doseq [doc [(assoc empty-document :format_version 2)
               (assoc empty-document :apiVersion "silinex.metabase/v2")
               (assoc-in empty-document [:metadata :key] "bad/key")
               (assoc-in empty-document [:spec :collections] [])
               (assoc-in empty-document [:spec :databases]
                         [{:key "warehouse" :name "DB" :engine "postgres" :details {:password "plaintext"}}])]]
    (is (thrown? clojure.lang.ExceptionInfo (document/validate! doc)))))
