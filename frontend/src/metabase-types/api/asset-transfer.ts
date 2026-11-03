export type TransferDatabase = {
  key: string;
  name: string;
  engine: "postgres" | "mysql" | "clickhouse";
  details: Record<
    string,
    string | number | boolean | { valueFrom: { env?: string; file?: string } }
  >;
};

export type AssetTransferBundle = {
  apiVersion: "silinex.metabase/v1";
  kind: "AssetSet";
  metadata: { key: string };
  spec: {
    databases: TransferDatabase[];
    collections: { key: string; name: string }[];
    tables: Record<string, unknown>[];
    fields: Record<string, unknown>[];
    questions: { key: string; name: string; [key: string]: unknown }[];
    dashboards: { key: string; name: string; [key: string]: unknown }[];
  };
};

export type AssetTransferRequest = {
  yaml: string;
  database_bindings?: Record<string, number>;
  target_collection_id?: number;
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
  action: "create" | "bind" | "skip" | "conflict" | "created" | "skipped";
  card_id?: number;
  dashboard_id?: number;
  model?: "card" | "dashboard" | "database" | "collection";
  target_id?: number;
  error?: AssetTransferError;
  event_delivered?: boolean;
};

export type AssetTransferPlan = {
  can_import: boolean;
  requires_sync?: boolean;
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
