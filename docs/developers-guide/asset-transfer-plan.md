# 开源版分析资产迁移：实现计划

基线：`52f0501383`。目标是在开源版内独立实现，不依赖企业版 Serialization。

## 第一版范围

- 仅管理员；相同 Metabase 版本和迁移协议版本。
- JSON 导出包，最大 10 MiB、100 张独立 Question。
- PostgreSQL、MySQL、ClickHouse 非敏感连接模板；补凭据后复用现有连接创建接口，或绑定已有同类型数据源。
- 普通 SQL（文本、数字、日期模板参数）；单物理表可视化查询（字段、筛选、聚合、分组、排序、数量限制）。
- 表格、数字、折线、柱状图及明确支持的样式。
- 导入到已有普通 Collection；新增、幂等跳过、冲突阻止。
- 不支持 Dashboard/Model/Metric/Snippet、Question 依赖、Join、多阶段、自定义表达式、Field Filter、跨对象交互、覆盖更新、权限/订阅/数据迁移。未知配置显式拒绝。

## 协议与接口

包包含 format、format_version、producer、databases、tables、fields、cards。源数字 ID 只作为包内引用键，不是目标 ID；表字段按目标数据源/schema/真实名称解析，并核对 base_type/effective_type。协议固定使用 legacy MBQL JSON；读写 Card 时通过现有 lib 转换，不依赖内部多阶段查询的 UUID。

SQL 正文不改写，也不执行；SQL 中硬编码的库名、schema、表名需在目标环境可用。连接详情仅允许 host、port、dbname、db、user、ssl、ssl-mode，不携带密码、SSH、连接串、JDBC 参数或任意驱动配置。SQL 正文和参数默认值由用户编写，可能自身包含敏感字面量，导出文件仍应按业务配置文件管理。

- POST /api/asset-transfer/export：card_ids、database_ids -> JSON 包。
- POST /api/asset-transfer/import/preview：bundle、database_bindings、target_collection_id -> plan_hash、逐项动作和错误。
- POST /api/asset-transfer/import：同上及 plan_hash、request_id -> 执行结果。
- GET /api/asset-transfer/imports/:request-id：网络重试恢复结果。
- POST /api/asset-transfer/imports/:request-id/retry-events：重试已提交批次的创建事件；刷新页面后无需重新上传文件，不重新创建 Question。

预检查不执行查询；执行时重新校验权限、目标元数据、连接更新时间和 plan_hash。图表、映射、成功记录同事务写入。创建流程禁止执行查询获取结果元数据，事件提交后发布。数据源创建是独立步骤，不随图表事务回滚；现有创建流程会验证连接并启动同步，用户应等待同步完成后预检查。

### 请求及返回约定

- `database_bindings` 是源数据库 ID 字符串到目标数据库数字 ID 的映射，例如 `{"1": 12}`，必须覆盖包内所有数据库，不能有额外条目。
- `target_collection_id` 必须是已有、未归档的普通 Collection；不接受根目录、个人目录、远程同步目录或其他特殊命名空间。
- 预检查返回 `can_import`、`plan_hash`、`items`。每项动作为 `create`、`skip` 或 `conflict`；结构/元数据错误返回 `errors`，阻止整批导入。
- 执行沿用相同请求内容，新增 64 位 `plan_hash` 和稳定的 `request_id`（页面生成 UUID）。网络失败重试沿用原键；相同键不同内容返回 409。
- 成功返回 `request_id`、`status`、`items`，每项包含目标 `card_id`，动作为 `created` 或 `skipped`。`status` 为 `complete` 或 `events_pending`。
- 目标在预检查后改变、映射冲突时返回 409；不存在的导入记录返回 404；所有接口均检查管理员身份。

### ID、冲突及事务规则

1. 按查询语法位置转换数据库、表和字段 ID；不会遍历替换任意数字，不修改筛选值和聚合索引。图表 `table.columns` 与 JSON 编码的 `column_settings` 中字段引用也转换。
2. 导入创建新的 Card/entity_id；独立映射表记录源 entity_id，不将源数据库主键写入目标对象。
3. 映射以“源 entity_id + 目标 Collection + 目标数据库”为唯一键。源定义、已导入目标定义均未改变时跳过；源或目标改变、目标被删除/移动/归档时冲突，第一版不覆盖。
   现有图表编辑器保存改名时也可能持久化默认显示设置，因此仅改回原名称不一定能消除冲突；第一版按完整受管定义判断。
4. 锁定目标 Collection 串行处理同一目录的导入，配合请求唯一约束和映射唯一约束防止并发重复创建。
5. 创建事件在图表事务提交后发送，逐批记录完成状态；允许至少一次投递。事件失败不回滚已提交图表，可从结果页重试补发。
6. 历史表保存请求摘要、对象 ID、名称、动作和事件状态，不保存上传包、SQL 或连接详情。映射故意不随目标删除级联清理，以便后续导入识别删除冲突。

## 模块

- 新增 asset-transfer：协议/白名单、引用转换、导出、计划、导入及两张记录表。
- 新增 asset-transfer-rest：管理员 API；注册 api-routes 和模块边界。
- queries：为内部导入提供不执行查询的创建选项，保持普通保存默认行为。
- frontend：admin/asset-transfer 页面、管理路由/导航、RTK Query API、类型；复用现有数据库连接表单。
- migrations：asset_transfer_import（请求/结果/事件状态）、asset_transfer_binding（源标识+目标 Collection/数据源唯一映射及定义摘要）。

具体文件：

| 模块 | 文件 | 改动 |
| --- | --- | --- |
| 协议 | `src/metabase/asset_transfer/schema.clj` | 版本/大小/数量限制、查询和样式白名单、精确引用转换、摘要 |
| 服务 | `src/metabase/asset_transfer/core.clj`、`db.clj` | 导出、目标元数据解析、预检查、事务、幂等、结果与事件恢复 |
| 模型 | `src/metabase/asset_transfer/models/transfer.clj` | 两个新 Toucan 模型 |
| HTTP | `src/metabase/asset_transfer_rest/api.clj`、`src/metabase/api_routes/routes.clj` | 五个管理员接口及路由注册 |
| 既有创建逻辑 | `src/metabase/queries/models/card.clj` | 新增仅内部使用的 skip-query-metadata? 选项，默认保存逻辑保持原行为 |
| 迁移 | `resources/migrations/2026/20261102_asset_transfer.yaml` | 两张表、两个唯一约束、四个可回滚 changeset；源标识使用区分大小写的 entity_id.type |
| 管理页面 | `frontend/src/metabase/admin/asset-transfer/` | 导出、文件校验、绑定、创建连接、Collection 选择、预检查、执行和恢复 |
| 前端 API/类型 | `frontend/src/metabase/api/asset-transfer.ts`、`frontend/src/metabase-types/api/asset-transfer.ts` | RTK Query hooks 和协议类型 |
| 管理入口 | `frontend/src/metabase/admin/routes.tsx`、`admin/app/reducers.ts`、`redux/store/admin.ts` | 管理路由、导航及类型 |
| 模块边界 | `.clj-kondo/config/modules/config.edn` | 注册两个 OSS 模块及公开接口 |
| 企业版兼容 | `enterprise/backend/src/metabase_enterprise/serialization/v2/models.clj` | 仅将两张运行记录表排除出企业版 Serialization；OSS 模块不引用企业版代码 |

## 执行顺序

- [x] 阅读现有创建、权限、元数据、路由和管理界面实现。
- [x] 写入本计划。
- [x] 实现协议、支持范围校验和引用转换，补纯函数测试。
- [x] 实现导出、目标解析、预检查和对应 API。
- [x] 实现迁移表、无查询创建、事务及幂等导入。
- [x] 接入管理页面、连接表单和错误/执行报告。
- [x] 完成针对性测试、模块检查、静态检查，更新执行记录（全量类型检查的既有问题见下文）。

## 验收

1. 源目标 ID 完全不同仍正确映射；数字筛选值不被重写。
2. 不支持的结构、缺字段、歧义和类型不匹配在写入前阻止。
3. 非管理员拒绝；连接模板白名单不泄露凭据。
4. 重复/并发提交不重复创建；相同请求键不同输入冲突。
5. 中途异常整批回滚；提交后事件失败不重复创建，保留可恢复状态。
6. 预检查后目标变化返回 409；目标手工修改不得覆盖。
7. 导入不执行 SQL 获取结果元数据，原普通创建流程不变。
8. 管理页完成导出、绑定、新建连接、预检查、执行及结果恢复。
9. 开源版独立加载；测试与本机限制如实记录，不将静态检查视为运行验收。

## 执行记录

- 本地安装仓库锁定的前端依赖并准备 JDK 23/Clojure 工具链，未修改依赖声明或锁文件。
- ClojureScript `app` 编译通过（490 个文件，0 编译警告）。
- 迁移文件通过仓库 lint；H2 迁移创建、唯一约束、回滚、重新执行已通过。
- 后端：17 个测试、89 项断言通过。覆盖纯协议、连接字段脱敏、查询/样式引用转换、物理表字段匹配、普通保存行为、无查询导入、事务回滚、同请求并发重试、源/目标冲突、事件补发、HTTP 全流程、非管理员拒绝以及迁移回滚后重建。
- 功能前端：10 个测试通过。覆盖文件边界和连接字段校验、冲突禁用执行、改绑定失效预检查、失败重试沿用请求 ID、刷新后仅凭请求 ID 恢复和补发事件。
- 管理入口：7 个现有路由/导航测试通过；新增导航项放在末尾，保持已有数字快捷键顺序。
- clj-kondo 检查新增模块、接口、测试及迁移测试：0 errors、0 warnings；修改的前端文件 ESLint 通过，`git diff --check` 通过。
- 使用仓库 `type-check-pure` 对应的 TypeScript 7 命令检查：新增功能没有错误，全量检查仍被两个未改动文件的 `kbar` 回调签名错误阻止：`frontend/src/metabase/dashboard/components/DashboardTabs/DashboardTabs.tsx:53` 和 `frontend/src/metabase/nav/components/AdminNavbar/AdminNavbar.tsx:41`。本次未扩大范围修改这两个文件。

复现命令（需按仓库开发文档准备工具链和依赖）：

```sh
clojure -X:dev:test :only '[metabase.asset-transfer.schema-test metabase.asset-transfer.core-test metabase.asset-transfer-rest.api-test metabase.app-db.schema-migrations-test/asset-transfer-tables-test]'
bun run build:cljs
bun x jest --ignoreProjects ci-scripts --runInBand frontend/src/metabase/admin/asset-transfer frontend/src/metabase/admin/app/reducers.unit.spec.ts frontend/src/metabase/admin/routes.unit.spec.tsx
bun run type-check-pure
./bin/lint-migrations-file.sh
```

实际测试使用本机临时工具链和内存 H2；迁移 lint 调用与仓库脚本相同的 Clojure 入口和工作目录。没有修改系统默认 Java/Node 配置。

## 使用和交付边界

1. 更新并启动包含本次代码的开源版服务，按现有启动流程应用 App DB 迁移；以管理员进入 `/admin/asset-transfer`。
2. 源实例选择 Questions 和可选的额外连接模板，下载 JSON。若出现不支持的查询/样式，按错误定位并从本批选择中排除；第一版不会悄悄删掉配置。
3. 目标实例上传文件，为每个源连接选择目标连接；也可从模板创建连接并补齐凭据。只包含连接模板的文件可在此完成，无需创建空导入批次。
4. 等待目标元数据同步，选择普通 Collection，预检查后执行。打开生成的 Question 验证实际查询结果。
5. 失败时保留原请求重试；刷新后可以恢复上次已提交结果和补发事件。浏览器 sessionStorage 只保留最近请求 ID，不保留包或凭据。

## 本地浏览器验收（2026-11-02）

已编译完整 OSS 前端并启动两个独立实例：源 `http://localhost:3040`，目标 `http://127.0.0.1:3041`。分别使用独立 H2 App DB，并通过专用 PostgreSQL 16 容器的源、目标数据库执行真实查询。没有使用现有业务数据库。

| 场景 | 结果 |
| --- | --- |
| 浏览器选择柱状图、SQL 表格并下载 JSON | 成功；下载文件包含 2 张 Question 和 1 份连接模板 |
| 仅导出、上传连接模板 | 成功；0 张 Question、1 个数据库，无需选择 Collection 或创建空导入批次 |
| 上传文件、从模板创建目标连接 | 成功；host/port/user 预填，密码为空；修改 dbname 后创建并同步 |
| 选择已有普通 Collection，预检查并导入 | 成功；两张图表均创建并提供打开链接 |
| 数据库、表、字段 ID 转换 | 数据库 3 → 2，表 10 → 12，category 77 → 80，amount 78 → 81 |
| 打开导入图表运行真实查询 | 柱状图和 SQL 表格都显示 Books 200、Hardware 400、Software 400 |
| 浏览器模拟断网后重试 | 成功；保留同一 request_id，无重复创建 |
| 刷新页面后恢复结果 | 成功；不重新上传文件即可读取已提交结果 |
| 再次上传相同文件并执行 | 两张图表均显示 Skipped |
| 预检查后在目标图表页面修改标题 | 旧计划被拒绝；重新预检查显示 Conflict，导入按钮禁用；未覆盖目标修改 |

完整流程未发现阻断问题。启动检查发现两个恢复接口缺少显式路由参数匹配规则，已补齐 `:api/regex`；HTTP 回归 2 个测试、22 项断言通过，格式及 clj-kondo 检查通过。随后重启服务加载修复，两个健康检查均为 `ok`，再次通过浏览器完成幂等导入。

本地样例、截图、运行日志、启动脚本与登录信息位于 Git 忽略目录 `local/asset-transfer-e2e/`。凭据单独保存在权限为 600 的 `login.txt`，不写入本计划或版本控制。验收结束后保留服务和样例，便于继续手工查看。

仍未覆盖真实 MySQL/ClickHouse 数据源及 PostgreSQL/MySQL App DB 迁移。本次没有提交、推送或生产部署；全量类型检查的两个既有问题仍按上节记录。

## 提交与镜像构建

浏览器验收后，用户授权提交代码并构建 `linux/amd64` 镜像，指定标签为 `r.inner.silinex.work/silinex/oss/metabase:20261102-amd64`。沿用 `Dockerfile.tokenplus` 的 OSS 构建流程，并从提交后的 Git 快照生成构建上下文。`.dockerignore` 同时排除 `local/`，防止后续直接构建时夹带测试数据库、凭据和日志。

提交前运行模块配置生成器，移除未被其他模块使用的模型导出声明。REST 模块按仓库要求注册为 `asset-transfer.rest` 子模块，使用 `asset-transfer-rest` namespace，命名空间前缀预算相应增加一项。迁移 v1 协议仍明确采用 legacy MBQL，生产代码保留一处转换例外并为该符号增加一项 lint 配额；测试中不需要的忽略声明已删除。

镜像构建保留 Maven/Git 依赖缓存，并为 Bun、uv 和 Alpine 包增加 BuildKit 持久缓存；Bun/uv 按构建架构、Alpine 按目标架构隔离。源码变化导致安装层重跑时仍可复用已下载的依赖，缓存挂载不会进入最终镜像。首次构建或清理 builder 缓存后仍需下载；后续使用同一个 builder 才能复用本地缓存，包索引检查及新版本依赖仍可能联网。
