import type {
  AssetTransferBundle,
  AssetTransferPlan,
  AssetTransferRequest,
  AssetTransferResult,
  ExecuteAssetTransferRequest,
} from "metabase-types/api/asset-transfer";

import { Api } from "./api";
import { listTag } from "./tags";

export const assetTransferApi = Api.injectEndpoints({
  endpoints: (builder) => ({
    inspectAssetFile: builder.mutation<AssetTransferBundle, { yaml: string }>({
      query: (body) => ({
        method: "POST",
        url: "/api/asset-transfer/inspect",
        body,
      }),
    }),
    exportAssets: builder.mutation<
      { yaml: string },
      { card_ids: number[]; database_ids: number[]; dashboard_ids?: number[] }
    >({
      query: (body) => ({
        method: "POST",
        url: "/api/asset-transfer/export",
        body,
      }),
    }),
    previewAssetImport: builder.mutation<
      AssetTransferPlan,
      AssetTransferRequest
    >({
      query: (body) => ({
        method: "POST",
        url: "/api/asset-transfer/import/preview",
        body,
      }),
    }),
    importAssets: builder.mutation<
      AssetTransferResult,
      ExecuteAssetTransferRequest
    >({
      query: (body) => ({
        method: "POST",
        url: "/api/asset-transfer/import",
        body,
      }),
      invalidatesTags: () => [
        listTag("database"),
        listTag("card"),
        listTag("dashboard"),
        listTag("collection"),
        "collection-tree",
      ],
    }),
    getAssetImport: builder.query<AssetTransferResult, string>({
      query: (id) => `/api/asset-transfer/imports/${encodeURIComponent(id)}`,
    }),
    retryAssetImportEvents: builder.mutation<AssetTransferResult, string>({
      query: (id) => ({
        method: "POST",
        url: `/api/asset-transfer/imports/${encodeURIComponent(id)}/retry-events`,
      }),
    }),
  }),
});

export const {
  useExportAssetsMutation,
  useInspectAssetFileMutation,
  usePreviewAssetImportMutation,
  useImportAssetsMutation,
  useLazyGetAssetImportQuery,
  useRetryAssetImportEventsMutation,
} = assetTransferApi;
