(ns metabase-enterprise.scim.db-test
  (:require
   [clojure.test :refer :all]
   [metabase-enterprise.scim.db :as db]
   [metabase.test :as mt]
   [toucan2.core :as t2]))

(deftest user-email-case-insensitive-test
  (mt/with-temp [:model/User {id :id} {:email "case.user@example.com"}]
    (t2/update! :core_user id {:email "Case.User@Example.com"})
    (is (= id (:id (db/scim-user-by-email "CASE.USER@EXAMPLE.COM"))))))
