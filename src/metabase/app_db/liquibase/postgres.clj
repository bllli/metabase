(ns metabase.app-db.liquibase.postgres
  (:require
   [clojure.string :as str])
  (:import
   (liquibase.database.core PostgresDatabase)
   (liquibase.sql Sql)
   (liquibase.sqlgenerator SqlGeneratorFactory)
   (liquibase.sqlgenerator.core RawSqlGenerator)
   (liquibase.statement.core RawSqlStatement)
   (liquibase.util StringUtil)))

(set! *warn-on-reflection* true)

(defn- initialization-statement [^RawSqlStatement statement]
  (let [sql (str/trim (StringUtil/stripComments (.getSql statement)))]
    (cond
      (re-matches #"CREATE EXTENSION IF NOT EXISTS citext WITH SCHEMA public;?" sql) :extension
      (str/starts-with? sql "CREATE TABLE public.core_user (") :users)))

(defn- initialization-generator ^RawSqlGenerator []
  ;; Change generated SQL, not the shipped bootstrap resource or its Liquibase checksum.
  (proxy [RawSqlGenerator] []
    (getPriority []
      (let [^RawSqlGenerator this this]
        (inc (proxy-super getPriority))))
    (supports [statement database]
      (and (instance? PostgresDatabase database)
           (instance? RawSqlStatement statement)))
    (generateSql [^RawSqlStatement statement database chain]
      (let [^RawSqlGenerator this this]
        (case (initialization-statement statement)
          :extension (into-array Sql [])
          :users (proxy-super generateSql
                              (RawSqlStatement. (str/replace (.getSql statement)
                                                             "email public.citext NOT NULL"
                                                             "email text NOT NULL")
                                                (.getEndDelimiter statement))
                              database chain)
          (proxy-super generateSql statement database chain))))))

(defn register-postgres-generators!
  "Register PostgreSQL bootstrap SQL that does not require the citext extension."
  []
  (.register (SqlGeneratorFactory/getInstance) (initialization-generator)))
