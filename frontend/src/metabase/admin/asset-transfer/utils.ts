import { t } from "ttag";

export const MAX_BUNDLE_BYTES = 10 * 1024 * 1024;
export const LAST_IMPORT_KEY = "metabase.asset-transfer.last-request";

export function checkFileSize(text: string): string {
  if (new TextEncoder().encode(text).length > MAX_BUNDLE_BYTES) {
    throw new Error(t`The file exceeds 10 MiB.`);
  }
  return text;
}

export function downloadBundle({ yaml }: { yaml: string }) {
  const url = URL.createObjectURL(
    new Blob([yaml], { type: "application/yaml" }),
  );
  const link = document.createElement("a");
  link.href = url;
  link.download = "metabase-assets.yaml";
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
