(ns ^:mb/app-db-migrations-test metabase.app-db.email-text-test
  (:require
   [clojure.test :refer :all]
   [metabase.app-db.custom-migrations.email-text :as email-text]
   [metabase.app-db.liquibase.postgres :as postgres]
   [metabase.app-db.schema-migrations-test.impl :as impl]
   [metabase.models.serialization.resolve.default :as resolve.default]
   [metabase.test :as mt]
   [metabase.util :as u]
   [toucan2.core :as t2]))

(defn- email-type []
  (:type (first (t2/query ["SELECT t.typname AS type FROM pg_attribute a JOIN pg_type t ON t.oid = a.atttypid
                           WHERE a.attrelid = 'core_user'::regclass AND a.attname = 'email'"]))))

(defn- insert-user! [email]
  (t2/insert-returning-pk! :core_user {:email email :date_joined :%now :type "personal"
                                       :entity_id (u/generate-nano-id)}))

(defn- check-email-uniqueness! []
  (let [id (insert-user! "Mixed.Case@example.com")]
    (is (= id (resolve.default/import-fk-keyed "MIXED.CASE@EXAMPLE.COM" 'User :email)))
    (is (thrown? Exception (insert-user! "mixed.case@example.com")))
    (let [other-id (insert-user! "other@example.com")]
      (is (thrown? Exception (t2/update! :core_user other-id {:email "MIXED.CASE@example.com"}))))))

(deftest postgres-fresh-install-without-citext-test
  (mt/test-driver :postgres
    (impl/test-migrations ["emtxtq7k" "emtxtq7k"] [migrate!]
      (is (= "text" (email-type)))
      (is (empty? (t2/query ["SELECT 1 FROM pg_extension WHERE extname = 'citext'"])))
      (testing "a conflicting pre-existing index must not bypass the uniqueness guarantee"
        (t2/query ["CREATE INDEX core_user_email_lower_key ON core_user (email)"])
        (is (thrown? Exception (migrate!)))
        (is (= "text" (email-type)))
        (t2/query ["DROP INDEX core_user_email_lower_key"]))
      (migrate!)
      (check-email-uniqueness!)
      (email-text/migrate-email-to-text!)
      (is (empty? (t2/query ["SELECT 1 FROM pg_extension WHERE extname = 'citext'"]))))))

(deftest postgres-existing-citext-upgrade-test
  (mt/test-driver :postgres
    ;; Reproduce an installation made by the previous release, using the unchanged bootstrap SQL.
    (mt/with-dynamic-fn-redefs [postgres/initialization-statement (constantly nil)]
      (impl/test-migrations ["emtxtq7k" "emtxtq7k"] [migrate!]
        (is (= "citext" (email-type)))
        (let [id (insert-user! "Existing.Mixed@example.com")
              checksum (t2/select-one-fn :md5sum :databasechangelog :id "v00.00-000")]
          (t2/query ["GRANT SELECT ON v_users TO PUBLIC"])
          (t2/query ["GRANT UPDATE (email) ON v_users TO PUBLIC"])
          (t2/query ["COMMENT ON VIEW v_users IS E'owner''s \\\\comment'"])
          (t2/query ["COMMENT ON COLUMN v_users.email IS 'retained email comment'"])
          (t2/query ["ALTER VIEW v_users SET (security_barrier = true)"])
          (t2/query ["ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT INSERT ON TABLES TO PUBLIC"])
          (testing "custom dependent views stop the upgrade without changing the database"
            (t2/query ["CREATE VIEW custom_email_view AS SELECT email FROM core_user"])
            (is (thrown? Exception (migrate!)))
            (is (= "citext" (email-type)))
            (is (some? (t2/select-one :v_users :user_id id)))
            (t2/query ["DROP VIEW custom_email_view"]))
          (testing "a failure after dropping views rolls back the type and views together"
            (mt/with-dynamic-fn-redefs [email-text/restore-view! (fn [_] (throw (ex-info "injected failure" {})))]
              (is (thrown-with-msg? Exception #"injected failure" (email-text/migrate-email-to-text!))))
            (is (= "citext" (email-type)))
            (is (some? (t2/select-one :v_users :user_id id))))
          (migrate!)
          (is (= "text" (email-type)))
          (is (= "Existing.Mixed@example.com" (t2/select-one-fn :email :core_user :id id)))
          (is (= checksum (t2/select-one-fn :md5sum :databasechangelog :id "v00.00-000")))
          (is (= id (resolve.default/import-fk-keyed "EXISTING.MIXED@EXAMPLE.COM" 'User :email)))
          (is (= {:view_comment "owner's \\comment" :column_comment "retained email comment"
                  :can_select true :can_update true :can_insert false :barrier true}
                 (first (t2/query ["SELECT obj_description('v_users'::regclass) AS view_comment,
                                   col_description('v_users'::regclass, a.attnum) AS column_comment,
                                   EXISTS (SELECT 1 FROM aclexplode(c.relacl) p
                                           WHERE p.grantee = 0 AND p.privilege_type = 'SELECT') AS can_select,
                                   EXISTS (SELECT 1 FROM aclexplode(a.attacl) p
                                           WHERE p.grantee = 0 AND p.privilege_type = 'UPDATE') AS can_update,
                                   EXISTS (SELECT 1 FROM aclexplode(c.relacl) p
                                           WHERE p.grantee = 0 AND p.privilege_type = 'INSERT') AS can_insert,
                                   'security_barrier=true' = ANY(c.reloptions) AS barrier
                                   FROM pg_class c JOIN pg_attribute a ON a.attrelid = c.oid
                                   WHERE c.oid = 'v_users'::regclass AND a.attname = 'email'"]))))
          (testing "no application view or column still needs citext"
            (t2/query ["DROP EXTENSION citext RESTRICT"])
            (is (some? (t2/select-one :v_users :user_id id))))
          (check-email-uniqueness!)
          (migrate!)
          (is (= "text" (email-type))))))))
