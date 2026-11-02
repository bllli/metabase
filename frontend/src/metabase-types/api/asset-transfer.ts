export type TransferDatabase = {
  id: number;
  name: string;
  engine: "postgres" | "mysql" | "clickhouse";
  details: Record<string, string | number | boolean>;
};

export type AssetTransferBundle = {
  format: "metabase-asset-transfer";
  format_version: 1;
  producer: { metabase_version: string; transfer_version: 1 };
  databases: TransferDatabase[];
  tables: Record<string, unknown>[];
  fields: Record<string, unknown>[];
  cards: { entity_id: string; name: string; [key: string]: unknown }[];
};

export type AssetTransferRequest = {
  bundle: AssetTransferBundle;
  database_bindings: Record<string, number>;
  target_collection_id: number;
};

export type AssetTransferError = {
  code: string;
  message: string;
  path?: (string | number)[];
  object_ref?: string;
};

export type AssetTransferItem = {
  source_entity_id: string;
  name: string;
  action: "create" | "skip" | "conflict" | "created" | "skipped";
  card_id?: number;
  error?: AssetTransferError;
  event_delivered?: boolean;
};

export type AssetTransferPlan = {
  can_import: boolean;
  plan_hash?: string;
  items: AssetTransferItem[];
  errors?: AssetTransferError[];
};

export type ExecuteAssetTransferRequest = AssetTransferRequest & {
  plan_hash: string;
  request_id: string;
};

export type AssetTransferResult = {
  request_id: string;
  status: "complete" | "events_pending";
  items: AssetTransferItem[];
};
