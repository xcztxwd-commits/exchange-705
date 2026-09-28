# 资产图表按需请求、查询优化与 Redis 历史缓存执行文档

## 0. 文档用途与状态

本文件供新会话直接实施和验收，不是已完成报告。本次仅创建执行文档，没有实施 Redis 缓存、索引迁移或部署。

- 工作目录：`C:\workspace\fx\705`
- 分析基线 HEAD：`0f0c294fe1505c88d64adddd92316929d111e5e6`，仅用于识别上下文，禁止据此强制 checkout/reset。
- 数据库兼容目标：现有 MySQL 5.7；Redis 7；PowerShell；前端 Node 24。
- 检查时工作区存在其他功能的未提交修改；新会话必须重新检查并保护。
- 只在本任务隔离环境写测试数据。对现有数据库只做必要的只读结构检查；迁移应用与业务容器部署不在本工单执行授权内。

## 1. 已确认目标

### 1.1 数据查询

核对实际 SQL 和现有索引，再补足以下候选索引。现有等价或覆盖索引优先复用；索引名由迁移统一管理。

```text
contract_order         (user_id, status, close_time)
option_order           (user_id, status, close_time)
financial_yield_record (user_id, status, paid_at)
loan_record            (user_id)
```

这些索引同时服务当前持仓/未发收益和区间收益查询。不要在本任务中顺手改变贷款状态过滤规则，因为已完成贷款可能仍涉及未付费用。

把 `AssetEquityStore.source()` 的绘图历史查询改为明确列集合。分钟路径至少保留 `user_id, bucket_start, observed_at, effective_at, net_equity`；上级路径按 `AssetHistoryBucket.row()` 实际取值逐项列出。不得因删列使 `quality`、OHLC、样本数、封闭状态或 API 元数据失真。

不要全局替换所有 `SELECT *`。手工历史修改、审计证据读取等路径是否需要整行，要独立判断。

### 1.2 前端请求规则

首次进入默认 `1W`。允许触发 `/api/user/asset-history` 的动作只有：

1. 页面首次进入或从其他路由重新进入。
2. 切换不同的 `1D/1W/1M/1Y` 周期。
3. 用户点击刷新按钮。

下列动作不发起该接口：

- 停留页面，无论超过多少个原来的 10 秒周期。
- 浏览器窗口 focus、标签 visibilitychange。
- 图表点击、长按、拖动、松开、方向键、Home/End/Escape/Enter。
- 点击当前已选中的周期、余额显隐、语言切换、尺寸变化和动画重绘。

移除定时轮询及焦点/可见性刷新监听，也移除 canvas 的 Enter 刷新绑定；刷新按钮本身保留正常键盘可访问性。

进入页面的生命周期只能触发一次。当前 App.vue 使用普通 router-view；若新会话发现已改用 KeepAlive，则处理 activated/mounted 去重。保留切换周期时旧响应不得覆盖新响应的 generation 防护，以及刷新中的重复点击保护。

显示基于成功响应 `asOf` 的更新时间；本地化显示即可，不为更新时间新增网络请求。停留时“实时”金额是最后一次请求的快照，不是假装持续更新。

### 1.3 不能误解的“一周”

一周主体使用 `asset_history_1h`，但不是零分钟表查询：

- `carryIn` 从分钟记录向前查最近有效值，没有固定回看天数限制。
- 滚动七天的左端可能需要分钟级裁剪。
- 收益百分比期初基数可能需要分钟记录。

保留这几类查询。不要把窗口悄悄改成整点对齐后宣称已消除分钟查询。

## 2. 必读代码与当前数据流

以下路径均相对于本工作目录；实际读取和报告文件链接使用绝对路径。

```text
exchange-frontend/src/components/AssetPixelChart.vue
exchange-frontend/src/utils/assetPixelWindow.ts
exchange-frontend/src/views/Profile.vue
exchange-frontend/src/utils/request.ts
exchange-frontend/src/App.vue
exchange-backend/src/main/java/com/gtcfesk/exchange/user/AssetHistoryController.java
exchange-backend/src/main/java/com/gtcfesk/exchange/user/AssetHistoryService.java
exchange-backend/src/main/java/com/gtcfesk/exchange/user/AssetEquityHistoryService.java
exchange-backend/src/main/java/com/gtcfesk/exchange/user/AssetEquityStore.java
exchange-backend/src/main/java/com/gtcfesk/exchange/user/AssetHistoryBucket.java
exchange-backend/src/main/java/com/gtcfesk/exchange/user/AssetEquityJobs.java
exchange-backend/src/main/java/com/gtcfesk/exchange/user/EquityValuationService.java
exchange-backend/src/main/java/com/gtcfesk/exchange/user/ManualOrderHistory.java
exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ManualOrderService.java
exchange-backend/src/main/java/com/gtcfesk/exchange/config/RedisConfig.java
exchange-backend/src/main/java/com/gtcfesk/exchange/market/RedisMarketService.java
exchange-backend/src/main/java/com/gtcfesk/exchange/config/JwtFilter.java
```

接口不变：`GET /api/user/asset-history?range=1W`，用户身份来自已有鉴权，不能接受客户端任意 userId 来选择缓存。

返回继续包含 `points/carryIn/live/total/income/incomePercent/from/asOf/intervalMs` 及现有质量、缺失区间、极值等合同字段。

历史表映射：1D=1m，1W=1h，1M=4h，1Y=1d。live 不落入已封闭历史缓存。图表需要的零起点仅是前端显示推定，不能写历史表或改变收益。

原有请求包含当前估值、收益、时区等查询；历史缓存命中不意味着整个接口不查 MySQL。JWT 活跃时间更新也仍存在，本任务不更改鉴权。

## 3. Redis 实现原则

### 3.1 第一版范围

- 使用项目已有 `StringRedisTemplate` 和 JSON 工具，不另装缓存框架。
- 只缓存 1h/4h/1d 已封闭历史数据；1D 暂不缓存。
- 采用按需填充：归集后更新历史版本/失效；下次请求才重建。不要为所有不活跃用户主动预热。
- 缓存精简但合同完整的历史桶投影，不缓存原始 `valuation_evidence`、账号信息或令牌。
- 不缓存整份响应，也不缓存 live/total/income。时区沿用现有读取方式即可，不扩大任务。
- Redis 不可用时在有界时间内回源；不能每个请求卡住长时间 Redis 超时，也不能吞掉真正的 MySQL 查询错误。

### 3.2 时间范围与 key

缓存 key 至少区分应用/环境、schema/basis、userId、粒度、稳定桶范围和历史版本。例如结构：

```text
705:<environment>:equity:history:v2:<basis>:<userId>:<level>:<alignedStart>:<alignedEnd>:<revision>
```

这是结构约定，不是要求逐字符照抄。不要把每次变化的毫秒 `asOf` 放进 key，导致每次都未命中；也不能仅用 userId+range 缓存已裁剪的滚动窗口。

建议缓存覆盖请求范围的 UTC 对齐、已封闭桶集合；每次按本次 from/asOf 裁剪并补 carryIn/实时点。左端不足一桶的现有精确裁剪仍可查细粒度表。到达整点但归集尚未提交，不能把未封闭桶当成已存在。

缓存中记录粒度、版本、覆盖范围和必要的 sourceThrough/finalized/质量元数据。金额用十进制字符串保存，不通过 double 来回转换。空集合也可以缓存，但必须带版本，首次归集后能立即摆脱空缓存。

### 3.3 一致性基线：数据库版本与版本化 key

推荐新增轻量的、按 userId+basis+level 标识的历史版本记录。历史桶发生有效变化时，历史版本在**同一 MySQL 事务**里更新；没变化不要仅因为定时器唤醒就递增。

请求读取历史版本和构建缓存数据必须具有匹配的数据库快照，不能先读新版本再从旧 REPEATABLE READ 快照读旧桶。优先在现有一致性事务内读取版本，并保证当前估值与选用的历史版本遵循同一数据库可见性边界。

Redis key 包含版本。旧并发请求允许完成自己旧快照的响应，也允许写入旧版本 key，但绝不能把旧内容写到新版本 key。历史提交后才开始的请求，必须读取新版本，不能继续命中旧历史。

数据库版本是权威来源，不能只依靠 Redis INCR 或 DEL：进程可能在 MySQL 提交后、更新 Redis 前崩溃。版本化 key 让旧缓存即使没删除也不再被新请求使用；旧 key 由 TTL 回收。

版本读取允许增加一条小索引查询，不能为了追求“零 SQL”删去一致性保证。若选择其他更简单且等价的机制，必须用第 7 节的故障与并发测试证明，不只作文字解释。

### 3.4 提交点与所有修改路径

必须覆盖定时小时/4小时/日归集、恢复任务、手工历史补录/修改/重算。对当前用户受影响的所有粒度失效，不能只清除当前界面的 1W。

`AssetEquityStore.Session.commit()` 和外层 `locked()` 当前直接操作 JDBC connection；归集会分批提交。不要直接套普通 Spring afterCommit 回调，并假设它会自动感知这些手动事务。

版本更新随对应写入事务提交；如果主动删除旧 Redis key 或发布通知，只能在相应提交成功后执行。不可等整场长时间补算结束才让已提交的历史可见。失败批次不发布；已经成功提交的批次仍有效。

手工订单修改可能同时改余额与历史。缓存修复不得把这两部分拆成新的不一致事务。

### 3.5 TTL、开关与资源上限

- 新增明确的缓存启用开关，默认关闭，方便发布及回退；命名按项目惯例统一。
- TTL 用于资源回收和兜底，不作为新数据可见性的唯一机制。建议起始值 1h数据=2小时、4h数据=8小时、1d数据=48小时，可配置并用测试覆盖过期。
- 只为实际访问用户填缓存，不在 Redis 复制全部分钟历史。
- 不使用 KEYS/FLUSHDB/FLUSHALL 清理业务实例，不批量扫描全库删 key；按精确 key、版本和 TTL 管理。
- 损坏、未知 schema、版本不匹配的缓存必须拒绝并回源，不把未知金额转换成 0。
- 用已有日志或轻量计数记录 hit/miss/fallback/版本及耗时，不记录令牌或完整敏感资产内容。

## 4. 索引和版本表迁移

新增独立的增量 SQL/迁移脚本。现有 `scripts/asset-equity/Migrate.ps1` 只处理 V001，不能假设它会执行新迁移。

新迁移工具至少具备 Verify / Apply / Status；支持检查 information_schema 的等价索引、部分完成状态及重复执行。只有 Verify 不得写库。

MySQL 5.7 的 DDL 可能隐式提交，并涉及元数据锁；不能把“包在事务中”当作可整体回滚保证。在线算法与锁策略应在隔离 MySQL 验证，不能静默降级为长时间阻塞写入。

保留本任务新建对象清单，回退只处理本任务新建对象；不删已有索引，不改余额、订单金额或历史记录。新版本表可在关闭缓存后保留，避免危险的紧急删表回退。

## 5. 开始执行的 PowerShell 命令

以下命令用于检查与既有测试，不会应用新迁移到现有业务库。每个失败必须处理，不得继续写“通过”。

```powershell
Set-Location -LiteralPath 'C:\workspace\fx\705'
$ErrorActionPreference = 'Stop'
git status --short
git rev-parse HEAD
node --version
mvn --version
docker version

$run = Get-Date -Format 'yyyyMMdd-HHmmss'
$report = Join-Path (Get-Location) "reports\asset-history-cache-$run"
$backup = Join-Path (Get-Location) "rollback\asset-history-cache-$run"
New-Item -ItemType Directory -Path $report,$backup | Out-Null
git status --short | Set-Content -LiteralPath (Join-Path $report 'status-before.txt') -Encoding utf8
git rev-parse HEAD | Set-Content -LiteralPath (Join-Path $report 'head-before.txt') -Encoding utf8
```

读取目标文件和全部调用者；实际将编辑哪些文件确定后，逐个复制到 `$backup`，保留目录结构和 SHA256 清单。只读凭据的必要配置字段；不要将 .env、令牌、会话文件复制进公开报告。

已有前端回归命令：

```powershell
Push-Location -LiteralPath 'C:\workspace\fx\705\exchange-frontend'
try {
    foreach ($test in @(
        'assetPixelWindow.test.mjs',
        'assetEquityHistory.test.mjs',
        'assetCarryForward.test.mjs',
        'assetPixelScrub.test.mjs'
    )) {
        & node "tests/$test"
        if ($LASTEXITCODE -ne 0) { throw "Failed: $test" }
    }
    & npm run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }
} finally { Pop-Location }

& mvn -B -f 'C:\workspace\fx\705\exchange-backend\pom.xml' '-Dtest=AssetEquityCarryTest,AssetEquityScheduleTest,AssetEquityValuationTest,AssetHistoryRollupTest,AssetHistoryTest' test
if ($LASTEXITCODE -ne 0) { throw 'Backend regression failed' }

& 'C:\workspace\fx\705\scripts\asset-equity\Test-MySql.ps1'
& 'C:\workspace\fx\705\scripts\manual-order\Test-MySql.ps1'
```

这些是当前已有测试入口；如果新会话发现名称或环境已变化，应核查实际文件后调整，不可删除失败断言来凑通过。

## 6. 必须新增的隔离验证入口

建议创建 `C:\workspace\fx\705\scripts\asset-equity\Test-Cache.ps1`。这个文件在本执行文档创建时**尚不存在**，新会话必须实现后再运行。

接口约定：`-ReportPath <绝对路径> [-Performance]`；默认运行全部正确性测试，Performance 额外跑性能对照。

脚本要求：

1. 创建随机命名、带本任务标签的 MySQL 5.7 和 Redis 7 临时容器，端口只绑定 127.0.0.1，测试库与业务库名称不同。
2. 使用容器级临时卷/tmpfs，不挂业务数据目录；测试凭据是明确的无业务用途 fixture 值。
3. 等待 ready 后设置本轮测试使用的 JDBC/Redis 环境变量；测试必须拒绝非 loopback 或非测试库地址。
4. 在测试库应用基础迁移、手工历史迁移及本次增量迁移。按现有测试入口隔离不同测试的 schema，避免共享库互相清表。
5. 用真实生产服务方法和真实 MySQL/Redis验证下述场景；查询计数可在真实连接上装测试包装器，但不能用模拟数据库替代。
6. 所有关键测试实际执行、失败数为0、跳过数为0；缺数据库/Redis视为失败，不是跳过。
7. 输出测试 XML、SQL 分类计数、必要的时间线、缓存元数据和结果摘要到 ReportPath。不得输出真实令牌或密钥。
8. finally 中恢复旧环境变量；清理前核对容器随机名称及本任务标签，只删除本次创建的容器/卷。不能对现有 compose 执行 down 或重启服务。

实现完成后的执行命令：

```powershell
Set-Location -LiteralPath 'C:\workspace\fx\705'
$runner = 'C:\workspace\fx\705\scripts\asset-equity\Test-Cache.ps1'
if (!(Test-Path -LiteralPath $runner)) { throw 'Implement Test-Cache.ps1 first' }
$run = Get-Date -Format 'yyyyMMdd-HHmmss'
$report = "C:\workspace\fx\705\reports\asset-history-cache-$run"
& $runner -ReportPath $report -Performance
# 脚本必须在任一失败时抛错，不得仅打印 FAIL 后返回成功。
```

## 7. 缓存一致性验收矩阵

以下均为必须场景。使用固定业务时钟和可控行情做严格比较；并发顺序通过 latch/barrier 等确定控制，不靠任意 sleep 碰运气。

### C01 冷/暖/关闭缓存等价

同一用户、固定时间、固定报价，分别关闭缓存、冷缓存、暖缓存请求四个周期。对完整业务响应深度比较；必须保留原始小数精度、NULL、负数、质量、missingIntervals、carryIn、OHLC和期初基数。只允许排除明确非业务且本来就随机的诊断字段，并记录排除原因。

### C02 所有归集层的成功提交

预热旧历史；通过真实归集方法生成新 1h/4h/1d 桶并提交；提交后新请求得到新桶/新版本。依次验证三级，不允许只测一周。

### C03 未提交、失败与分批提交

写入后提交前阻塞：另一个请求不得看到未提交桶。强制回滚：历史和版本都不变，缓存不得发布失败结果。多个批次中一批失败：已提交批次可见、失败批次不可见。

### C04 旧请求回填竞争

请求A读取版本V和历史后暂停；写入方提交V+1；请求B读到V+1并返回新数据；随后释放A，让A把旧结果填缓存；请求C仍必须得到V+1。A不得覆盖新版本缓存。

### C05 提交后 Redis 更新失败

预热旧值；在MySQL提交成功后、Redis删除/通知前注入异常或断开Redis；恢复Redis后，提交之后的新请求不能继续返回旧版本。必须证明依赖数据库版本的正确行为，不能只等TTL到期再判通过。

### C06 手工历史补录与修正

预热1W/1M/1Y，然后通过实际手工历史服务修改历史，覆盖跨小时/4小时/日边界；检查三个范围、carryIn及相关元数据更新。失败回滚不得改变版本和缓存语义。验证另一用户完全不受影响。

### C07 缓存命中时 live 仍实时计算

历史版本不变，改变可控行情或通过测试环境真实业务路径改变当前余额/持仓；下一次请求命中历史缓存，但live/total按新状态计算。不能直接改缓存来伪造此测试。

### C08 时间推进与未封闭桶

不产生新归集，推进时钟后再次请求：滚动窗口正确裁剪，不复用旧from/asOf。覆盖分钟、小时、4小时、日边界与归集延迟；不得把尚未完成的桶提前画出来。允许精确边界/期初查询读分钟表。

### C09 空数据、无效估值与显示零

验证空历史缓存后首次归集能更新；有carryIn时沿用；无更早记录但有后续有效值时显示推定零；全部不可用时不虚构真实零。确认零起点没有写库、没有污染收益。

### C10 隔离、损坏、过期和重启

至少两个用户、不同basis/schema、三个缓存粒度不串数据。未知版本、损坏JSON、错误金额、过期key均回源。应用进程重启不需要依赖旧进程内存中的版本才能识别新历史。

### C11 Redis失联与恢复

覆盖连接失败及超时；业务响应在配置的有界超时后回源，与关闭缓存结果一致。Redis恢复后重新建立有效缓存，不复活旧数据。并发冷缓存回源不能无限重试或生成重复历史。

### C12 前端真实请求次数

在隔离API/浏览器或实际挂载组件的测试环境计数该接口：

- 首次进入1次且range=1W；静置至少60秒仍为1次。
- focus/visibility、尺寸和语言/显隐变化、图表所有交互后次数不变。
- 重复点1W不变；切1M仅增加1次。
- 点击刷新仅增加1次；加载中重复点击不增加。
- 离开再进入仅增加1次；旧1W响应晚到不能覆盖新的1M。

真实浏览器验证至少覆盖进入、停留、切周期、点图表和刷新；纯正则检查不是验收。接口HTTP测试通过隔离测试账户正常登录取token，不使用生产账户，不伪造业务令牌。

## 8. 性能及SQL验证

只在隔离环境建立代表性数据。建议先1万条，再10万条合约/期权/收益记录，包含多个用户、状态、不同时间段；至少构造完整1D及代表性的1W/1M/1Y历史。测试数据必须满足实际约束，避免全是空表/无效行。

- 索引前后保存 SHOW INDEX 和 EXPLAIN，重点观察使用的key及rows；如优化器因测试表太小选择全表扫描，要说明并用代表性规模复核，不能把“索引存在”当作“查询使用”。
- 比较同一fixture、同一报价下缓存关闭/冷/暖的SQL分类数量与耗时；不要再次笼统承诺固定减少多少条。
- 暖缓存应消除所缓存完整1h/4h/1d主体桶的MySQL读取。保留版本查询、live/收益、carryIn及边界查询属于预期。
- 固定点数与各金额结果必须一致；优化不能靠少返回数据来取得好看的延迟。
- 串行至少100次记录p50/p95；可补10并发小规模测试。数据库端耗时和HTTP端到端耗时分开；单SQL平均值不得当成接口p95。
- 缓存故障路径单独报告；缓存命中率、回源次数、Redis命令数、payload大小、运行环境与样本数一并记录。
- 若固定真实报价无法完成HTTP精确比较，应将“固定服务层等价测试”与“HTTP延迟测试”分开，说明限制，不删除业务字段蒙混比较。

不要对正在运行的业务服务压测，不全局开启会记录令牌/敏感参数的调试日志。

## 9. 构建、回退和最终交付

测试通过后运行：

```powershell
Set-Location -LiteralPath 'C:\workspace\fx\705'
git diff --check
if ($LASTEXITCODE -ne 0) { throw 'Diff check failed' }
& mvn -B -f 'C:\workspace\fx\705\exchange-backend\pom.xml' -DskipTests package
if ($LASTEXITCODE -ne 0) { throw 'Backend package failed' }
Push-Location -LiteralPath 'C:\workspace\fx\705\exchange-frontend'
try {
    & npm run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }
} finally { Pop-Location }
git status --short
git diff --stat
```

上面 `-DskipTests package` 只是已完成测试后的打包，不算测试证据。既有 `*IT` 不一定由默认Surefire命名规则运行，隔离脚本必须显式选中必要的集成测试，并检查执行数量和跳过数量。

本任务不默认部署。交付时另附经过核对的发布步骤：新增迁移Verify/Apply、缓存关闭状态下发布、验证回源正常，再开启缓存验证；不要直接复用只处理V001的旧迁移命令。

回退首选关闭缓存开关，恢复MySQL读取，保留历史及版本表。索引一般可保留；确需删除时只删除本次新建且确认不再依赖的索引。源码回退按本次备份和diff逐文件核对，不能覆盖他人后续修改。不能用整库恢复或批量删Redis充当日常回退。

最终报告至少包含：

1. 实际改动文件、索引与查询差异、前端事件矩阵。
2. 缓存key结构、版本事务边界、所有历史写入路径覆盖清单、TTL/开关/故障策略。
3. C01–C12逐项PASS/FAIL/未执行及证据位置，不接受一句“测试通过”。
4. 关闭/冷/暖/故障路径的SQL计数及p50/p95对照，金额等价结果。
5. 备份绝对路径、迁移和回退命令、构建日志、隔离容器清理结果。
6. 明确尚未部署，以及分钟表长期增长、采集超时导致尾部用户缺记录等本次未解决风险。

## 10. 完成判定

只有源码、迁移、真实MySQL/Redis一致性验证、前端请求次数验证、构建及报告全部完成，才可宣称本工单完成。外部环境不具备时可交付代码，但必须明确“集成验证未完成”，不能把文档、mock或跳过测试当成一致性已经得到证明。
