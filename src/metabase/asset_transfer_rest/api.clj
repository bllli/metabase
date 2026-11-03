(ns metabase.asset-transfer-rest.api
  "Admin-only OSS question definition transfer. This is unrelated to query-result export."
  (:require
   [metabase.api.common :as api]
   [metabase.api.macros :as api.macros]
   [metabase.asset-transfer.core :as transfer]
   [metabase.util.malli.schema :as ms]))

(def ^:private ImportRequest
  [:map {:closed true}
   [:yaml [:string {:min 1 :max 10485760}]]
   [:database_bindings {:optional true} (ms/string-keyed-map ms/PositiveInt)]
   [:target_collection_id {:optional true} ms/PositiveInt]])

(api.macros/defendpoint :post "/inspect" :- :map
  "Validate a YAML AssetSet without writing resources."
  [_route-params _query-params body :- [:map {:closed true} [:yaml [:string {:min 1 :max 10485760}]]]]
  (transfer/inspect (:yaml body)))

(api.macros/defendpoint :post "/export" :- :map
  "Export question definitions and non-sensitive connection templates."
  [_route-params _query-params
   body :- [:map {:closed true}
            [:dashboard_ids {:default []} [:vector {:max 20} ms/PositiveInt]]
            [:card_ids {:default []} [:vector {:max 100} ms/PositiveInt]]
            [:database_ids {:default []} [:vector {:max 100} ms/PositiveInt]]]]
  (api/check-superuser)
  (transfer/export-yaml body))

(api.macros/defendpoint :post "/import/preview" :- :map
  "Validate and preview an import without writing or running warehouse queries."
  [_route-params _query-params body :- ImportRequest]
  (api/check-superuser)
  (transfer/preview body))

(api.macros/defendpoint :post "/import" :- :map
  "Execute an unchanged plan atomically; retries must reuse request_id."
  [_route-params _query-params
   body :- [:merge ImportRequest
            [:map {:closed true}
             [:plan_hash [:string {:min 64 :max 64}]]
             [:request_id [:string {:min 16 :max 64}]]]]]
  (api/check-superuser)
  (transfer/apply! body))

(api.macros/defendpoint :get "/imports/:request-id" :- :map
  "Recover a committed import result after a network interruption."
  [{:keys [request-id]} :- [:map {:closed true} [:request-id [:string {:min 16 :max 64 :api/regex #"[A-Za-z0-9-]{16,64}"}]]]]
  (api/check-superuser)
  (transfer/import-result request-id))

(api.macros/defendpoint :post "/imports/:request-id/retry-events" :- :map
  "Retry pending creation events for an already committed import."
  [{:keys [request-id]} :- [:map {:closed true} [:request-id [:string {:min 16 :max 64 :api/regex #"[A-Za-z0-9-]{16,64}"}]]]]
  (api/check-superuser)
  (transfer/retry-import-events! request-id))
