(ns metabase.asset-transfer.startup
  (:require
   [metabase.asset-transfer.assets :as assets]
   [metabase.asset-transfer.db :as db]
   [metabase.asset-transfer.resources :as resources]
   [metabase.asset-transfer.schema :as s]
   [metabase.config.core :as config]
   [metabase.request.core :as request]
   [metabase.util.log :as log]))

(defn initialize!
  "Apply the configured YAML file before readiness. Disabled unless MB_PROVISIONING_FILE is set."
  []
  (when-let [path (config/config-str :mb-provisioning-file)]
    (let [email (config/config-str :mb-provisioning-owner-email)
          owner (when email (db/provisioning-owner email))
          timeout (or (config/config-long :mb-provisioning-timeout-seconds) 300)]
      (s/check! owner "INVALID_OWNER" [] "MB_PROVISIONING_OWNER_EMAIL must identify an existing active administrator.")
      (s/check! (<= 1 timeout 3600) "INVALID_TIMEOUT" [] "Provisioning timeout must be between 1 and 3600 seconds.")
      (log/info "Applying startup YAML assets")
      (let [work (future
                   (request/with-current-user (:id owner)
                     (assets/provision! (resources/bounded-file path s/max-bytes))))]
        (try
          (let [result (deref work (* timeout 1000) ::timeout)]
            (s/check! (not= ::timeout result) "PROVISIONING_TIMEOUT" [] "Startup asset provisioning timed out.")
            (s/check! (= "complete" (:status result)) "EVENTS_PENDING" [] "Startup asset creation events are still pending.")
            (log/info "Startup YAML assets applied successfully"))
          (catch Exception error
            ;; Retain our safe error code, never nested JDBC/parser messages or event payloads.
            (let [allowed #{"PROVISIONING_TIMEOUT" "EVENTS_PENDING" "IMPORT_CONFLICT" "INVALID_YAML"
                            "CONNECTION_FAILED" "SYNC_FAILED" "SECRET_UNAVAILABLE" "FILE_UNAVAILABLE"
                            "RESOURCE_CHANGED" "ASSET_PREFLIGHT_FAILED"}
                  code (or (some #(allowed (:code (ex-data %))) (take-while some? (iterate ex-cause error)))
                           "PROVISIONING_FAILED")]
              (throw (ex-info (str "Startup YAML provisioning failed (" code "). Check the file, resource conflicts, secrets and data source availability.")
                              {:code code}))))
          (finally (future-cancel work)))))))
