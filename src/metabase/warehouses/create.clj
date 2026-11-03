(ns metabase.warehouses.create
  (:require
   [metabase.api.common :as api]
   [metabase.warehouses.db :as db]))

(defn create-database!
  "Persist a connection-validated database. The caller publishes creation events after committing."
  [definition]
  (api/check-superuser)
  (api/check-500 (db/insert-database! definition)))
