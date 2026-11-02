(ns metabase-enterprise.advanced-config.db-test
  (:require
   [clojure.test :refer :all]
   [metabase-enterprise.advanced-config.db :as db]
   [metabase.test :as mt]
   [toucan2.core :as t2]))

(deftest user-email-case-insensitive-test
  (mt/with-temp [:model/User {id :id} {:email "case.user@example.com"}]
    (t2/update! :core_user id {:email "Case.User@Example.com"})
    (is (= id (:id (db/user-by-email "CASE.USER@EXAMPLE.COM"))))
    (is (= id (:id (db/user-columns-by-email [:id] "case.user@example.com"))))))
