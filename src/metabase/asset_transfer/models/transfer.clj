(ns metabase.asset-transfer.models.transfer
  (:require
   [metabase.models.interface :as mi]
   [methodical.core :as methodical]
   [toucan2.core :as t2]))

(methodical/defmethod t2/table-name :model/AssetTransferImport [_] :asset_transfer_import)
(methodical/defmethod t2/table-name :model/AssetTransferBinding [_] :asset_transfer_binding)

(derive :model/AssetTransferImport :metabase/model)
(derive :model/AssetTransferBinding :metabase/model)

(t2/deftransforms :model/AssetTransferImport
  {:result mi/transform-json})

(methodical/defmethod t2/table-name :model/AssetTransferDashboardBinding [_] :asset_transfer_dashboard_binding)
(derive :model/AssetTransferDashboardBinding :metabase/model)
