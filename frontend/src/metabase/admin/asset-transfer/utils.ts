import { t } from "ttag";

import type { AssetTransferBundle } from "metabase-types/api/asset-transfer";

export const MAX_BUNDLE_BYTES = 10 * 1024 * 1024;
export const LAST_IMPORT_KEY = "metabase.asset-transfer.last-request";

const CONNECTION_KEYS = new Set([
  "host",
  "port",
  "dbname",
  "db",
  "user",
  "ssl",
  "ssl-mode",
]);

/** Validate before pre-filling a connection form; full semantic validation is server-side. */
export function parseBundle(text: string): AssetTransferBundle {
  if (new TextEncoder().encode(text).length > MAX_BUNDLE_BYTES) {
    throw new Error(t`The file exceeds 10 MiB.`);
  }
  const value = JSON.parse(text);
  if (
    value?.format !== "metabase-asset-transfer" ||
    value.format_version !== 1 ||
    !Array.isArray(value.databases) ||
    !Array.isArray(value.cards) ||
    !Array.isArray(value.tables) ||
    !Array.isArray(value.fields) ||
    value.cards.length > 100 ||
    value.databases.length > 100
  ) {
    throw new Error(
      t`Choose a version 1 asset transfer file with at most 100 questions.`,
    );
  }
  for (const database of value.databases) {
    if (
      !database ||
      !Number.isSafeInteger(database.id) ||
      database.id <= 0 ||
      typeof database.name !== "string" ||
      !["postgres", "mysql", "clickhouse"].includes(database.engine) ||
      !database.details ||
      typeof database.details !== "object" ||
      Array.isArray(database.details) ||
      Object.entries(database.details).some(
        ([key, detail]) =>
          !CONNECTION_KEYS.has(key) ||
          !["string", "number", "boolean"].includes(typeof detail),
      )
    ) {
      throw new Error(
        t`The file contains an unsupported or sensitive connection option.`,
      );
    }
  }
  return value;
}

export function downloadBundle(bundle: AssetTransferBundle) {
  const url = URL.createObjectURL(
    new Blob([JSON.stringify(bundle, null, 2)], { type: "application/json" }),
  );
  const link = document.createElement("a");
  link.href = url;
  link.download = "metabase-assets.json";
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
