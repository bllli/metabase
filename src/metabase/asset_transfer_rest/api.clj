(ns metabase.asset-transfer-rest.api
  "Admin-only OSS question definition transfer. This is unrelated to query-result export."
  (:require
   [metabase.api.common :as api]
   [metabase.api.macros :as api.macros]
   [metabase.asset-transfer.core :as transfer]
   [metabase.util.malli.schema :as ms]))

(def ^:private Scalar [:or :string number? :boolean :nil])

(def ^:private Clause
  [:schema {:registry {::clause [:or Scalar
                                 [:map {:closed true}
                                  [:base-type {:optional true} [:maybe :string]]
                                  [:temporal-unit {:optional true} [:maybe :string]]]
                                 [:vector [:ref ::clause]]]}}
   [:ref ::clause]])

(def ^:private TemplateTag
  [:map {:closed true}
   [:id {:optional true} :string]
   [:name :string]
   [:display-name {:optional true} :string]
   [:type [:enum "text" "number" "date"]]
   [:default {:optional true} Scalar]
   [:required {:optional true} :boolean]])

(def ^:private Query
  [:map {:closed true}
   [:database ms/PositiveInt]
   [:type [:enum "native" "query"]]
   [:native {:optional true}
    [:map {:closed true}
     [:query ms/NonBlankString]
     [:template-tags {:optional true} (ms/string-keyed-map TemplateTag)]]]
   [:query {:optional true}
    [:map {:closed true}
     [:source-table ms/PositiveInt]
     [:fields {:optional true} [:vector Clause]]
     [:filter {:optional true} Clause]
     [:aggregation {:optional true} [:vector Clause]]
     [:breakout {:optional true} [:vector Clause]]
     [:order-by {:optional true} [:vector Clause]]
     [:limit {:optional true} ms/PositiveInt]]]])

(def ^:private Card
  [:map {:closed true}
   [:entity_id [:re #"[A-Za-z0-9_-]{21}"]]
   [:name [:string {:min 1 :max 254}]]
   [:description {:optional true} [:maybe :string]]
   [:display [:enum "table" "scalar" "line" "bar"]]
   [:dataset_query Query]
   [:visualization_settings ms/VisualizationSettings]
   [:parameters [:vector
                 [:map {:closed true}
                  [:id {:optional true} :string]
                  [:name {:optional true} :string]
                  [:slug {:optional true} :string]
                  [:type [:enum "category" "number/=" "date/single"]]
                  [:target Clause]
                  [:default {:optional true} Scalar]]]]])

(def ^:private Bundle
  [:map {:closed true}
   [:format [:= "metabase-asset-transfer"]]
   [:format_version [:= 1]]
   [:producer [:map {:closed true} [:metabase_version :string] [:transfer_version [:= 1]]]]
   [:databases [:vector {:max 100}
                [:map {:closed true}
                 [:id ms/PositiveInt] [:name :string] [:engine [:enum "postgres" "mysql" "clickhouse"]]
                 [:details [:map {:closed true}
                            [:host {:optional true} :string]
                            [:port {:optional true} [:or :string :int]]
                            [:dbname {:optional true} :string]
                            [:db {:optional true} :string]
                            [:user {:optional true} :string]
                            [:ssl {:optional true} :boolean]
                            [:ssl-mode {:optional true} :string]]]]]]
   [:tables [:vector [:map {:closed true}
                      [:id ms/PositiveInt] [:database_id ms/PositiveInt]
                      [:schema [:maybe :string]] [:name :string]]]]
   [:fields [:vector [:map {:closed true}
                      [:id ms/PositiveInt] [:table_id ms/PositiveInt]
                      [:name :string] [:base_type :string] [:effective_type {:optional true} [:maybe :string]]]]]
   [:cards [:vector {:max 100} Card]]])

(def ^:private ImportRequest
  [:map {:closed true}
   [:bundle Bundle]
   [:database_bindings (ms/string-keyed-map ms/PositiveInt)]
   [:target_collection_id ms/PositiveInt]])

(api.macros/defendpoint :post "/export" :- :map
  "Export question definitions and non-sensitive connection templates."
  [_route-params _query-params
   body :- [:map {:closed true}
            [:card_ids {:default []} [:vector {:max 100} ms/PositiveInt]]
            [:database_ids {:default []} [:vector {:max 100} ms/PositiveInt]]]]
  (api/check-superuser)
  (transfer/export-bundle body))

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
  (transfer/import-bundle! body))

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
