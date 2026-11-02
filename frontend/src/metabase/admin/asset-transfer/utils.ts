import { t } from "ttag";

import type { AssetTransferBundle } from "metabase-types/api/asset-transfer";
import { isAssetTransferBundle } from "metabase-types/guards/asset-transfer";

export const MAX_BUNDLE_BYTES = 10 * 1024 * 1024;
export const LAST_IMPORT_KEY = "metabase.asset-transfer.last-request";

/** Validate before pre-filling a connection form; full semantic validation is server-side. */
export function parseBundle(text: string): AssetTransferBundle {
  if (new TextEncoder().encode(text).length > MAX_BUNDLE_BYTES) {
    throw new Error(t`The file exceeds 10 MiB.`);
  }
  const value: unknown = JSON.parse(text);
  if (!isAssetTransferBundle(value)) {
    throw new Error(
      t`Choose a version 1 or 2 asset transfer file with at most 100 questions and 20 dashboards, without sensitive connection options.`,
    );
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
