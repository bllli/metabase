(ns metabase.app-db.custom-migrations.email-text
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [toucan2.core :as t2]))

(def ^:private view-resources
  {"v_users"                 "users/v5/postgres-users.sql"
   "v_subscriptions"         "subscriptions/v1/postgres-subscriptions.sql"
   "v_alerts"                "alerts/v4/postgres-alerts.sql"
   "v_ai_usage_log"          "ai_usage_log/v6/postgres-ai_usage_log.sql"
   "v_metabot_conversations" "metabot_conversations/v8/postgres-metabot_conversations.sql"
   "v_api_key_usage"         "api_key_usage/v1/postgres-api_key_usage.sql"
   "v_agent_api_calls"       "agent_api_calls/v1/postgres-agent_api_calls.sql"
   "v_mcp_tool_calls"        "mcp_tool_calls/v1/postgres-mcp_tool_calls.sql"})

(defn- identifier [s]
  (str "\"" (str/replace s "\"" "\"\"") "\""))

(defn- literal [s]
  (if (nil? s) "NULL" (str "E'" (str/replace (str/replace s "\\" "\\\\") "'" "''") "'")))

(defn- dependent-views []
  (t2/query
   ["WITH RECURSIVE dependents AS (
       SELECT r.ev_class AS oid, 1 AS depth
       FROM pg_depend d JOIN pg_rewrite r ON r.oid = d.objid
       JOIN pg_attribute a ON a.attrelid = d.refobjid AND a.attnum = d.refobjsubid
       WHERE d.classid = 'pg_rewrite'::regclass AND d.refclassid = 'pg_class'::regclass
         AND a.attrelid = 'public.core_user'::regclass AND a.attname = 'email'
       UNION ALL
       SELECT r.ev_class, p.depth + 1
       FROM dependents p JOIN pg_depend d ON d.refobjid = p.oid
       JOIN pg_rewrite r ON r.oid = d.objid
       WHERE d.classid = 'pg_rewrite'::regclass AND d.refclassid = 'pg_class'::regclass
         AND r.ev_class <> p.oid
     )
     SELECT c.oid::bigint AS oid, c.relname AS name, n.nspname AS schema,
            c.relkind::text AS kind, pg_get_userbyid(c.relowner) = current_user AS owned,
            array_to_string(c.reloptions, ', ') AS options, obj_description(c.oid) AS comment,
            EXISTS (SELECT 1 FROM pg_trigger t WHERE t.tgrelid = c.oid AND NOT t.tgisinternal)
              OR EXISTS (SELECT 1 FROM pg_rewrite r WHERE r.ev_class = c.oid AND r.rulename <> '_RETURN')
              AS custom_rules,
            max(p.depth) AS depth
     FROM dependents p JOIN pg_class c ON c.oid = p.oid JOIN pg_namespace n ON n.oid = c.relnamespace
     GROUP BY c.oid, n.nspname ORDER BY max(p.depth), c.relname"]))

(defn- grants [oid]
  (t2/query
   ["SELECT CASE WHEN a.grantee = 0 THEN 'PUBLIC' ELSE quote_ident(pg_get_userbyid(a.grantee)) END AS grantee,
            a.privilege_type AS privilege, a.is_grantable AS grantable, a.grantor = c.relowner AS owner_granted,
            NULL::text AS column_name
     FROM pg_class c, LATERAL aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) a
     WHERE c.oid = ?
     UNION ALL
     SELECT CASE WHEN a.grantee = 0 THEN 'PUBLIC' ELSE quote_ident(pg_get_userbyid(a.grantee)) END,
            a.privilege_type, a.is_grantable, a.grantor = c.relowner, col.attname
     FROM pg_class c JOIN pg_attribute col ON col.attrelid = c.oid,
          LATERAL aclexplode(col.attacl) a
     WHERE c.oid = ?" oid oid]))

(defn- view-snapshot [view]
  (when-not (and (= "public" (:schema view)) (= "v" (:kind view))
                 (:owned view) (not (:custom_rules view)) (contains? view-resources (:name view)))
    (throw (ex-info "Cannot migrate core_user.email: unsupported dependent view; preserve and migrate it manually."
                    (select-keys view [:schema :name :kind :owned :custom_rules]))))
  (let [privileges (grants (:oid view))]
    (when-not (every? :owner_granted privileges)
      (throw (ex-info "Cannot migrate core_user.email: dependent view has delegated grants."
                      (select-keys view [:schema :name]))))
    (assoc view :grants privileges
           :column-comments (t2/query ["SELECT attname AS name, col_description(attrelid, attnum) AS comment
                                        FROM pg_attribute WHERE attrelid = ? AND attnum > 0 AND NOT attisdropped"
                                       (:oid view)]))))

(defn- restore-view! [{:keys [name options comment column-comments] :as view}]
  (let [qualified-name (str "public." (identifier name))]
    ;; Use the versioned definitions: pg_get_viewdef embeds citext casts for the old v_users output type.
    (t2/query [(slurp (io/resource (str "migrations/instance_analytics_views/" (view-resources name))))])
    (when (seq options)
      (t2/query [(str "ALTER VIEW " qualified-name " SET (" options ")")]))
    (t2/query [(str "COMMENT ON VIEW " qualified-name " IS " (literal comment))])
    (doseq [{column :name column-comment :comment} column-comments
            :when (some? column-comment)]
      (t2/query [(str "COMMENT ON COLUMN " qualified-name "." (identifier column) " IS " (literal column-comment))]))
    ;; Remove any default privileges applied by CREATE VIEW before restoring the original grants.
    (let [oid (:oid (first (t2/query ["SELECT to_regclass(?)::oid::bigint AS oid" qualified-name])))]
      (doseq [grantee (distinct (map :grantee (grants oid)))]
        (t2/query [(str "REVOKE ALL ON " qualified-name " FROM " grantee)])))
    (doseq [{:keys [grantee privilege grantable] column-name :column_name} (:grants view)]
      (t2/query [(str "GRANT " privilege (when column-name (str " (" (identifier column-name) ")"))
                      " ON " qualified-name " TO " grantee (when grantable " WITH GRANT OPTION"))]))))

(defn migrate-email-to-text!
  "Convert PostgreSQL user emails to text and enforce case-insensitive uniqueness, preserving supported view metadata."
  []
  (t2/with-transaction [_conn]
    (t2/query ["LOCK TABLE public.core_user IN ACCESS EXCLUSIVE MODE"])
    (let [email-type (:type (first (t2/query ["SELECT t.typname AS type FROM pg_attribute a
                                             JOIN pg_type t ON t.oid = a.atttypid
                                             WHERE a.attrelid = 'public.core_user'::regclass AND a.attname = 'email'"])))
          views      (when (= "citext" email-type) (mapv view-snapshot (dependent-views)))]
      (when-not (#{"citext" "text"} email-type)
        (throw (ex-info "Cannot migrate core_user.email: unexpected column type." {:type email-type})))
      (doseq [view (reverse views)]
        (t2/query [(str "DROP VIEW public." (identifier (:name view)))]))
      (when (= "citext" email-type)
        (t2/query ["ALTER TABLE public.core_user ALTER COLUMN email TYPE text USING email::text"]))
      (doseq [view views]
        (restore-view! view))
      (t2/query ["CREATE UNIQUE INDEX IF NOT EXISTS core_user_email_lower_key ON public.core_user (lower(email))"])
      (when-not (:valid (first (t2/query ["SELECT i.indisunique AND i.indisvalid AND i.indisready
                                          AND i.indrelid = 'public.core_user'::regclass
                                          AND i.indpred IS NULL AND i.indnatts = 1
                                          AND pg_get_indexdef(i.indexrelid, 1, true) = 'lower(email)' AS valid
                                          FROM pg_index i WHERE i.indexrelid = 'public.core_user_email_lower_key'::regclass"])))
        (throw (ex-info "Cannot migrate core_user.email: conflicting case-insensitive unique index." {})))
      (t2/query ["DROP INDEX IF EXISTS public.idx_lower_email"]))))
