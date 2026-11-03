# Unified YAML assets and startup provisioning

Status: implementation and local validation complete. Scope approved 2026-11-03. Private OSS build; no legacy file compatibility required. Usage and configuration: [YAML assets](yaml-assets.md).

## Contract and scope

- One `silinex.metabase/v1` / `AssetSet` YAML document serves export, manual import and startup provisioning.
- Stable resource keys identify databases, collections, questions and dashboards; display names are not identities. Physical table/field references resolve through portable metadata descriptors.
- Export includes referenced questions, dashboards, connection templates and collections. Credentials never leave the server. Secret references support environment variables and mounted files.
- The shared planner/executor creates missing resources, skips unchanged resources and rejects drift. No automatic overwrite, deletion, password rotation or first administrator creation.
- Startup requires an existing active administrator, reads `MB_PROVISIONING_FILE`, and fails before readiness on invalid configuration, conflicts, failed metadata sync or timeout. No file means no change to normal startup.
- Manual import exposes the same plan; explicit existing-resource bindings are supported. HTTP may carry JSON, but the only public asset file format is YAML. Old JSON bundle/version branches are removed.
- Format compatibility uses `apiVersion`, not exact application build version. Query shapes remain strictly validated.

## Implementation sequence

1. Define safe YAML parsing/emission and the single document schema. Reject duplicate keys, aliases, unsupported tags, oversized/deep documents and unknown fields. Preserve SQL/Markdown literals.
2. Adapt export to stable references and round-trip identities. Refactor existing bounded card/dashboard rewrite and validation into the shared document pipeline.
3. Add durable bindings for databases/collections, shared connection creation, secret resolution and metadata synchronization. Keep network operations outside resource transactions. Use cluster locks plus unique constraints for concurrent starts.
4. Add shared preview/apply execution, resumable creation/event delivery and startup invocation before initialization completes. Always inspect current resources instead of trusting a prior request result.
5. Switch admin upload/download and API types to YAML; expose resource plans and explicit bindings. Remove old JSON protocol UI/tests.
6. Cover YAML safety, export/import, startup/restart, concurrency, drift, failure recovery, secret redaction and version independence. Run targeted frontend/backend checks, migrations and browser verification where available.

## Implementation boundaries

- Reuse `asset_transfer` query/layout rewriting, hashes, import journal and collection locking.
- Add YAML and resource/provisioning modules inside the existing feature. Use installed YAML libraries; no new parser dependency.
- Register new binding migration/models and module declarations; retain existing stored transfer tables where useful.
- Preserve database create permissions/model hooks and event handling. Provisioning only performs connection checks/metadata discovery, never evaluates dashboard business queries.
- Single ordinary destination collection per file in v1; referenced assets are placed there. Existing collection/database bindings are optional runtime overrides, never guessed from names.
- Keep credentials out of plans, logs and binding rows. Explicit bindings do not need source credentials.
- Core asset import remains atomic; connection/collection creation and metadata sync are recoverable earlier stages, not one cross-system transaction.

## Acceptance

- Export dashboard with tabs, filters, owned questions and text tiles; manually import YAML into a separate application database and render it.
- Initialize from that same YAML on boot; repeat boot without duplicate resources.
- Reject changed source/target resources and missing references before creating cards/dashboard shells.
- Two concurrent executions create one binding/object per key. Retry after partial resource creation converges.
- YAML errors and unavailable secrets fail without secret/query text in errors; no unsafe YAML evaluation.
- Different application build labels do not block a compatible v1 document.
- Startup failures do not mark the instance ready. Disabled provisioning leaves startup unchanged.

## Validation record

Validation completed locally on 2026-11-03:

- H2 backend regression: `./bin/test-agent --oss :only '[metabase.asset-transfer.assets-test metabase.asset-transfer.yaml-test metabase.asset-transfer.dashboard-test metabase.asset-transfer.core-test metabase.asset-transfer.schema-test metabase.sync.events.sync-database-test metabase.app-db.schema-migrations-test/yaml-resource-bindings-test metabase.core.modules-test]'` — **57 tests, 8720 assertions, zero failures/errors**. Includes migration rollback/reapply, concurrent provisioning, drift, partial failure/event recovery and YAML rejection cases.
- PostgreSQL application database: the assets/dashboard test namespaces with `MB_DB_TYPE=postgres` and `DRIVERS=postgres` against the dedicated local test database — **15 tests, 82 assertions, zero failures/errors**. Connection checks and metadata discovery are stubbed in the provisioning unit tests; the browser/startup checks below use a real PostgreSQL warehouse.
- Frontend Jest tests for `AssetTransferPage` and `utils` — **2 suites, 7 tests passed**. Development frontend bundle built successfully with Rspack.
- Browser: exported `metabase-assets.yaml` from a source instance, uploaded it to a separate target, created the connection/collection/question/dashboard and opened the dashboard. The chart rendered; Category = Books returned 200; Revenue/Notes tabs and the text tile were preserved.
- Startup: used the exact browser-exported YAML on a third application database with an existing administrator. A failed creation event prevented readiness; after the event-handler fix, restart recovered the already-created resources. A second successful boot kept database/collection/question/dashboard IDs unchanged and preflight reported **skip** for every resource.
- Fixed two issues found during validation: the audit event handler now removes the internal skip-auto-sync flag before validating its event payload; native SQL definitions normalize empty template tags for stable export/import round trips.
- All changed Clojure files pass readability checks. Module configuration is up to date; migration lint and `git diff --check` pass. Kondo reports **zero errors and zero warnings** after fixing namespace order; its existing malformed `metabase.lib.expression` inline-cache diagnostic is unrelated to this change.
- `type-check-pure` remains blocked by two existing, unrelated kbar callback type errors in `DashboardTabs.tsx:53` and `AdminNavbar.tsx:41`; no asset-transfer type errors were reported.

Local evidence is under ignored `local/yaml-assets/`: `backend-final.log`, `postgres-tests.log`, `frontend-tests.log`, `frontend-build.log`, `typecheck.log`, `migration-lint-final.log`, `module-final.log`, `boot-verified-first.json`, `boot-verified-second.json`, `dashboard-filter.png` and `dashboard-notes.png`. These runtime fixtures and credentials are not committed.

Limits of validation: no live MySQL application database or Kubernetes rollout was exercised. Concurrent execution was tested against H2 and PostgreSQL; no multi-pod deployment test was run. The full repository test suite was not run. No commit, image build/push or deployment is part of this task.

## Subsequent image build and handoff

On 2026-11-03, an authorized local amd64 image build reused BuildKit caches and completed ClojureScript compilation. Production JavaScript bundling exhausted the 6 GiB Docker VM's memory and the Docker connection was lost; no completed image was produced or pushed. The remaining build process was stopped. The user then requested committing the implementation and moving the image build to a server. The intended image tag is `r.inner.silinex.work/silinex/oss/metabase:20261103-amd64`; `Dockerfile.tokenplus` remains unchanged.
