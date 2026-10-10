# 行情月线退役维护工具

`monthly_retirement.py` 使用 Python 标准库、现有 MySQL CLI 和 Redis RESP。仅适用真实 MySQL 5.7、Redis 7+；Redis 版本不足会阻断（精确期限依赖 PEXPIRETIME）。不引入依赖，不修改主键或全局 collation。

## 精确范围

- 全租户 `market_source_candle`、`market_simulation_source_candle` 的精确 `1M` 与旧供应商实际映射为月的 `m/mo/1mo`；`1m` 永远保留。读取原始 HEX 字节后辨别大小写，不依靠大小写不敏感的 SQL 比较。新请求拒绝的 `1month/month/type10` 等并不证明旧数据是月线，持久行和缓存一律保留并报告。
- `market_history_response.request_json.interval` 明确为月线的归档行；仅移除同一 `market_history_ordering.responses_json` 中这些归档的摘要键，保留整条 policy、非月字段、非月摘要和所有其他归档字节。
- `market_engine_runtime.quote_json/status_json.liveKlines` 中的月线子键。按 JSON 字节位置移除，保留其他字段和高精度数字原文。
- Redis 的 `tenant:<id>:market:kline:<symbol或simulation-history>:<period>` 及旧 `market:kline:<symbol>:<period>` 精确月后缀；`tenant:<id>:market:price:<symbol>.liveKlines` 月线子键。逐键备份 DUMP 与 PTTL；删除使用原值比较的 Lua 操作，不使用通配符 DEL。
- `market_history_restore_job/minute` 只有分钟范围，没有月周期鉴别列。疑似月元数据仅列入报告，不删除。已因旧 MySQL 大小写不敏感主键而被覆盖的数据，不能凭时间或 OHLC 恢复；工具明确报告此限制。

财务月范围、`SOURCE_1M`（一分钟投影标记）、供应商小时行情的 `range=1mo` 和日期用途不在清理范围。

工具还枚举整个 DB 的表/列，复扫相关周期列和 JSON/text 数据；Redis 使用全 DB SCAN。未分类的明确月线周期/行情 JSON 标记会阻断删除并输出人工分类清单，不能只扫描白名单表后宣称全库无残留。

非受管大表使用 MySQL `--quick` 逐行读取，不将全表额外复制到内存；仍保留显式 `--max-rows` 上限。严格验证 HEX 字段、列数、换行、总行数及 CLI 退出码，扫描消费失败时回收本次子进程，任何部分结果不作为清理通过证据。

## 门禁与备份

先停止**所有**应用写者和可能恢复旧状态的任务：行情采集、预热、模拟、引擎、补缺、恢复任务，以及旧 JVM 内存队列；保持停止直到验证结束。工具不会自动停止、启动或重启已有业务进程、容器。

1. 在已审核目标环境执行 `preview`。报告包含服务器 UUID、MySQL 版本、DB、环境指纹、逐表总数/候选数/租户数和预览 SHA。
2. 人工核对环境、候选范围和完整写者清单。可信维护人员提供新鲜停写证据（有效 15 分钟）；不能把脚本生成的声明当成生产停写证明。
3. `delete` 必须同时提供匹配的环境指纹、审核预览 SHA 和停写证据。备份文件只能新建，不覆盖。备份包含全部相关表的列、完整行原始 HEX、表定义、完整触发器定义及 SQL_MODE/字符集/collation 元数据；另存审核维护 SQL。
4. 有触发器时，生产 `delete` 默认拒绝执行；**工具禁止自动生产 DDL**。DBA 必须审核并手工维护精确相关触发器，再重新预览。仅本次新建、`monthly.retirement.owner` 标签匹配、loopback 绑定的隔离容器可使用 `--owned-trigger-maintenance` 自动演练。
5. MySQL DROP/CREATE TRIGGER 隐式提交。触发器维护、数据事务和 Redis 操作不是一个可回滚事务。任何失败都保持停写；检查备份和错误，恢复已移除的原触发器，重新验证全部定义与内容后才可恢复业务。
6. 删除后再次 `preview/delete` 应为零候选。`restore` 只插回不存在的原行、恢复仍与清理后值一致的 JSON；冲突即拒绝覆盖。全表 SHA、schema 和触发器定义必须与备份一致。Redis 备份原 DUMP、PTTL、捕获时间与原绝对过期时间；临时键恢复使用 ABSTTL，扣除停写时间，已自然过期的键跳过并报告，永久键 TTL=0。不会修改 quote 的 expiresAt/executionExpiresAt，也不会延长缓存有效期。

停写证据示例（由维护人员核验后填入真实值）：

```json
{
  "environmentFingerprint": "preview中的真实指纹",
  "allWritersStopped": true,
  "writerInventory": ["经核验已停止的每一个写者及任务"],
  "verifiedBy": "维护人员/审批凭据",
  "verifiedAt": "实际UTC核验时间",
  "productionMaintenanceApproved": true
}
```

## 使用

凭据放在既有 MySQL `--defaults-extra-file` 中；不要将密码写进参数、证据或终端输出。

```powershell
python scripts/market/monthly_retirement.py preview --database <DB> --mysql-defaults <既有凭据文件> --host <已核验主机> --port <端口> --redis-host <已核验Redis> --redis-port <端口> --redis-db <DB编号> --output <新preview.json>
python scripts/market/monthly_retirement.py delete --database <DB> --mysql-defaults <既有凭据文件> --host <主机> --port <端口> --redis-host <Redis> --redis-port <端口> --redis-db <编号> --production --reviewed-preview <preview.json> --expect-preview <审核SHA> --expect-environment <指纹> --stop-evidence <停写证据.json> --backup <新完整backup.json> --output <新delete.json>
python scripts/market/monthly_retirement.py restore --database <DB> --mysql-defaults <既有凭据文件> --host <主机> --port <端口> --redis-host <Redis> --redis-port <端口> --redis-db <编号> --production --expect-environment <指纹> --stop-evidence <新鲜停写证据.json> --backup <原backup.json> --output <新restore.json>
```

Redis 有密码时使用 `--redis-password-env <已设置的环境变量名>`。未指定 Redis 只表示未执行缓存清理；生产交付不能因此宣称缓存已清理。默认每表/缓存扫描最多 100000 行/键，超出即拒绝；必须先评估内存、维护窗口和备份容量后增加 `--max-rows`。

## 可重复隔离演练

```powershell
python scripts/market/test_monthly_retirement_mysql.py --output C:/workspace/fx/new/<新的证据目录>
```

演练创建本次 owned MySQL 5.7、Redis 7 随机 loopback 端口，加载真实生产 schema/触发器迁移，注入双租户月线、分钟、周线、归档、runtime 与缓存。检查未分类第八表/缓存门禁、中途 optimistic WHERE=0 的真实事务整体回滚、触发器恢复、精确删除、非月 count/内容 SHA 不变、第二次删除零、恢复完整 SHA、Redis DUMP/原绝对 TTL 和已过期键不复活；最后核对 owner 标签并删除本次容器与匿名卷。不会向其他 MySQL 容器写入。

中途故障演练实际注入的是 DML 预期行数为零；CREATE TRIGGER 自身恢复失败的分支尚未做物理故障注入。该分支会尽力恢复全部定义并输出未恢复清单，发生时必须保持全部写者停止，人工核验后才能恢复业务。

本轮生产停写未确认，生产清理未执行；脚本、审核 SQL 和隔离演练供维护评审。
