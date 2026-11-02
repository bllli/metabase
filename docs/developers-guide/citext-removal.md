# PostgreSQL 应用库移除 citext 依赖

用户邮箱使用普通 `text`，应用查询显式使用 `lower(email)`，数据库通过 `core_user_email_lower_key` 唯一表达式索引阻止大小写不同的重复邮箱。该约束同时覆盖并发写入和直接 SQL 写入；不能只靠应用在插入前查重。

## 新库初始化

`metabase.app-db.liquibase.postgres` 注册 SQL 生成器，针对历史初始化脚本的两条语句执行兼容转换：跳过 `CREATE EXTENSION IF NOT EXISTS citext WITH SCHEMA public`，将 `core_user.email` 创建为 `text`。其他语句保持不变，H2/MySQL 不使用此转换。

历史 SQL 和已发布的 Liquibase changeset 不修改，既有数据库的初始化 checksum 保持兼容。SQL 生成器按 PostgreSQL/RawSqlStatement 类型注册，在生成 SQL 时判断具体语句，避免 Liquibase 按类型缓存生成器造成漏处理。

## 已有库升级

新迁移 `emtxtq7k` 仅在 PostgreSQL 执行，步骤在同一个事务内完成：

1. 锁定用户表，检查字段类型及邮箱的依赖视图。
2. 保存支持的应用视图的表级/列级权限、注释和视图选项，按依赖顺序删除视图，不使用 `CASCADE`。
3. 将 `citext` 转换为 `text`，保留已有邮箱字符串的大小写及用户 ID。
4. 按仓库中固定版本的 SQL 重建应用视图并恢复元数据，清除重建时额外获得的默认权限。
5. 创建并校验 `lower(email)` 唯一索引，移除被替代的非唯一 `idx_lower_email`。保留原 `core_user_email_key` 约束。

支持的应用视图为 `v_users`、`v_subscriptions`、`v_alerts`、`v_ai_usage_log`、`v_metabot_conversations`、`v_api_key_usage`、`v_agent_api_calls`、`v_mcp_tool_calls`。视图定义恢复为本次迁移指定的应用版本，应用库视图不作为用户自定义查询的扩展点。

自定义或物化依赖视图、不同所有者、额外规则/触发器、经其他授权者转授的权限会阻止自动迁移。操作者须先单独保存并处理这些依赖，再重试同一版本。失败会回滚本迁移内的字段、视图和索引变更；迁移记录只在成功后写入。

已有 `citext` 扩展不会自动删除，避免影响同库内其他对象，也不要求应用账号拥有扩展管理权限。升级后应用对象不再依赖它；如需清理遗留扩展，由数据库所有者另行确认依赖后使用 `DROP EXTENSION citext RESTRICT`，不得使用 `CASCADE`。

## 邮箱行为

用户模型已有的新增/修改小写归一化保持不变；登录、密码找回和用户查重继续按小写比较。序列化用户解析，以及高级配置、支持访问授权和 SCIM 的遗漏查询补齐显式小写比较，因此历史混合大小写邮箱仍能匹配。

PostgreSQL 数据源驱动及 JDBC 对 `citext` 的读取兼容保留；这是对旧数据或外部业务数据源的兼容，不要求应用库安装扩展。大小写折叠仍遵循现有 Java/数据库 locale 规则，本次不引入新的 Unicode 账号等价规则。

## 发布与回退

升级前备份应用数据库并保管原加密密钥，安排用户表 DDL 所需的维护窗口。新旧二进制不应并行写入迁移中的应用库。

此迁移明确拒绝自动回退，避免隐式重新引入扩展或丢失唯一性保证。需要退回旧版本时，应恢复升级前数据库备份和匹配的旧镜像；不能只执行镜像降级或清除迁移记录。

## 验证

- SQL 生成器：先执行普通语句，再执行初始化语句，验证缓存选择不影响处理；无关 SQL 与 H2 保持原样。
- PostgreSQL 新库：初始化后无 `citext` 扩展，邮箱为 `text`，重复插入和修改均被唯一索引拒绝。
- PostgreSQL 旧库：使用未转换的历史初始化脚本构建旧库，验证视图权限/注释/选项、邮箱内容、初始化 checksum 和重试。
- 故障注入：自定义依赖被拒绝，删除视图后发生错误仍恢复原字段和视图。
- 普通账号：账号无安装扩展权限，验证应用初始化、大小写混合登录、密码找回限流、用户归一化和序列化解析。

针对性测试位于 `test/metabase/app_db/email_text_test.clj`、`test/metabase/app_db/liquibase/postgres_test.clj`、`test/metabase/models/serialization/resolve/default_test.clj`，企业查询兼容测试位于对应模块的 `db_test.clj`。测试使用隔离数据库，不连接线上应用库。
