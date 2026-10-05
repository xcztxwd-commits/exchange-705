# Exchange 705 当前数据库结构（2026-10-05）

## 版本与文件

本目录发布与业务源码提交 `50d65fdc59083f9c3d029fa78d42794f5eabf86c`、结构版本 `2026100404` 对应的**无业务数据结构快照**。来源为已迁移到该版本的本机隔离 MySQL 5.7 库，不是旧的 `1090.sql`，也不是生产数据备份。本次只读取结构、生成文档和提交 GitHub，不执行业务库迁移、服务切换或业务激活。

| 文件 | 用途 |
| --- | --- |
| [schema.sql](schema.sql) | 全量 CREATE TABLE、索引、外键、触发器和存储过程；仅供空库导入 |
| [data-dictionary.md](data-dictionary.md) | 每张表的分类、字段、类型、默认值、索引、外键和触发器清单 |
| [schema-manifest.json](schema-manifest.json) | 对应源码版本、对象数量、差异、迁移顺序、SHA-256 与本次验证结果 |
| [check_snapshot.py](../../scripts/database/check_snapshot.py) | 不连接数据库的结构、脱敏边界及校验和检查 |

### 实际对象数量

- 112 张表：94 张租户私有表、6 张共享表、12 张控制面表。
- 1319 个字段、448 个索引、188 个外键。
- 161 个触发器、1 个存储过程 `joint_s4_fence`；没有视图、事件或存储函数。
- 索引和外键按对象计数，复合索引/外键的多个字段不重复计数。

现有 [table_manifest.json](../../scripts/multitenant/table_manifest.json) 登记 **113** 张表，但其中 `tenant_migration_history` 仅被登记，没有对应的审定创建语句，也未出现在本次实际结构中。因此本快照忠实发布 **112** 张实际表，不新增猜测结构、不伪造第 113 张表或已完成迁移记录。现有受控迁移证据保留在私有 JSON 回执中。这个清单差异需要另行设计/审定，不能通过手工建表“制造通过”。

## 导出与公开边界

导出使用 `mysqldump --no-data --routines --triggers --events --skip-lock-tables --skip-comments --skip-add-drop-table --no-tablespaces --default-character-set=utf8mb4 --set-gtid-purged=OFF`；仅查询结构元数据。导出前后两次原始 DDL 完全相同，用于拒绝采集期间的结构变化。

公开快照做了以下明确处理：

1. 162 个存储对象 DEFINER 改为 `CURRENT_USER`，不暴露源数据库账户或主机身份。导入后以实际导入账户为定义者；部署时必须审定该账户的权限与存储对象执行权限。
2. 去掉 35 个表级实时 AUTO_INCREMENT 计数器；保留字段的自增属性。空库 `user_account` 表的自增下限保留审定多租户 DDL 的 `7000001`，而不是源库已使用的计数值；这不等于当前 ORM 编号生成器的起点。当前 `UserIdGenerator` 使用 `user_id_sequence`，`INITIAL_PARAM=752911`，审定序列迁移的初始 `next_val=752910`。表级自增计数器与 ORM 序列不是同一机制；本快照不复制序列行，仍须由正常初始化流程配置。
3. 使用既有 `controlled_migration.py` 的严格 SQL_MODE 头校验，核对真实触发器和过程的模式；不改写对象保护条件。MySQL 5.7 转储的 `NO_AUTO_CREATE_USER` 兼容差异不能无条件忽略。
4. 不发布任何业务表行、账户密码/哈希、邮件或云端凭据、域名绑定、租户/用户记录、迁移/批准回执、备份、服务器 UUID、源库名称或连接配置。
5. 原结构内少量旧注释存在历史编码乱码；DDL 和数据字典照录结构元数据，不猜测修正，不改写业务数据库。

触发器和过程正文包含执行逻辑，可能有内部 INSERT/UPDATE/DELETE；它们不是业务数据转储。不得为了删去这些关键字而破坏租户不可变、审计或 generation fencing 保护。

本目录不包含 `.env`、`1090.sql`、后台密码、`uploads`、`rollback`、`reports`，也不把尚未实施的控盘 V2 设计当作当前已存在的结构。

## 校验

在仓库根目录执行，Python 标准库即可，不需要连接 MySQL：

```powershell
python scripts/database/check_snapshot.py --self-test
python scripts/database/check_snapshot.py
```

检查覆盖实际表集合、结构版本、索引/外键/触发器/过程数量、25 份迁移的明确顺序、必要文件校验和、源 DEFINER 泄漏及顶层数据/破坏性 SQL。变更 schema、数据字典、清单或审定迁移后必须重新生成快照与校验和，不应仅修改 JSON 来绕过失败。

本次还在新建、无宿主端口、无网络、使用独立 tmpfs 的 MySQL 5.7 容器中执行空库导入、112 张表逐表零行检查、约束检查恢复、再次无数据导出及规范化 DDL 逐字节比对。实际结果以 `schema-manifest.json` 的 `verification` 为准。检查容器只按本次新建的精确 ID 清理，未操作共享服务或旧数据库。

这些是**结构导入与往返验证**，不是应用启动、所有业务 API/UI、生产备份恢复或正式发布验收，也不改变 `release_approved` 或业务激活门禁。

## 空库导入

**只能导入全新空数据库。不得覆盖已有业务库、把此文件作为旧库升级补丁，或带 `--force` 忽略错误。MySQL DDL 隐式提交，导入失败可能留下部分结构，不能声称事务已经回滚。**

下面是 Bash/MySQL 客户端示例；从仓库根目录执行。使用交互密码提示，不把密码写入命令、文档或 Git。

```bash
python scripts/database/check_snapshot.py
mysql -u DB_ADMIN -p --default-character-set=utf8mb4 \
  -e 'CREATE DATABASE exchange705_empty CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;'
mysql -u DB_ADMIN -p --default-character-set=utf8mb4 exchange705_empty \
  < docs/database/schema.sql
```

`CREATE DATABASE` 故意不带 `IF NOT EXISTS`；数据库名已存在即停止，不能继续向旧库导入。导入账户需要创建表、索引、外键、触发器、过程等结构的权限；正式服务账户不能因此被授予不必要的管理权限。

本次验证版本为 MySQL 5.7。未测试 MySQL 8、MariaDB、不同 SQL_MODE 或其他排序规则，不宣称跨版本兼容。

### 导入后仍不是可直接运行的业务环境

此文件**没有种子数据，也没有 `tenant_schema_version` 行或批准记录**。只创建结构，不创建默认管理员、不复制租户与业务配置、不伪造“已完成迁移”或“已批准激活”。

新环境还需要独立审定的初始化流程：租户及策略、总控身份和 MFA、用户编号序列、角色/菜单、交易品种、必要业务配置、密钥/域名/文件存储，以及真实版本与激活门禁。密码、邮件及其他凭据应通过受限配置注入，不得提交到公开 SQL。

当前后端使用 Hibernate `ddl-auto=validate`，不能依靠启动自动补表。本快照已经包含当前版本的结构，**不要在其上再次执行 25 份旧迁移**；旧库则必须走原有明确授权、备份恢复证明和受控前向迁移流程。

默认 [compose.yaml](../../compose.yaml) 仍引用本地 `1090.sql`。本次没有把默认 Docker 初始化改为快照，也不声称克隆仓库后即可直接启动业务。换用此快照必须配套上述初始化与门禁设计，不能只替换挂载文件。

## 既有迁移与相关文档

25 份审定迁移的**唯一顺序**为 [table_manifest.json](../../scripts/multitenant/table_manifest.json) 的 `migration_files`；逐份原始 SHA-256 已保存在 [schema-manifest.json](schema-manifest.json)。不按目录名字盲目遍历，也不重复执行 `superseded_authoring_inputs` 中的旧草稿。

- [历史结构基线](../../scripts/multitenant/legacy-schema.sql) 与 [前置功能结构](../../scripts/multitenant/legacy-feature-tables.sql)：历史定义，不等于当前快照。
- [受控迁移入口](../../scripts/multitenant/controlled_migration.py)：备份、恢复证明、授权和前向迁移边界。
- [多租户迁移运行手册](../multitenant-migration-runbook-20260929.md) 与 [历史结构盘点](../multitenant-schema-inventory-20260929.md)：保留原始日期与历史状态；其旧数量不覆盖本目录的新快照。
- [2026-10-05 所有者授权测试环境迁移记录](../705-owner-live-test-migration-20261005.md)：主库、Demo、故障保全和迁移状态分别计数，不把本次文档发布当作后续上线证明。
- [2026-10-05 代码整合记录](../705-code-integration-20261005.md)：对应源码及发布边界。
- [Docker 部署说明](../../README-Docker.md)：现有运行配置、数据与凭据边界。

## 后续更新规则

每次结构版本变化，应从对应版本、结构稳定的隔离库重新进行无数据导出，完整保留必要对象；核对实际对象清单，重新生成字段/索引/外键字典及校验和，扫描公开候选文件中的凭据，再在独立新库执行导入和规范化 DDL 往返验证。不得上传整库数据备份或未经审定的初始化凭据，也不得用旧快照覆盖真实库。
