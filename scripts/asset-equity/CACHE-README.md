# 资产历史缓存交付与操作

本入口不自动部署。不要对业务实例运行测试 SQL。`Test-Cache.ps1` 创建并删除带专属标签的随机临时容器；不使用 compose，不读取业务凭据，不挂载业务卷。

## 配置

缓存默认关闭。开启同时要求已有净资产读取开关、完整迁移及独立环境前缀：

```properties
asset.history.equity.read-enabled=true
asset.history.cache.enabled=true
asset.history.cache.namespace=705:YOUR_ENVIRONMENT
asset.history.cache.timeout-ms=200
asset.history.cache.hour-ttl-seconds=7200
asset.history.cache.four-hour-ttl-seconds=28800
asset.history.cache.day-ttl-seconds=172800
```

环境变量对应 `ASSET_HISTORY_CACHE_ENABLED`、`ASSET_HISTORY_CACHE_NAMESPACE` 等标准 Spring 名称。关闭 `asset.history.cache.enabled` 即恢复原 MySQL 历史读取；开关为启动配置，需在另行授权的发布窗口生效，不是动态管理接口。TTL 范围 1–604800 秒，命令/连接超时限制为 25–1000ms。一次缓存尝试最多 GET+SET；Redis GET 失败直接回源，不循环重试。边界递归可能访问不同粒度，各自有界；MySQL 时间不计入 Redis 超时。

复用项目的 `StringRedisTemplate`/Jackson，无新增应用依赖。历史缓存使用独立、短超时的 Lettuce 连接，避免改变结算/行情客户端。使用 `spring.redis.host/port/database/username/password/ssl`；首版不支持 URL/Sentinel/Cluster 配置，开启时明确拒绝，关闭时不创建该客户端。默认主 Redis Bean 保持 `@Primary`，历史客户端用限定符注入。

## 一致性

* Key：`<namespace>:equity:history:v2:net_equity_v1:<user>:<level>:<alignedStart>:<closedEnd>:<revision>`。
* MySQL `asset_history_revision(user_id,basis_version,level)` 是唯一权威版本。三个父历史表各有 INSERT/UPDATE/DELETE 触发器，与原写入共享事务、提交、回滚和行锁。UPDATE 用 NULL 安全比较，只比较业务字段，不因 `updated_at` 或无变化 UPSERT 增版。
* 触发器覆盖 `AssetEquityJobs.reduce`（定时、恢复、分批提交）、`ManualOrderHistory.apply`（与钱包共用调用者事务）、其他行级补录/修正/删除。无需依赖 JDBC 手动提交之外的 Spring afterCommit，也无 MySQL 提交后必须成功的 Redis DEL。
* 请求在原 `AssetEquityStore.read` 的 REPEATABLE READ 快照中读取实时资产、版本、历史；旧请求最多回填旧版本 key，新请求不能选择旧 key。
* 不缓存 live/total/income/carryIn/收益基数/时区/完整响应，不缓存 1D。小时、四小时、日只缓存已封闭桶的投影。金额为十进制字符串。版本、覆盖范围包含于 envelope key；quality/sourceThrough/finalized/OHLC/样本数保持完整。
* sourceThrough 在请求时过滤，防止整点附近晚到观测被永久遗漏；毫秒 asOf 不进 key。左边界仍逐级裁剪，必要分钟查询保留。
* 损坏 JSON、错误 schema/basis、金额格式、覆盖范围不匹配均回源并替换；不将无效金额转为零。上限 2000 桶、2MB 反序列化载荷，TTL 回收旧版本。hit/miss/fallback/Redis 命令计数在 `AssetHistoryCache` 的 LongAdder 中，不记录资产和令牌。
* 不得 TRUNCATE 历史表或重置版本表；TRUNCATE 不触发行级触发器。此类非业务维护须先关缓存，并在重新开启前换 namespace。常规回退保留版本表及触发器。

## 隔离验收

依赖 Docker、MySQL 5.7/Redis 7 镜像、Maven/JDK、Node 24、已安装前端依赖及 Chromium/Playwright。Playwright 可指定现有安装绝对路径，不是生产依赖：

```powershell
$env:PLAYWRIGHT_PATH='C:\ABSOLUTE\node_modules\playwright'
$env:CHROME_PATH='C:\Program Files\Google\Chrome\Application\chrome.exe'
& 'C:\workspace\fx\705\scripts\asset-equity\Test-Cache.ps1' `
  -ReportPath 'C:\workspace\fx\705\reports\asset-history-cache-NEW_RUN' -Performance
```

脚本失败返回异常；没有 MySQL/Redis、浏览器或任何 C01–C12 测试缺失，都不是通过。浏览器安装真实 SFC，并调用隔离 Tomcat 中生产 AuthController/AuthService/JwtFilter/AssetHistoryController。只有现金 fixture，没有外部行情服务、邮件或后台调度；不是全量生产应用部署。HTTP 动态时间与固定时钟服务等价测试分别报告。故障通过暂停本次有标签 Redis 容器及拒绝连接端口实现。版本重启检查使用独立 Java JVM。

## 迁移与回退（本任务未对业务执行）

以下命令必须替换为经授权的数据库容器、schema、用户和单独清单路径。默认 Verify/Status 不写库。Apply 要求可写的绝对 ManifestPath；不要把测试清单用于业务回退。

```powershell
$args = @{
  Container='AUTHORIZED_MYSQL_CONTAINER'; Database='AUTHORIZED_SCHEMA'; User='AUTHORIZED_USER'
  PasswordVariable='MYSQL_PASSWORD'
  ManifestPath='C:\workspace\fx\705\rollback\AUTHORIZED_RELEASE\cache-objects.json'
}
& 'C:\workspace\fx\705\scripts\asset-equity\Migrate-Cache.ps1' -Mode Verify @args
& 'C:\workspace\fx\705\scripts\asset-equity\Migrate-Cache.ps1' -Mode Apply @args
& 'C:\workspace\fx\705\scripts\asset-equity\Migrate-Cache.ps1' -Mode Status @args
```

先备份并批准数据库变更窗口，再执行迁移；缓存关闭发布并验证回源后才开启。索引检查复用等价左前缀覆盖索引，新增使用 `ALGORITHM=INPLACE, LOCK=NONE`、5 秒元数据锁等待，不自动降级。MySQL 5.7 DDL 隐式提交，失败按清单和现存定义续跑；不声称事务回滚 DDL。触发器同名但定义不一致立即拒绝。

优先回退：设 `ASSET_HISTORY_CACHE_ENABLED=false`，在另行批准的发布流程加载该配置；保留全部历史和版本表。确需移除新增索引，关闭缓存后核对本次清单及当前定义：

```powershell
& 'C:\workspace\fx\705\scripts\asset-equity\Migrate-Cache.ps1' -Mode RollbackIndexes @args
```

只删除清单中属于本迁移且定义仍匹配的索引，保留旧索引、版本表、触发器及全部业务数据。不使用 KEYS、FLUSHDB、FLUSHALL、整库恢复或 git reset/clean/stash。源码回退按报告中的备份及任务局部 diff 手工核对，不能整文件覆盖并行工作。
