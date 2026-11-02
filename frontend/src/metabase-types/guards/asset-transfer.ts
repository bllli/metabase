import type { AssetTransferBundle } from "metabase-types/api/asset-transfer";

import { isObject } from "./common";

const CONNECTION_KEYS = new Set([
  "host",
  "port",
  "dbname",
  "db",
  "user",
  "ssl",
  "ssl-mode",
]);

function isDefinition(value: unknown): boolean {
  return (
    isObject(value) &&
    !Array.isArray(value) &&
    typeof value.entity_id === "string" &&
    typeof value.name === "string"
  );
}

function isDatabase(value: unknown): boolean {
  if (
    !isObject(value) ||
    Array.isArray(value) ||
    typeof value.id !== "number" ||
    !Number.isSafeInteger(value.id) ||
    value.id <= 0 ||
    typeof value.name !== "string" ||
    (value.engine !== "postgres" &&
      value.engine !== "mysql" &&
      value.engine !== "clickhouse") ||
    !isObject(value.details) ||
    Array.isArray(value.details)
  ) {
    return false;
  }
  return Object.entries(value.details).every(
    ([key, detail]) =>
      CONNECTION_KEYS.has(key) &&
      (typeof detail === "string" ||
        typeof detail === "number" ||
        typeof detail === "boolean"),
  );
}

/** Structural boundary for the upload UI; reference and option validation runs on the server. */
export function isAssetTransferBundle(
  value: unknown,
): value is AssetTransferBundle {
  if (
    !isObject(value) ||
    value.format !== "metabase-asset-transfer" ||
    (value.format_version !== 1 && value.format_version !== 2) ||
    !isObject(value.producer) ||
    typeof value.producer.metabase_version !== "string" ||
    value.producer.transfer_version !== value.format_version
  ) {
    return false;
  }
  const databases: unknown = value.databases;
  const cards: unknown = value.cards;
  const dashboards: unknown = value.dashboards;
  const tables: unknown = value.tables;
  const fields: unknown = value.fields;
  return (
    Array.isArray(databases) &&
    databases.length <= 100 &&
    databases.every(isDatabase) &&
    Array.isArray(cards) &&
    cards.length <= 100 &&
    cards.every(isDefinition) &&
    Array.isArray(tables) &&
    tables.every((item: unknown) => isObject(item) && !Array.isArray(item)) &&
    Array.isArray(fields) &&
    fields.every((item: unknown) => isObject(item) && !Array.isArray(item)) &&
    (value.format_version === 1
      ? dashboards === undefined
      : Array.isArray(dashboards) &&
        dashboards.length <= 20 &&
        dashboards.every(isDefinition))
  );
}
