import { useRef, useState } from "react";
import { t } from "ttag";

import { useListCardsQuery, useListDatabasesQuery } from "metabase/api";
import {
  useExportAssetsMutation,
  useImportAssetsMutation,
  useLazyGetAssetImportQuery,
  usePreviewAssetImportMutation,
  useRetryAssetImportEventsMutation,
} from "metabase/api/asset-transfer";
import { getErrorMessage } from "metabase/api/utils/errors";
import { CollectionPickerModal } from "metabase/common/components/Pickers/CollectionPicker";
import { Link } from "metabase/router";
import {
  Alert,
  Box,
  Button,
  Divider,
  FileInput,
  Group,
  Modal,
  MultiSelect,
  Select,
  Stack,
  Text,
  Title,
} from "metabase/ui";
import type {
  AssetTransferBundle,
  AssetTransferItem,
  AssetTransferPlan,
  AssetTransferResult,
  ExecuteAssetTransferRequest,
  TransferDatabase,
} from "metabase-types/api/asset-transfer";

import { DatabaseEditConnectionForm } from "../databases/components/DatabaseEditConnectionForm";

import {
  LAST_IMPORT_KEY,
  MAX_BUNDLE_BYTES,
  downloadBundle,
  parseBundle,
} from "./utils";

function ItemList({ items }: { items: AssetTransferItem[] }) {
  const labels = {
    create: t`Create`,
    skip: t`Skip`,
    conflict: t`Conflict`,
    created: t`Created`,
    skipped: t`Skipped`,
  };
  return (
    <Stack gap="xs">
      {items.map((item) => (
        <Box key={item.source_entity_id}>
          <Text>
            {item.name}: {labels[item.action]}
          </Text>
          {item.error && <Alert color="error">{item.error.message}</Alert>}
          {item.card_id &&
            (item.action === "created" || item.action === "skipped") && (
              <Link to={`/question/${item.card_id}`}>{t`Open question`}</Link>
            )}
        </Box>
      ))}
    </Stack>
  );
}

export function AssetTransferPage() {
  const cards = useListCardsQuery({ f: "all" });
  const databases = useListDatabasesQuery();
  const [exportAssets, exportState] = useExportAssetsMutation();
  const [previewImport, previewState] = usePreviewAssetImportMutation();
  const [importAssets, importState] = useImportAssetsMutation();
  const [getResult, recoveryState] = useLazyGetAssetImportQuery();
  const [retryEvents, retryEventsState] = useRetryAssetImportEventsMutation();
  const [cardIds, setCardIds] = useState<string[]>([]);
  const [databaseIds, setDatabaseIds] = useState<string[]>([]);
  const [file, setFile] = useState<File | null>(null);
  const [bundle, setBundle] = useState<AssetTransferBundle>();
  const [bindings, setBindings] = useState<Record<string, number>>({});
  const [collection, setCollection] = useState<{ id: number; name: string }>();
  const [pickingCollection, setPickingCollection] = useState(false);
  const [newDatabase, setNewDatabase] = useState<TransferDatabase>();
  const [plan, setPlan] = useState<AssetTransferPlan>();
  const [attempt, setAttempt] = useState<ExecuteAssetTransferRequest>();
  const [result, setResult] = useState<AssetTransferResult>();
  const [error, setError] = useState<string>();
  const [lastRequest, setLastRequest] = useState(() =>
    sessionStorage.getItem(LAST_IMPORT_KEY),
  );
  const revision = useRef(0);
  const busy =
    previewState.isLoading ||
    importState.isLoading ||
    recoveryState.isFetching ||
    retryEventsState.isLoading;

  const invalidate = () => {
    revision.current++;
    setPlan(undefined);
    setAttempt(undefined);
    setResult(undefined);
    setError(undefined);
  };

  const readFile = async (next: File | null) => {
    invalidate();
    const generation = revision.current;
    setFile(next);
    setBundle(undefined);
    setBindings({});
    if (!next) {
      return;
    }
    try {
      if (next.size > MAX_BUNDLE_BYTES) {
        throw new Error(t`The file exceeds 10 MiB.`);
      }
      const value = parseBundle(await next.text());
      if (revision.current === generation) {
        setBundle(value);
      }
    } catch (e) {
      if (revision.current === generation) {
        setError(getErrorMessage(e));
      }
    }
  };

  const runPreview = async () => {
    if (!bundle || !collection) {
      return;
    }
    setError(undefined);
    setPlan(undefined);
    setAttempt(undefined);
    setResult(undefined);
    const generation = revision.current;
    try {
      const next = await previewImport({
        bundle,
        database_bindings: bindings,
        target_collection_id: collection.id,
      }).unwrap();
      if (generation === revision.current) {
        setPlan(next);
      }
    } catch (e) {
      setError(getErrorMessage(e));
    }
  };

  const execute = async () => {
    if (!bundle || !collection || !plan?.can_import || !plan.plan_hash) {
      return;
    }
    const request = attempt ?? {
      bundle,
      database_bindings: bindings,
      target_collection_id: collection.id,
      plan_hash: plan.plan_hash,
      request_id: crypto.randomUUID(),
    };
    setAttempt(request);
    setLastRequest(request.request_id);
    sessionStorage.setItem(LAST_IMPORT_KEY, request.request_id);
    setError(undefined);
    try {
      setResult(await importAssets(request).unwrap());
    } catch (e) {
      setError(getErrorMessage(e));
    }
  };

  return (
    <Stack p="xl" maw={960} gap="lg">
      <Title order={1}>{t`Asset transfer`}</Title>
      <Text>{t`Move standalone questions and connection templates between instances running the same Metabase version. Existing questions are never overwritten.`}</Text>
      <Text c="text-secondary">{t`Version 1 supports PostgreSQL, MySQL, ClickHouse, simple SQL and single-table queries, and table, number, line and bar charts. Dashboards, models, joins, saved-question references and field filters are not supported.`}</Text>
      {!!(error || cards.error || databases.error) && (
        <Alert color="error" role="alert">
          {error ?? getErrorMessage(cards.error ?? databases.error)}
        </Alert>
      )}

      <Title order={2}>{t`Export`}</Title>
      <MultiSelect
        label={t`Questions`}
        searchable
        clearable
        value={cardIds}
        onChange={setCardIds}
        data={(cards.data ?? [])
          .filter(
            (card) =>
              card.type === "question" &&
              !card.dashboard_id &&
              !card.document_id,
          )
          .map((card) => ({
            value: String(card.id),
            label: `${card.name} (#${card.id})`,
          }))}
      />
      <MultiSelect
        label={t`Additional connection templates`}
        searchable
        clearable
        value={databaseIds}
        onChange={setDatabaseIds}
        data={(databases.data?.data ?? [])
          .filter((db) =>
            ["postgres", "mysql", "clickhouse"].includes(db.engine ?? ""),
          )
          .map((db) => ({ value: String(db.id), label: db.name }))}
      />
      <Text size="sm">{t`Referenced connection templates are included automatically. Passwords, SSH keys and query results are excluded.`}</Text>
      <Button
        loading={exportState.isLoading}
        disabled={
          (!cardIds.length && !databaseIds.length) || cardIds.length > 100
        }
        onClick={async () => {
          setError(undefined);
          try {
            downloadBundle(
              await exportAssets({
                card_ids: cardIds.map(Number),
                database_ids: databaseIds.map(Number),
              }).unwrap(),
            );
          } catch (e) {
            setError(getErrorMessage(e));
          }
        }}
      >{t`Download transfer file`}</Button>

      <Divider />
      <Title order={2}>{t`Import`}</Title>
      <FileInput
        label={t`Transfer file`}
        fileInputProps={{ "aria-label": t`Upload transfer file` }}
        accept="application/json,.json"
        clearable
        value={file}
        onChange={readFile}
        disabled={busy}
      />
      {bundle && (
        <>
          <Text>{t`${bundle.cards.length} questions in this file`}</Text>
          {bundle.databases.map((source) => (
            <Stack key={source.id} gap="xs">
              <Select
                label={t`Target connection for ${source.name}`}
                searchable
                clearable
                disabled={busy}
                value={bindings[source.id] ? String(bindings[source.id]) : null}
                data={(databases.data?.data ?? [])
                  .filter((db) => db.engine === source.engine && !db.is_stub)
                  .map((db) => ({
                    value: String(db.id),
                    label: `${db.name} (#${db.id})`,
                  }))}
                onChange={(value) => {
                  invalidate();
                  setBindings((previous) => {
                    const next = { ...previous };
                    if (value) {
                      next[source.id] = Number(value);
                    } else {
                      delete next[source.id];
                    }
                    return next;
                  });
                }}
              />
              <Button
                variant="subtle"
                disabled={busy}
                onClick={() => setNewDatabase(source)}
              >{t`Create a connection from this template`}</Button>
            </Stack>
          ))}
          <Alert>{t`New connections are saved separately and remain if question import fails. Finish metadata synchronization before preflight. SQL text is preserved; verify that names written in SQL exist in the target database.`}</Alert>
          {bundle.cards.length > 0 ? (
            <>
              <Button
                disabled={busy}
                onClick={() => setPickingCollection(true)}
              >
                {collection?.name ?? t`Choose destination collection`}
              </Button>
              <Button
                disabled={
                  busy ||
                  !collection ||
                  bundle.databases.some((db) => !bindings[db.id])
                }
                loading={previewState.isLoading}
                onClick={runPreview}
              >{t`Run preflight`}</Button>
            </>
          ) : (
            <Text>{t`This file contains connection templates only. Create or bind the connections above; no question import is needed.`}</Text>
          )}
        </>
      )}
      {plan && (
        <>
          {plan.errors?.map((item, index) => (
            <Alert key={index} color="error">
              {item.code}: {item.message}
              {item.path && <Text size="sm">{item.path.join(" / ")}</Text>}
            </Alert>
          ))}
          <ItemList items={plan.items} />
          <Button
            disabled={!plan.can_import || busy || result?.status === "complete"}
            loading={importState.isLoading}
            onClick={execute}
          >
            {attempt ? t`Retry this import` : t`Import definitions`}
          </Button>
        </>
      )}
      {lastRequest && (
        <Group>
          <Text size="sm">{t`Last request: ${lastRequest}`}</Text>
          <Button
            variant="subtle"
            disabled={busy}
            onClick={async () => {
              setError(undefined);
              try {
                setResult(await getResult(lastRequest, false).unwrap());
              } catch (e) {
                setError(
                  getErrorMessage(
                    e,
                    t`No committed result yet. Retry the original import.`,
                  ),
                );
              }
            }}
          >{t`Recover result`}</Button>
        </Group>
      )}
      {result && (
        <>
          <Alert color={result.status === "complete" ? "success" : "warning"}>
            {result.status === "complete"
              ? t`Definitions imported. Open the questions to verify their results.`
              : t`Definitions are committed, but creation events are pending. Retry event delivery to finish without creating duplicates.`}
          </Alert>
          {result.status === "events_pending" && (
            <Button
              disabled={busy}
              loading={retryEventsState.isLoading}
              onClick={async () => {
                setError(undefined);
                try {
                  setResult(await retryEvents(result.request_id).unwrap());
                } catch (e) {
                  setError(getErrorMessage(e));
                }
              }}
            >{t`Retry event delivery`}</Button>
          )}
          <ItemList items={result.items} />
        </>
      )}

      {pickingCollection && (
        <CollectionPickerModal
          title={t`Choose destination collection`}
          value={{ id: collection?.id ?? "root", model: "collection" }}
          onClose={() => setPickingCollection(false)}
          options={{ hasRootCollection: false, hasPersonalCollections: false }}
          onChange={(item) => {
            if (item.model === "collection" && typeof item.id === "number") {
              invalidate();
              setCollection({ id: item.id, name: item.name });
              setPickingCollection(false);
            }
          }}
        />
      )}
      <Modal
        opened={!!newDatabase}
        onClose={() => setNewDatabase(undefined)}
        title={t`Create target connection`}
        size="xl"
      >
        {newDatabase && (
          <DatabaseEditConnectionForm
            key={newDatabase.id}
            database={{
              name: newDatabase.name,
              engine: newDatabase.engine,
              details: newDatabase.details,
            }}
            isAttachedDWH={false}
            config={{ engine: { fieldState: "disabled" } }}
            formLocation="admin"
            onCancel={() => setNewDatabase(undefined)}
            onSubmitted={({ id }) => {
              invalidate();
              setBindings((previous) => ({
                ...previous,
                [newDatabase.id]: id,
              }));
              setNewDatabase(undefined);
              databases.refetch();
            }}
          />
        )}
      </Modal>
    </Stack>
  );
}
