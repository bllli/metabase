# YAML assets in the private OSS build

Export, manual import and startup provisioning use one `silinex.metabase/v1` YAML document. The version identifies the asset schema, not a Metabase build or Git commit. Old JSON transfer files are not supported.

## Export and manual import

Open **Admin → Asset transfer**. Select dashboards, standalone questions or additional connection templates, then download `metabase-assets.yaml`. Referenced questions and databases are included. V1 places all selected assets in one ordinary collection; exports spanning multiple collections use an `Imported assets` destination.

Upload that same YAML file to another instance. Optionally bind database keys and the collection to existing target resources. Otherwise the importer creates them from YAML. It never adopts an unrelated object just because the names match. Review the preflight and execute the import. If metadata must be synchronized first, the preflight explicitly says that final question/dashboard validation will follow synchronization.

Database and collection creation are separate committed stages. If metadata synchronization or asset validation fails, these resources remain and are reused after fixing the problem and running preflight again. Questions and dashboards are imported atomically. Use the result recovery and event retry controls if the response was lost after commit.

## Document

```yaml
apiVersion: silinex.metabase/v1
kind: AssetSet
metadata:
  key: business-bi
spec:
  databases:
    - key: warehouse
      name: Business warehouse
      engine: postgres
      details:
        host: warehouse.internal
        port: 5432
        dbname: analytics
        user: metabase_reader
        password:
          valueFrom:
            env: BI_DATABASE_PASSWORD
  collections:
    - key: reports
      name: Business reports
  tables: []
  fields: []
  questions:
    - key: order-count
      name: Order count
      description: null
      display: scalar
      parameters: []
      visualization_settings: {}
      dataset_query:
        database: warehouse
        type: native
        native:
          query: |
            select count(*) from orders
          template-tags: {}
  dashboards: []
```

Use the exporter for dashboard layouts and physical query definitions. `tables` and `fields` are portable metadata descriptors; their numeric references are local to the document and resolved by schema/table/field name at the destination. Resource keys, including question and dashboard references, are stable strings. Names can be descriptive but are never used as resource identities.

Credentials are never exported. An exported unbound source has an environment-variable reference to fill in. A mounted secret can instead use `password: {valueFrom: {file: /run/secrets/warehouse-password}}`. Both upload and startup resolve references on the **server**. An explicit existing connection binding does not require the source password. Secret file values have one trailing newline removed. Plaintext passwords, arbitrary driver options, SSH configuration, aliases, merge keys, explicit YAML tags and duplicate keys are rejected.

## Startup

Mount the file and configure:

```text
MB_PROVISIONING_FILE=/etc/metabase/assets.yaml
MB_PROVISIONING_OWNER_EMAIL=bi-admin@example.com
MB_PROVISIONING_TIMEOUT_SECONDS=300
BI_DATABASE_PASSWORD=<injected through your existing Secret>
```

The owner must be an existing active administrator. Complete initial administrator setup before enabling provisioning on a fresh instance. The owner, timeout and secret values are runtime configuration and are not exported in the asset file.

Startup reads the file once after application database migrations and before readiness. It validates the file, creates missing resources, synchronizes metadata when required, and invokes the same importer as the admin page. Failure aborts initialization. The timeout is 300 seconds by default, configurable from 1 to 3600; give the deployment's startup probe a corresponding budget. No provisioning file means ordinary startup is unchanged.

Repeated startup inspects actual resource state, not just an old success record. Pending events are retried. Competing replicas serialize execution through the application database, while creation writes commit in short transactions. Warehouse connection checks and metadata discovery are read operations; provisioning does not execute the questions' business SQL.

## V1 boundaries

- Missing resource: create. Unchanged resource: skip. Changed definition, manual edit, deleted target or incompatible binding: conflict.
- Changes to file membership do not delete existing resources. Existing passwords and connection properties are not automatically updated.
- One collection, up to 100 databases, 100 questions and 20 dashboards; a YAML file is limited to 10 MiB and 80 nested levels.
- Existing simple SQL/single-table query and dashboard restrictions still apply: table/scalar/line/bar charts, tabs, layout, text/heading tiles and supported filters. No Models, joins, saved-question dependencies, native SQL field filters, actions or custom click behavior.
- Permissions, public sharing, subscriptions and embedding configuration are not transferred. Review destination access separately.
- No file watcher, periodic reconciliation, first administrator creation, automatic overwrite or automatic deletion.

## HTTP contract

All endpoints require a superuser. JSON is the HTTP envelope; the asset document remains YAML.

- `POST /api/asset-transfer/export` accepts `card_ids`, `dashboard_ids`, `database_ids`; returns `{yaml: string}`.
- `POST /api/asset-transfer/inspect` accepts `{yaml: string}`; returns the validated document for the upload UI.
- `POST /api/asset-transfer/import/preview` accepts `{yaml, database_bindings?, target_collection_id?}`. Binding keys are the YAML database keys. Returns `can_import`, `requires_sync`, resource/asset items and `plan_hash`.
- `POST /api/asset-transfer/import` adds `plan_hash` and `request_id`. A changed plan requires a new preflight. A committed request can be recovered through the existing result and retry-events endpoints.

The feature is independent of enterprise serialization/configuration-file support.
