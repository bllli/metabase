(ns metabase.app-db.liquibase.postgres-test
  (:require
   [clojure.test :refer :all]
   [metabase.app-db.liquibase])
  (:import
   (liquibase.database Database)
   (liquibase.database.core H2Database PostgresDatabase)
   (liquibase.sql Sql)
   (liquibase.sqlgenerator SqlGeneratorFactory)
   (liquibase.statement.core RawSqlStatement)))

(set! *warn-on-reflection* true)

(defn- generated-sql [^Database database sql]
  (mapv #(.toSql ^Sql %)
        (.generateSql (SqlGeneratorFactory/getInstance) (RawSqlStatement. sql) database)))

(deftest ^:parallel postgres-initialization-without-citext-test
  (let [database (PostgresDatabase.)]
    ;; Liquibase caches generator selection by statement/database class, not by SQL contents.
    (is (= ["SELECT 1"] (generated-sql database "SELECT 1")))
    (is (= [] (generated-sql database "CREATE EXTENSION IF NOT EXISTS citext WITH SCHEMA public")))
    (is (= ["CREATE TABLE public.core_user (id integer NOT NULL, email text NOT NULL)"]
           (generated-sql database
                          "CREATE TABLE public.core_user (id integer NOT NULL, email public.citext NOT NULL)")))
    (testing "unrelated migrations and databases are unchanged"
      (doseq [sql ["CREATE EXTENSION IF NOT EXISTS other_extension"
                   "CREATE TABLE other_table (email public.citext)"
                   "SELECT 'public.citext'"]]
        (is (= [sql] (generated-sql database sql))))
      (is (= ["CREATE TABLE core_user (email VARCHAR_IGNORECASE)"]
             (generated-sql (H2Database.) "CREATE TABLE core_user (email VARCHAR_IGNORECASE)"))))))
