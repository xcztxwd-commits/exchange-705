# 多租户数据库迁移、演练与回滚

## 当前状态与适用范围

- 已实现并在独立 MySQL 5.7 实例运行版本化 DDL、默认租户回填、租户唯一约束、复合外键、全局后台名称注册、审计防修改触发器及部署版本门禁。
- 已运行合成数据的备份、恢复、迁移前后逐字段一致性、资金并发、审计失败回滚及跨租户约束检查；不是 H2，也不是浏览器 mock。
- 实际业务库只做了只读 schema/孤儿/冲突盘点，没有迁移，没有改金额，没有删除记录。生产孤儿数据阻止迁移，详见隔离清单。
- SQL 并发探针验证数据库事务/锁/唯一流水模式，不替代业务 HTTP 服务并发验收。独立原生行情探针直接调用真实 Java 服务和真实 MySQL，仍不等于全平台已上线。
- 70 张租户私有表、3 张共享表、8 张控制面/版本门禁表；完整机器清单见 `scripts/multitenant/table_manifest.json`。

## 文件与执行顺序

1. `V2026092900__legacy_feature_prerequisites.sql`：当前源码已存在功能所需的缺失表/列。仅创建结构，不复制任何真实业务行或账号密码，不重写原始资金/聊天。
2. `V2026092901__multitenant_control.sql`：控制面身份、限时会话、后台注册、独立审计、域名历史及版本门禁。默认租户 ID 为 1，状态 `MAINTENANCE`，无推测域名，没有预置总控密码。
3. `V2026092902__multitenant_isolation.sql`：为全部私有表回填 `tenant_id=1`，保留历史主键和原时间；添加无默认值的 NOT NULL、租户外键、复合唯一和关联约束；建立后台名称注册；撤销旧无租户会话。

这三份 SQL 必须作为同一审核版本使用。MySQL DDL 会隐式提交，不能用 ROLLBACK 伪装成可撤销的全流程。中途失败必须停留在维护状态，查清部分迁移状态；脚本不会盲目继续覆盖。

生成第二份隔离 SQL 的入口为 `python scripts/multitenant/build_manifest.py`。修改清单或过程模板后需重新生成、审核 diff、重跑演练；修改后的校验哈希与上次成功证据不一致时，正式迁移入口会拒绝。

## 一键独立演练

工作目录为 `C:\workspace\fx\705`：

```powershell
python scripts/multitenant/mysql_migration.py rehearse
```

使用 `compose.multitenant-test.yaml` 创建 `mt705-20260929-test-mysql`，MySQL 5.7，端口只绑定 `127.0.0.1:64029`，使用独立命名卷与 `10.235.73.0/24` 网络。密码随机生成，不在命令参数/报告中输出。脚本检查容器测试标签和本机端口绑定；不会使用名称相近的项目实际实例。

每次创建新的 `mt705_<时间>_<随机值>` 和独立恢复库；不删除旧数据库、不清卷、不重置其他服务。`legacy-schema.sql` 仅含实际旧环境的结构快照，测试账号、资金、订单和聊天全部合成。重复运行会保留多个演练库，需由维护者按报告中的精确数据库名清理；不提供整机/整目录清理命令。

证据写入：

- `reports/multitenant/<运行编号>/result.json`：目标身份、MySQL 版本、预检、DDL SHA-256、恢复及测试结果。
- 同目录 `before.json`、`after.json`：每表行数与原有字段的规范哈希；保留 DECIMAL 的全部 16 位小数和附件原始字节，不经过浮点数。仅当前会话令牌字段不纳入业务内容一致性，因为迁移明确撤销旧会话。
- `rollback/multitenant-20260929/schema/<运行编号>/before.sql` 与 `migrated.sql`：受限访问备份，不提交 Git。
- `reports/multitenant/latest-success.json`：最近成功的完整演练证据，而非失败运行。

备份文件可能包含私有数据和密码哈希，父目录由脚本关闭继承并仅授予当前操作用户访问权限。不要上传备份、连接文件或原始数据；报告只保存必要元数据、计数和哈希。

## 测试连接与原生行情验证

创建新的独立测试库及仅授权该库的账号，凭据只落在受限目录：

```powershell
python scripts/multitenant/mysql_migration.py fixture-connection `
  --output rollback/multitenant-20260929/schema/market-test/connection.json
mvn -q -f exchange-backend/pom.xml '-Dmaven.test.skip=true' compile
mvn -q -f exchange-backend/pom.xml dependency:build-classpath `
  '-Dmdep.outputFile=target/multitenant-classpath.txt'
$cp = (Get-Content -Raw exchange-backend/target/multitenant-classpath.txt).Trim()
New-Item -ItemType Directory -Force reports/multitenant/probe-classes | Out-Null
javac -proc:none -encoding UTF-8 -cp "exchange-backend/target/classes;$cp" `
  -d reports/multitenant/probe-classes scripts/multitenant/MarketTenantProbe.java
java -cp "reports/multitenant/probe-classes;exchange-backend/target/classes;$cp" `
  com.gtcfesk.exchange.market.MarketTenantProbe `
  rollback/multitenant-20260929/schema/market-test/connection.json
```

此探针不启动 Spring Boot、定时任务、外部行情源；直接执行原生行情服务与真实 MySQL 的写入、V3 计划、样本、恢复、发布、K 线合并、缓存隔离和跨租户拒绝。成功末行包含 `MARKET_TENANT_MYSQL_PASS checks=17`。每次先创建新连接库，避免上次探针的数据与主键影响本次结果。

旧市场 SQL 单测已改为显式租户 schema 和线程作用域，不再依赖生产启动自动建表。6 个测试类共 84 项：81 通过、0 失败/错误、3 条件跳过（专用 MySQL 性能入口/严格 TEXT 失败项/长稳参数未启用）。证据 `reports/multitenant/market-legacy-tests-summary.json`；跳过项不计入真实 MySQL 已验收范围。

## 全实体和 Repository 的真实 MySQL 验证

`JpaTenantProbe.java` 扫描全部 54 个实体，使用 Hibernate `validate`（不建表、不 update），启动所有 Repository 查询定义，不启动业务服务或计划任务。事务最终 rollback；验证缺少/错误租户拒绝、A/B ID、分页、Specification、悲观锁、JPQL OR、批量更新、新增/删除，以及真实总控客服/站内信列、幂等唯一和关联外键。

```powershell
python scripts/multitenant/mysql_migration.py fixture-connection `
  --output rollback/multitenant-20260929/schema/jpa-test/connection.json
javac -proc:none -encoding UTF-8 -cp "exchange-backend/target/classes;$cp" `
  -d reports/multitenant/probe-classes scripts/multitenant/JpaTenantProbe.java
java -cp "reports/multitenant/probe-classes;exchange-backend/target/classes;$cp" `
  com.gtcfesk.exchange.tenant.JpaTenantProbe `
  rollback/multitenant-20260929/schema/jpa-test/connection.json
```

复用前节已编译的源码和依赖 `$cp`。成功末行：`JPA_TENANT_MYSQL_PASS entities=54 checks=19`。`-Dprobe.metadataOnly=true` 仅用于诊断预期 DDL，明确不算 schema validate 验收。

2026-09-29 实测还发现旧 schema 缺少 16 个当前源码订单删除/计量字段，已通过 V00 添加可空列，旧订单不推测新单位、不改旧金额。`trading_symbol.is_hot/is_enabled` 的旧 `TINYINT(4)` 被显式映射，不将旧数据强转为 BIT。

最新全迁移证据：`reports/multitenant/20260929T071511Z_5e78/result.json`（17 项）；全 JPA/客服控制证据：`reports/multitenant/jpa-schema-probe.log`（19 项）。测试库始终独立，生产孤儿阻塞条件未解除。

## 实际数据库预检和备份

只读预检，不读取输出密码，不修改记录：

```powershell
python scripts/multitenant/mysql_migration.py inventory `
  --container exchange-705-mysql-1 --database 1090 `
  --output reports/multitenant/production-readonly-inventory.json
```

只有预检 `passed=true` 且以下条件全部满足，才能执行正式迁移：

1. 已审核完整应用版本，全部私有 Repository、原生 SQL、EntityManager、任务、缓存、文件、模拟后端具备租户边界；所有验收通过。
2. 停止前后台写入、结算、推送处理、模拟服务及异步重试，排空在途请求，并保留未完成订单及任务水位。
3. 确认目标容器 ID、卷名和数据库名；保存当前发布包、配置、Nginx 路由、上传文件及必要运行状态。数据库备份不替代私有文件和部署包备份。
4. 修复有证据支持的历史孤儿及账号冲突。不得删除资金行、补造无依据身份、关闭外键或把未知数据悄悄丢入默认租户。
5. 若预检发现旧业务触发器，先审核它的租户传播和副作用并纳入版本化迁移；当前入口拒绝未登记的旧触发器，不自动删除它们。

停写后，脚本先备份当前目标，再恢复到独立测试实例，并逐字段证明恢复内容一致，完成后才执行实际目标的第一条 DDL：

```powershell
# 仅在上列条件实际满足后执行；当前生产孤儿尚未处理，不应运行本命令。
python scripts/multitenant/mysql_migration.py migrate `
  --container exchange-705-mysql-1 --database 1090 `
  --maintenance-confirmed --allow-business-target exchange-705-mysql-1/1090
```

入口会拒绝未停写声明、不匹配目标、未知表/孤儿/冲突、既有部分租户迁移以及缺少完全相同 SQL 哈希的成功演练。正式迁移不自动解除维护，也不自动宣布功能验收通过。

## 发布门禁与旧版隔离

```powershell
python scripts/multitenant/mysql_migration.py guard `
  --container CONTAINER --database DATABASE `
  --application-epoch 2026092902 --require-ready
```

最低应用 epoch 为 `2026092902`。旧版 epoch 0 必须失败；默认 `business_activation_ready=0`，验收未批准时，即使新版本 epoch 正确也不能放开业务。

门禁是受控发布入口，不是数据库识别二进制的魔法。正式切换还必须停用旧应用实例、轮换新应用数据库凭据并锁定旧应用账号，确保旧包无法重启后使用旧凭据访问多租户库；不能让运维绕开门禁直接连接。此刻实际库未迁移，因此旧业务实例和账号未被擅自改动。

不得依靠 `ddl-auto=update` 补表。部署使用 validate 或等价受控校验；所有端、模拟后端、数据库脚本必须同批切换，禁止新库配旧业务代码。

## 回滚边界

- 尚未开放新写入：再次确认停写后，可恢复经过验证的旧备份和旧发布包，同时恢复私有文件与配置。恢复前保留故障现场，确认备份 SHA-256 和目标。
- 已创建新租户或发生新业务写入：不允许直接恢复旧备份覆盖增量，也不允许将旧无隔离代码连接新库。优先前向修复；确需恢复时保全增量，验证业务重放和去重方案。
- 本工具仅允许 `restore-new` 在受标签限制的独立实例创建新库，不提供对业务库的危险覆盖恢复快捷键：

```powershell
python scripts/multitenant/mysql_migration.py restore-new `
  --database mt705_restore_new_name --backup FULL_BACKUP_PATH
```

## 仍需单独完成的验收

真实生产孤儿处理、默认域名/TLS、总控初始凭据/MFA、全端真实浏览器联调、所有业务 HTTP 并发/资金退出、模拟实例和异步历史任务、私有文件访问、完整清理/归档流程，以及上线前恢复责任确认。数据库脚本成功不能替代这些验收，也不构成法律合规认证。


## 追加：源码发布阻塞与列宽一致性

当前新增源码审查门禁 `python scripts/multitenant/isolation_gate.py --check`，以及更严格的 `--release`。后者明确拒绝尚未解决的生产阻塞；不能把源码指纹通过当作上线批准。构建入口见 `scripts/multitenant/verify-build.ps1`，详细分类与逐项敏感面位于 `source_isolation_registry.json`。

**已确认边界差异：** TenantRepository 的读取/存在性/删除前查找带 tenant，实体 owner 回调提供额外防御；Hibernate 默认 dirty UPDATE/DELETE 仍可能只带全局 ID（及 version），不能声称每条写 SQL 均是 tenant+ID。`ORM_DIRTY_WRITE_PREDICATE` 保持发布阻塞，需后续明确 ORM 写边界方案及实际 SQL 检查，禁止用通用 SQL 改写拦截器掩盖。数据库 activation 仍为 0。

真实全应用启动额外发现 `admin_menu.menu_code` 旧长度 50 不足，V00 现仅在短于 150 时扩宽；`asset_account.coin` 仅在短于 32 时扩宽并保留原字符集/排序规则。已核查 coin 无外键关联，未改原值、未缩短既有更宽字段。仅应用到 E2E 独立库，正式库未动；随后重新完成 17 项迁移/恢复演练，新 SQL checksum 证据见上述最新报告。

历史文件只读盘点与独立 copy/repoint 演练见 `docs/multitenant-private-files-20260929.md`。数据库恢复成功不等于文件归属及授权迁移完成。
