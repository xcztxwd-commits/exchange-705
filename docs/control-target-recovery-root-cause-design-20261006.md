# 控盘与渐进恢复卡住：根因、数据库机制和彻底修复方案

日期：2026-10-06。本文用户侧时间统一使用 Asia/Singapore（UTC+8）。分析对象为租户 1、品种 61（USD/JPY，`JPY=X`）。

本轮重新读取故障日志、恢复失败日志、当前 30 分钟日志、线上只读数据库证据和端到端代码；没有再次修改线上、操作订单或资金，也没有把下述待实施方案冒充成已完成修复。上一轮已执行的单类热修、正式恢复接口和测试结果作为对照证据。

## 1. 结论：不是一个前端超时参数问题

本次已确认的主因是代码和事务生命周期设计缺陷：查询“截止时刻最新行情”错误沿用了完整历史去重的反连接，在大量正常镜像数据下扫描历史；查询位于持有引擎行锁和 15 秒写入租约的事务内，耗时超过租约后，持有记录的写入被数据库隔离触发器拒绝；最后采样和任务完成一起回滚，周期性重试仍从同一水位开始。

恢复操作又先推进旧任务，必须穿过相同的失败完成路径，导致用来救场的恢复接口也失败。另有独立的前后端回执协议缺口，会把失去响应的渐进恢复显示成长期“启动命令不存在”。

因此需要分别处理四件事：

1. 最新源行情查询的扫描退化：本次主故障的直接根因，已热修。
2. 短租约事务中包含耗时操作、失败后无有效进展：故障固化和扩散机制。
3. 回到 SOURCE 的应急路径依赖旧任务成功完成：恢复能力设计缺口。
4. 启动/渐进恢复/取消的回执协议不一致：页面无限待确认的独立设计缺口。

现有证据不支持把本次归因于用户填错价格、点击顺序错误、数据库人工插入重复记录或已证实的死锁。历史上每一次超时是否同因，仍需按接口和请求键关联，不能全部归到本次慢 SQL。

## 2. 事实与证据边界

### 2.1 故障任务确实卡在最后一次提交

- 实际任务：`3349a6d6-b084-4b97-aa51-70299cae622e`。
- 实际启动请求键：`909dc0f3-817f-4874-b481-203adda91772`。
- 2026-10-06 22:08:55.364 接受命令，22:08:56.370 开始任务。
- 起点 158.076、目标 157.900、时长 25 秒、强度 2、随机波动开启、V4，持久化计划有 26 个点。
- 计划结束：22:09:21.370（`1791295761370`）。
- 故障时数据库水位：22:09:20.370（`1791295760370`），相差恰好 1 秒。
- 故障时任务仍为 RUNNING，flow 为 TARGET，hold 的 `activated_at` 为 NULL，最后已提交显示价为 157.919，原始价为 158.101。

这不是计划一直算不出目标，也不是 25 秒还没有经过，而是最终事务始终未提交。日志捕获的 22:13:47.186 等时刻仍反复报错；这是所读取片段中的时间，不代表故障第一次发生的时间。

### 2.2 最短决定性日志

```text
SQL [UPDATE market_control_hold ...]
SQL state [45000]; error code [1644]; ENGINE_FENCED
ControlHoldService.activate(ControlHoldService.java:45)
PersistentPriceControl.advance(PersistentPriceControl.java:162)
PersistentPriceControl.stopLocked(PersistentPriceControl.java:415)
ForexQuoteMarketService.manualControlLocked(ForexQuoteMarketService.java:1492)
```

这条真实堆栈同时证明：一键恢复原始行情不是单独改偏移字段，它也进入旧任务完成和 hold 激活路径。后台采样的堆栈到达相同的 `advance` / `activate`。

读取的 `before-api.private.log` 有 1,500 行、11 个品种 61 采样 ERROR 事件；`manual-failure.private.log` 有 1,256 行、24 个此类事件。两个捕获窗口不能直接相加作为唯一故障次数，堆栈中重复出现的 `ENGINE_FENCED` 字符串也不是独立事故计数。

### 2.3 线上与本地交叉验证

- 故障期间线上查询运行快照持续 16–40 秒，数据库 CPU 曾约 489%；当时未发现活动行锁等待。行锁竞争可能放大其他请求延迟，但未证明本次是死锁。
- 原 SQL 的线上 EXPLAIN：事件分支估计 662,784 行；tick 分支估计 615,476 行，含 `Using filesort` 和 `DEPENDENT SUBQUERY`。这些是优化器估计，不是实际扫描计数。
- 本轮对截止条件下最多 1,000 个符合条件的 JPY tick 做有界核对，1,000 个都有精确事件镜像。这不是宣称整个表镜像率 100%。
- 表元数据估计：事件账本约 554.6 万行、tick 约 548.1 万行，均为所有品种的合计估计，不能说 JPY 单个品种有 550 万行。
- 十万行完全镜像的独立 MySQL 5.7 夹具：旧 SQL 183.009 ms、`Handler_read*` 合计 200,005 次；修复 SQL 0.483 ms、6 次，结果价格和时间一致。
- 线上修复后同截止条件查询，本轮测得 24.544 ms；上一轮另一次为 0.943 ms。负载、缓存和计时条件不同，不承诺恒定小于 1 ms。
- 修复前正式 SOURCE 恢复 HTTP 500，运维调用链端到端 41.595 秒；修复后 HTTP 200，同类调用链 0.661 秒。

### 2.4 当前观测，不等同于永久修复完成

2026-10-06 23:37:04.690 的只读核对：SOURCE、running=false、enabled=false、offset=0，显示价与原始价均为 158.054；原任务已 COMPLETED，水位达到计划终点，主后端 healthy，热修 JAR 哈希保持不变。读取的最近 30 分钟 362 行日志中没有 `ENGINE_FENCED`、没有品种 61 采样失败、没有 ERROR 标头。

同窗口有一次 `ClientAbortException: java.io.IOException: Broken pipe`，随后异常处理器写响应失败的 WARN。没有关联的 AI 控盘接口名、请求键和阶段耗时，不能断言这一次仍是慢 SQL，也不能宣称整个站点所有超时都已消失。

原命令行仍为 RUNNING，表示该启动命令已经激活并关联任务，不是说其任务至今还在运行。任务生命周期应看 task/flow/SOURCE，不能把命令回执的 RUNNING 当作另一项故障。

## 3. 慢 SQL 为什么会产生

### 3.1 镜像数据来自正常业务写入

源码：`C:/Users/徐乾妖/.codex/worktrees/3d6b/705/exchange-backend/src/main/java/com/gtcfesk/exchange/market/ControlHistoryStore.java`，571–582 行。

一次接受的行情会执行：

1. 按租户、品种、event_id 查重。
2. 向 `market_source_event` 插入不可变事件。
3. UPSERT `market_source_quote`，维护当前最新源行情。
4. 向兼容账本 `market_source_tick` 插入源时间点；其唯一主键为 `(tenant_id,symbol_id,source_time)`，同源时间的旧 tick 保留首条值。

因此一条行情同时出现在事件表和旧 tick 表，是兼容性双写机制，不是证明 DBA 手工插入脏数据。入口对相同时间、相同价格已有重复判断，不能把所有增长都说成“重复请求写了无穷条 UUID”。

### 3.2 全历史去重规则错误用于 latest-only

源码：上述文件 559–569 行；调用方为 `ControlHoldService.java` 36–38 行。

查询必须找任务结束时刻之前的源行情，过滤 `received_at<=cutoff` 和 `source_time<=cutoff`，不能直接拿现在的 `market_source_quote` 替代，否则会引入截止点之后的行情。

旧实现大意如下，示意 SQL 不用于线上执行：

```sql
SELECT price, source_time
FROM (
  (SELECT symbol_id, source_time, received_at, price, event_sequence
   FROM market_source_event
   WHERE tenant_id=:tenant AND symbol_id=:symbol
     AND received_at<=:cutoff AND source_time<=:cutoff
   ORDER BY source_time DESC, received_at DESC, event_sequence DESC LIMIT 1)
  UNION ALL
  (SELECT t.symbol_id, t.source_time, t.received_at, t.price, 0 AS event_sequence
   FROM market_source_tick t
   WHERE t.tenant_id=:tenant AND t.symbol_id=:symbol
     AND t.received_at<=:cutoff AND t.source_time<=:cutoff
     AND NOT EXISTS (
       SELECT 1 FROM market_source_event e
       WHERE e.tenant_id=t.tenant_id AND e.symbol_id=t.symbol_id
         AND e.source_time=t.source_time AND e.received_at=t.received_at
         AND e.price=t.price)
   ORDER BY source_time DESC, received_at DESC, event_sequence DESC LIMIT 1)
) candidates
ORDER BY source_time DESC, received_at DESC, event_sequence DESC LIMIT 1;
```

外层只看两条候选，不代表内部只读两条。tick 分支要先过滤镜像，再找一条没有镜像的 tick。如果最近乃至全部 tick 都有镜像，数据库就不断向前扫描和执行相关子查询，直到找到少量旧遗留行或者读完符合条件的历史。`LIMIT 1` 是“返回一条幸存记录”，不是“最多检查一条记录”。即使反连接查找有索引，也不能避免检查大量全部被过滤的候选。

对完整历史合并，去重有意义；对最大值，只要两个账本各选最新候选再比较即可。完全镜像事件与 tick 的 source_time/received_at/price 相同，事件的正 event_sequence 会胜过 tick 的 0；更老的非镜像 tick 也不可能胜过一个已存在的更新镜像。因此最新值查询没有必要先搜出一个非镜像 tick。

本次只在 latest 分支删除反连接，并按 tick 主键唯一性使用 `ORDER BY t.source_time DESC LIMIT 1`。事件排序、截止条件、租户/品种隔离、外层比较不变。全历史分支仍保留去重。修复后的外层对至多两条候选仍可能出现 `Using filesort`，这不是旧 tick 分支的大范围排序；不能声称所有 filesort 已消失。

### 3.3 为什么此前看似能用，后来容易失败

原因在于数据分布和历史体量：旧 tick 越来越多、事件镜像覆盖越完整，“找没有镜像的最新 tick”越可能没有结果，查询就退化为扫描历史；高并发的源行情写入和周期重试进一步增加竞争。任务前 24 秒只正常生成样本，到结束激活 hold 才进入这条查询，故表现为接近结束时突然卡住。

Git 中 `NOT EXISTS` 兼容查询至少已存在于 `e3bf094`（2026-09-22），`9c73957`（2026-10-05）给 latest 下推条件/取候选时仍沿用它；固定 15 秒租约代码在 `71ba923`（2026-10-05）集成。旧查询性能缺陷与后续严格租约组合更容易暴露问题，但提交时间不是生产首次故障或首次坏发布的证据。没有证据把某一次人工操作定为缺陷诞生点。

## 4. 为什么数据库最终不前进

### 4.1 报错位置和耗时位置不是一处

- 真正耗时的 SELECT：`ControlHoldService.java:37`，SQL 生成器 `ControlHistoryStore.java:559`，旧 tick 反连接在修复前 565 行。
- 报错的 UPDATE：`ControlHoldService.java:45`。
- 进入完成分支：`PersistentPriceControl.java:162`。
- 领取 15 秒租约：`MarketRuntime.java:39`。
- 拒绝过期写入：迁移 `V2026100304__market_engine_runtime.sql:394–404` 的 `s2_control_hold_u`，本轮已核对线上实际 trigger 定义。

不能看到日志里的 UPDATE 就优化 UPDATE 或解除 hold 表锁。UPDATE 是首先触碰隔离检查的报错位置，前面的 SELECT 已消耗了租约。

### 4.2 同一个物理事务的执行顺序

`ControlHistoryStore.locked` 的 TransactionTemplate 和嵌套调用共享事务；`MarketRuntime.locked` 遇到已存在的上下文直接执行，不在每层重新续租。完成路径的顺序是：

```text
BEGIN
  锁定 market_engine_runtime 的租户/品种行（FOR UPDATE）
  写入 owner、writer_generation、数据库当前时间 + 15000ms 租约
  在连接中设置 @mt705_s2_owner、@mt705_s2_fences
  INSERT 终点 market_control_sample；更新 market_mixed_minute
  Java 对象先标记 COMPLETED
  查询截止点最新源行情，旧 SQL 持续超过 15 秒
  UPDATE market_control_hold，触发器拒绝，ENGINE_FENCED
  后面的 task sampled_until/status UPDATE 不再执行
ROLLBACK
```

最终样本、OHLC、持有激活、此事务中的租约续期一起回滚，数据库仍是上一次提交的 RUNNING 和终点前一秒。Java 内存一度写过 COMPLETED，不等于数据库已有完成记录。后续调度重新读取同一个旧水位，在新事务中重新领租约并再次经过同一慢查询，于是长期不前进。

触发器同时核对 tenant/symbol、owner、writer_generation 和数据库时钟下的 lease_until。行锁保证串行化，但不让时间停止，也不自动延长租约。即使没有另一个写者接管，这个事务也会因耗时过长而过期。`ENGINE_FENCED` 不是证明管理员失去业务权限或另一个人抢了控盘。

这个隔离保护本身是必要的。删除触发器、允许旧 generation 写入，或者盲目把租约/前端超时调大，只会掩盖耗时和旧写者风险，不是本次根因修复。

### 4.3 为什么原始行情看起来也卡住

`PersistentPriceControl.sourceQuote` 438 行写源行情，439 行在同一事务推进旧任务，456 行发布配套快照；`ForexQuoteMarketService.acceptQuote` 448 行持久化成功后才在 458 行之后发布缓存行情。

因此完成逻辑失败时，同次写入的事件、源行情和快照也可能回滚，不发布新的成功缓存。这是源行情接收与控盘推进的事务耦合，不能只凭页面价格不动就断言上游供应商断流。批量别名共事务的路径还有一个品种失败影响本批次的代码风险；本次没有逐一证明其他别名均发生故障。

### 4.4 为什么恢复也失败

`ForexQuoteMarketService.manualControlLocked:1492` 先调用 `controls.stop`，`PersistentPriceControl.stopLocked:415` 又先调用 `advance`。只有它成功之后才在 418 行取消 flow、释放 hold，再清配置和记录 SOURCE。

这等于“先成功完成坏任务，才能退出坏任务”。本次已经在恢复失败堆栈中确认这个设计问题。目标启动、渐进恢复启动、停止保留价格也复用推进路径，可能同时受影响，不是给一个按钮单独加 try/catch 就能解决。

## 5. 是业务问题还是操作问题

### 5.1 已确认的业务语义

该任务的 `autoRestore=false`，同时持久化了 `restoreMode=GRADUAL` 等选项。前者是是否自动恢复的开关，后者不是“已经正在恢复”。按 `ControlRecoveryFlow.java:54–65`，正常目标完成后应进入 HOLDING，而不是自动 SOURCE。

HOLDING 是保留目标相对源行情的固定偏移，后续价格跟随原始行情变化；`ControlHoldService.observe:54–56` 实际计算 raw + offset，不是永久锁死某个绝对目标价。渐进恢复是动态跟随 raw 并衰减偏移，断源时暂停消费恢复时间；核心计算见 `ControlRecoveryFlow.java:75–103`。

`stopAndHold` 的业务含义是停止推进、保留当前控盘价格及偏移；“一键恢复原始行情”才应解除偏移并切换 SOURCE。手动配置 `offset=0` 不等于持久化 task/flow 已结束。本次修复后的当前 SOURCE 不意味着已发布的历史控盘 K 线全部删除或回滚。

这些语义需要产品明确展示，但不是最后一秒反复回滚的原因。如果产品要求目标完成后默认自动回源，应另行改变默认/显式选项，而不是把正常 HOLDING 当作这次故障。

### 5.2 不支持归责为用户误操作

目标和时长通过校验，计划已创建并执行到倒数第二点；正常到期本身即可触发故障，不需要用户多次点击。停止、恢复请求可以增加同一路径的负载，但不是产生慢查询缺陷的必要条件。没有证据表明修改偏移、刷新页面或选随机波动导致数据库被写坏。

截图键 `99a50e0c-530e-4ea7-91bf-ae54f38b61e9` 与实际任务键不同；核对时没有对应命令或任务。未取得该浏览器持久化的 action/payload，不能把它断言成一次渐进恢复，也不能断言用户重复启动了同一任务。

综合判断：目标输入业务本身没有被证明错误；执行链、应急退出和前后端状态协议存在代码/设计问题。用户操作更多是暴露和尝试解除问题，不是已证实的根因。

## 6. 独立缺口：为什么会一直“命令不存在”

### 6.1 客户端协议与恢复端点不一致

`AiControl.vue:308–348` 把 start 和 restore 都先保存为 PendingCommand；丢响应后保留原键，只调用 `/commands?requestKey=...`，防止重复启动。这种未知结果下不重发的保护方向正确。

但后端 `AdminAiControlController.java:72–77` 的 start 创建持久命令并返回 202，117–120 行的 restore 仍同步调用市场服务、返回 200，没有创建命令行。restore 响应丢失后，客户端就可能反复查询一个从来没有命令回执的请求键。这个代码缺口确定存在，但截图键属于哪条 action 尚不确定。

### 6.2 即使 START 返回 202，接受过程仍可能等待引擎

`MarketControlCommands.accept:49–63` 在 INSERT command 前，于 55 行进入 `store.locked(symbol)`，获取行情引擎行锁。正在执行的长事务会让“接受命令”也等待，命令行尚未提交，浏览器 10 秒先超时，随后查询暂时不存在。

这是代码可确认的风险，而不是已经关联到截图键的完整请求日志。重型计划计算虽已在后台锁外进行，并不意味着命令接受过程完全不受长引擎事务影响。

### 6.3 取消未知键没有永久取消凭证

`MarketControlCommands.cancelLocked:94–96` 只 UPDATE 已存在的 ACCEPTED/PREPARING/READY 行。若原请求尚未被接受，UPDATE 为 0 行，没有持久 tombstone；后到的原请求可能仍被接受。`AiControl.vue:342` 停止后又查原命令，54/432 行将未知结果纳入 busy 并禁用一键回源，会继续锁住页面。

不能用“404 就删 sessionStorage 并重新启动”补救，否则可能引入延迟接受的旧启动与新启动竞争。必须让服务端对取消原键给出持久、可查询、对晚到请求有效的终态。

### 6.4 状态新鲜度不足以证明进展

`PersistentPriceControl.status:633–634` 读取已提交 status_json；remainingSeconds 在 writer 的 `statusLocked:669` 生成，不是每次 GET 自动重新推进。因此旧快照会反复显示剩余 1 秒。

`FundingConversions.java:106–115` 的配套换汇行情可以复制原 status 再发布 runtime snapshot，提升 quoteVersion/committedAt，但不推进控制任务水位。仅靠 committedAt 更新不能证明控盘引擎健康。

`MarketRuntime.read(false):87–97` 仍会在执行报价读取时检查有效期和授权代际，过期则不可交易；本次没有证据证明过期报价实际成交。不能把 status 中旧 available=true 或刷新提交时间，夸大成已发生资金损失。

## 7. 彻底修复方案：分阶段，保留现有正确保护

### P0：把已验证修复变成正式、可持续发布

1. 合入 `ControlHistoryStore.sourceEvents` 最新值修复及差分测试；明确区分 latest 选最大值和完整历史去重，不全局删除 NOT EXISTS。
2. 用实际待发布的最终源码构建不可变镜像，核对当前候选 Compose 与运行版本的差异后再选择镜像，不能覆盖别人待发布的配置。验证容器重新创建后 JAR 仍包含修复。
3. 保留租户/品种、截止条件、同时间事件排序，以及 owner/generation/fence。禁止直接 UPDATE task 为完成、把任意当前 raw 当作旧截止行情或删除保护 trigger。

上一轮只是当前主后端容器内单 class 热修，已准备热修镜像但未切换正式 Compose。普通重启保留，按原/其他候选镜像重新创建可能丢失。当前部署不能称为永久修复完成。

### P1：解除应急恢复与旧任务成功完成的耦合

为现有服务实现一条明确的应急 SOURCE 转换，在短事务中：

- 校验后台角色、租户、品种与请求键。
- 递增 control_revision，使此前捕获的准备任务/写者不能在恢复之后重新激活。
- 持久取消相关待接受/准备命令，终止旧 task，释放 hold，flow 切换 SOURCE 策略，并记录操作和旧水位。
- 不先完成全部缺失采样，不激活一个马上要释放的 hold，也不为了恢复去扫描旧事件全账本。
- 已提交样本和历史不得删除；仅发布确定已经提交的范围。若正常历史最终化仍有待处理部分，明确记录并由现有后台机制有界重试，不能伪造补齐样本或把未提交终点发布出去。
- 新报价必须使用通过 freshness/排序校验的真实源；源不可用时只解除控盘策略并显示等待有效原始源，不把旧价强行标为实时可交易。

该方案不等于直接把 `advance` 移到 COMMIT 后或者捕获异常后强行保存，否则会破坏历史、取消和报价一致性。需要为 SOURCE 转换、历史发布和准备任务竞争补充事务测试后落地。

### P1：统一 START / RESTORE / CANCEL 的持久回执

复用现有 `market_control_command` 与 worker，不新建第二套队列：

1. 给现有命令明确 START/RESTORE 类型与对应不可变参数。两者都在短事务中落库接受、返回 202，再后台准备和激活。
2. 接受事务只处理请求键、参数哈希、队列额度、配置依据和必要的短行锁；不先持有行情 runtime 锁再写回执。激活时再核对最新配置、control_revision、owner/generation，过期依据按明确终态拒绝。
3. 原键取消即使尚无 command，也要持久记录取消 tombstone。按租户/品种/原键唯一串行化接受与取消，晚到 accept 必须拒绝，后台 READY 与取消竞争不能复活旧任务；保留鉴权和操作者审计。
4. 返回可查询的取消终态。客户端收到服务端终态和核实状态后再清 pending；超时或一次“命令不存在”不得擅自清除。
5. 如需要兼容已发送的旧同步恢复，可按原键查其真实 task，并显式返回旧协议结果；不能伪造一个已经存在的 202 command。全库没有记录但请求可能尚在途时，仍需 tombstone 解除不确定性。
6. 应急回源与启动使用不同的 busy 条件：未知启动结果阻止新增启动，但不能仅因为 pending 就封住有权限的取消/回源。最终状态由服务端确认，不靠页面跳过保护。

### P1：事务时间预算、隔离和失败重试

- 保留现有锁外计划预计算、每事务共享 512 点额度、SQL 每批 500 点、每租户单执行、两线程/有界队列和轮询公平性，不重写成新调度框架。
- 额外增加真实墙钟预算，按耗时而不只按样本数截断补偿。关键写事务应在 15 秒租约前有充足余量，长任务按已提交水位继续。
- 对最新 SELECT、行锁等待、事务和 HTTP 分别设置/监测期限。MySQL SELECT 限时不等于行锁/整个事务限时，不能只添加一个全局参数就宣称覆盖全部路径。
- 第一阶段继续在短 writer 事务内执行优化后的截止查询；如果以后移到锁外，必须校验能覆盖报价事件变化的 revision。现有 source_input_revision 由 candle dirty 写入递增，`quote()` 不递增，不能直接拿它证明锁外源 tick 候选仍有效。
- 规范化 SQLException 根因：识别 SQLState 45000、error 1644 和 ENGINE_FENCED。现有 `MarketControlCommands:131` 只匹配顶层消息前缀，不能可靠识别 JdbcTemplate 包装异常。
- 区分“本代际操作因期限耗尽”和“旧代际已被接管”。前者退出当前事务后按原键/原水位有限重试；后者禁止重新授权旧写者。
- 品种级失败退避和抖动，保留恢复后的下一次机会，防止每个 engine 轮次重复执行坏 SQL 占住同租户其他品种。现有行为是周期重试，不是无间隔紧循环；也不建议把所有失败品种永久排除。

建议初始验收目标：命令接受 p99 小于 500 ms，截止最新查询 p99 小于 250 ms，关键写事务 p99 小于 2 秒，并设置远小于 15 秒的单次硬预算。以上是待压测标定的目标，不是本次已测出的 p99，也不保证任意数据分布都只读 6 行。

### P1：状态和可观测性

- 分开显示控制水位、预期水位、最后成功控制推进时间、源事件时间、最后行情接收时间和 snapshot committedAt。
- 只读 GET 根据当前时间和持久水位计算 lag/degraded；不要通过 GET 做采样或续租。TARGET、停止截点、WAITING_SOURCE、重启暂停和 HOLDING 要按不同语义判断，不以价格是否改变作为健康唯一标准。
- 保留最后可信价格用于展示，但失效/延迟时明确不可交易；不能让换汇快照更新伪装成控制进展。
- 控盘请求记录 traceId、requestKey、action、tenant/symbol、command/task、HTTP 时长以及锁等待、查源、采样、hold 激活、快照、提交耗时。
- 记录 normalized fence reason、writer generation、租约剩余预算、水位前后变化和扫描指标；敏感 Token、密码、订单私密字段不入日志。
- 当前 `RequestLoggingFilter.java:22–33` 只覆盖图片路径，需要覆盖控盘路径才能把下一次 Broken pipe 关联到具体阶段。对客户端断开分类处理，避免通用异常处理器再次写已关闭响应产生重复噪声。

### P2：数据与索引治理，不先删表或堆索引

1. 明确事件账本为长期权威源，对 legacy-only 数据做分批回填、覆盖证明与逐项读取方审计；确认完整后才退出 tick 双写和兼容分支。MAX(source_time) 相同不是覆盖完整的证明。
2. 保留原始事件的接收时间和源时间，不覆盖不可变事件；评估迟到/同时间改价/重复事件语义，不能用粗暴合并丢掉审计事实。
3. 先修 SQL 形状，再按实际多租户分布评估 `(tenant_id,symbol_id,source_time,received_at,event_sequence)` 这类时间索引，以及 FORCE INDEX 在新索引下是否仍必要。必须以计划、实际读取和写入成本验收，不把候选索引直接当成已必要的结论。
4. 当前事件表索引元数据约 1.73 GB，已有兼容旧索引。检查冗余索引和写放大；仅返回一行时，不必优先加包含全部价格列的宽 covering index。
5. 统计刷新、历史归档和保留策略在不影响未完成任务截止查询、历史重建和审计的前提下分批实施。不要在线 TRUNCATE、全表删除镜像或用强制改价“修复”。

## 8. 必须通过的彻底验收

### 已执行，不能夸大范围

- 后端 103 项：102 通过、1 跳过、0 失败、0 错误。
- 客户端 5 项通过。
- 最新值差分测试覆盖截止时间、同时间事件、晚到事件、legacy-only、替换 tick、其他租户和其他品种；原实现曾有 1 项失败，修复后通过。
- 独立 MySQL 5.7 十万行镜像夹具证明扫描退化和优化结果等价。
- 直接复用生产迁移中的 hold UPDATE trigger，在保留 15 秒租约的最小 SQL 事务内明确注入 15.1 秒延迟；两次失败均回滚到终点前一秒，优化查询无额外延迟时成功提交。

延迟注入测试是机制复现，不是声称十万行查询自然花了 15 秒；也不是完整应用 MySQL 端到端重现。跳过的 `S1PairedProbeTest.measureActualFreezeAndHoldOnRestoredMillionSnapshot` 需要显式的百万行全应用配对夹具，本次未启用。

### 还需验收的场景

1. 百万级真实 MySQL 数据：100%/部分/无镜像、最新遗留 tick、截止边界、同源时间不同接收时间和价格、迟到、租户隔离；每个结果和独立正确性 oracle 一致。
2. 完整应用真实 trigger/租约：最后一秒遇到查询超过期限，失败不发布虚假进度，后续恢复在原水位完成；不是仅 H2 或最小 SQL 示例。
3. HTTP 在接受前/提交后断开，202 响应丢失，后台重启；原键只产生一次语义结果，查询可恢复，不重复启动。
4. 未接受原键取消、晚到 accept、PREPARING/READY 与取消竞争、恢复后旧 worker 回来，均不能复活控制任务。
5. 应急 SOURCE 不依赖 hold 激活成功；断源时不假装有实时可交易原价，已有提交历史仍可审计。
6. 最长 86,400 秒任务、较长宕机补偿、恢复过程断源/重启，均分批推进且保留 seed/checksum、历史和租户隔离。
7. 动态 raw 的渐进恢复在正常结束时 offset 归零；正常 HOLDING、STOP 和回源含义分别验证。
8. 过期报价不能交易，换汇快照不刷新控制进展或旧执行有效期。
9. 多品种/多租户持续负载及单品种故障下，其他品种仍有进展；压测 p99、实际扫描量、锁等待和队列，而不只看 HTTP 200。
10. 从正式不可变镜像重建容器仍有修复，并演练仅应用版本回滚；不能为了回退恢复整库旧快照。

## 9. 可复用入口与材料

本地隔离测试命令，不连接线上数据库、不读取线上凭据：

```powershell
$env:NODE_PATH = 'C:/workspace/fx/705/exchange-pc/node_modules'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'C:/workspace/fx/new/control-timeout-20261006-worktree/scripts/market/Test-ControlTimeout.ps1' -WithMysql
```

ASCII junction 指向当前工作树，解决现有 protoc 的中文路径问题；没有另拷贝一套源码。上述结果是上一轮已执行测试，本轮源码未再更改，仅重新核对报告和证据。

主要材料（私有证据目录包含会话等内容，不应整体分享或提交 Git）：

- 前轮恢复与热修报告：`C:/Users/徐乾妖/.codex/worktrees/3d6b/705/docs/control-target-recovery-timeout-20261006.md`。
- 本轮只读审计：`C:/Users/徐乾妖/.codex/worktrees/3d6b/705/rollback/control-timeout-20261006/deep-audit-20261006T153703Z/summary.json`，另存 SQL 计划、实际 trigger、日志和元数据。
- 决定性恢复堆栈：`C:/Users/徐乾妖/.codex/worktrees/3d6b/705/rollback/control-timeout-20261006/manual-failure.private.log`，907–928 行。
- 原水位、flow 选项、待激活 hold：同目录 `before-1.private.tsv`、`before-2.private.tsv`、`before-3.private.tsv`。
- 全阶段测试：`C:/Users/徐乾妖/.codex/worktrees/3d6b/705/reports/control-timeout-20261006-all/summary.json`；MySQL 细节在该目录 `mysql/result.json`。
- 测试入口：`C:/Users/徐乾妖/.codex/worktrees/3d6b/705/scripts/market/Test-ControlTimeout.ps1`。
- MySQL 最小复现：`C:/Users/徐乾妖/.codex/worktrees/3d6b/705/scripts/market/reproduce_control_lookup.py`。

最终结论：主慢查询和回滚循环已由线上证据、代码路径、本地机制复现及热修前后对照交叉确认，当前实时 SOURCE 已恢复；正式镜像持久化、应急退出解耦、恢复回执与取消协议、时间预算和完整 MySQL 故障验收仍需实施。不能把“现在恢复”与“彻底解决所有超时”混为一谈。
