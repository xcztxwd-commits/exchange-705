# 2026-11-03 K 线修复独立验收

## 结论和范围

独立验收确认，原来的完整页无关历史重放和重复分钟物化已受到限制。验收同时复现了一个**原修复前已存在的同类遗漏**：短行情页连续出现超过 `limit+2` 个隐藏 SOURCE 周期时，更老的已发布历史会被截断。本轮补齐了这一边界，并只更新生产主 API 的一个类。

在本报告列明的合成规模、有限并发、真实 MySQL 5.7 和生产只读样本内，未发现新的价格、恢复或排序保护回归。不能据此保证任意负载下永不排队，也不能认定所有业务全绿。既存 provider payload 数量断言、净权益历史采集失败、首页迷你曲线刷新失败、管理端三项静态检查失败，以及未执行的专用交易并发测试均保留。

生产没有创建测试账号、订单、资金变动或控盘。有效账号密码登录、JWT 后续访问和交易写入验证均在隔离夹具中完成。未使用客户凭据验证生产成功登录。

## 精确基线与发布制品

- 原修复源码：[74e6cf7](https://github.com/xcztxwd-commits/exchange-705/commit/74e6cf7180e31e81cf05c079e4d45a756e9e8bfe)，交接文档 `06b4e7c`；原修复 PR 为 [#36](https://github.com/xcztxwd-commits/exchange-705/pull/36)。
- 验收开始时，独立 SSH 验证运行镜像为 `exchange705/kline-window:20261103t191636z`，镜像 ID `sha256:2c2d7928171bd62608052417f97695449855b5740c6d51242cc01d75d743a95b`，JAR SHA256 `00043d1df1abdfc666a1d9162f32fc22a0637e0c9838a2c30b56560661f5246a`。没有把更旧交接中的镜像当成当前版本。
- 对实际运行 JAR 中两个目标类与已提交源码独立执行规范化 `javap -p -c` 比较，指令一致。另与原修复前精确 JAR 比较，原发布确实仅改变两个类，其余 1020 条目一致。
- 本轮源码：[f3b35ac](https://github.com/xcztxwd-commits/exchange-705/commit/f3b35ac504fea60b21ab36015e0320f194286128)。本地验证提交与 GitHub 导入提交的 Git tree 同为 `e590094452faae55d70eb3274402b6e4da2ad8a1`，源码逐字节一致。
- 本轮运行镜像：`exchange705/kline-acceptance:20261103t194912z`，ID `sha256:07e6f2ba75e2577e0cfde56990cd8e6cb917880d0a23ae984c9e4a2b5163c484`。
- 本轮 JAR SHA256：`47adc3f72ae0317f5b2037e40e3278841de40f99c492206cea5fe456602500bb`。
- 由验收开始时的精确线上包覆盖 `ControlledKlineMerger.class`，Java major 52；其余 **1021 条目逐字节一致**，保留线上内部类，并比较其指令与编译结果一致。没有用本地完整应用包覆盖生产。
- 主 API 于 **19:49:19 UTC** 开始切换，**19:50:03 UTC** 完成健康、JAR、环境变量、挂载、网络及 HostConfig 校验。仅改变主 API 镜像；两层网关各实例并发 4 的保护保留，配置 SHA256 仍为 `def111d924bae103d9718b86cdb8c632e9ce7d1bca9046bd32d4b24f4a9b2e53`。
- 精确前版镜像、JAR 和仅主 API 的 Compose 回滚配置已保留在服务器受限事故目录；含凭据的配置及原始诊断材料未上传仓库。

## 独立发现及最小修复

新测试构造 1 根原生线、9 个隐藏 SOURCE 周期和更老的 2 个已发布周期，请求 3 根。原修复前的精确 merger 基线与 `74e6cf7` 都只返回 1 根，候选目录的 `limit+2` 下界截掉了已发布历史。故这是既存边界，不能称为本轮原修复引入的回归。

补丁让短页在候选不可见时，继续按索引游标读取下一段更老目录，直到凑足有效页或目录耗尽。实际会话周期可能跨目录片段，通过已物化周期的时间边界避免再次重建。完整原生页仍用第 N 新真实周期作为下界，不回溯无关旧目录。

新增 10 项 H2 验收及 5 项 MySQL 验收覆盖：

- N=1/2/20/200/1000；无关混合分钟和源事件十倍增长；结果及恢复 revision 不变，必要分钟只重放一次。
- 9 个连续隐藏周期、523 个隐藏周期跨整页目录、偏移半小时的会话与相邻目录片段重叠；已发布历史补足且不重复重放。
- 1501 个分钟键跨 500 行分页；1202 条样本及同一 `received_at` 的源事件跨 500/1000 行边界，实际源事件分页为 **500/500/202**，最终收盘价 **61.200**。
- 真实 Hikari 池下的并发读取、排序异常、SQL 异常及真实 TCP 客户端取消后的事务和连接释放。

可见性仍交给原 `visibleMixedPage/visibleMixedAt`；没有新增另一套可见性判断，没有跳过隐藏且无 seed 的 future sequence 校验。发布范围、手动点、历史恢复覆盖及 revision、封存响应、legacy 歧义拒绝仍由原回归验证。

## 实际测试矩阵

以下是各批次实际 JUnit/Node 结果，批次之间有共享用例和不同数据库上的重复执行，**不能相加冒充不重复测试数量**。中途的构建竞争、隧道未保持、重复使用固定 ID 的夹具错误均已作废并纠正，未计为产品失败或通过。

| 批次 | 执行数 | 通过 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 已提交原修复独立串行复跑 | 95 | 92 | 1 | 0 | 2 |
| 最终核心回归，含新增 10 项 | 105 | 102 | 1 | 0 | 2 |
| 既有真实 MySQL 回归 | 14 | 14 | 0 | 0 | 0 |
| 新增真实 MySQL 工作量及连接池回归 | 5 | 5 | 0 | 0 | 0 |
| 真实 MySQL 历史恢复/撤销/不可变及外键 | 1 | 1 | 0 | 0 | 0 |
| 扩展业务回归 | 242 | 233 | 0 | 0 | 9 |
| 移动端 Node 测试 | 87 | 87 | 0 | 0 | 0 |
| PC Node 测试 | 122 | 122 | 0 | 0 | 0 |
| 管理/控制端 Node 测试 | 57 | 54 | 3 | 0 | 0 |

移动、PC、admin、control 四个提交版本的类型检查和生产构建全部通过；使用原有依赖，没有混入用户未提交的分享图片改动。

真实 MySQL 共 20 项通过，0 失败/错误/跳过。额外的恢复测试实际安装原有迁移中的 writer fence、不可变快照触发器及租户外键，执行恢复、撤销、revision=2、拒绝裸更新/删除/跨租户写入及原始源插入幂等。

原核心批次的两个 MySQL 条件跳过分别为 `S4KlineDifferentialTest` 的同接收时刻 legacy 诊断，以及 `LiveKlineAnchorRegressionTest` 的真实 MySQL 参数化锚点验证。专用 MySQL 批次已实际执行前者及后者的 1h/1d/1w 三组；这关闭了原环境缺口，核心 H2 批次本身仍如实记两个 skip。

新增发现 `S4KlineWindowMySqlTest` 的冻结旧 oracle 缺少已经存在的 `historyRestoreRevision`。独立 `74e6cf7` 基线也失败，15 组数据中 K 线字段一致，差别仅为新合成 symbol 的 revision=0。测试补上已知为零的预期元数据，完整响应比较、价格断言及 Handler 工作量断言全部保留，最终 15 组比较通过。未删除或放宽价格断言。

业务批次的 9 个 skip 全属于 `ContractCloseConcurrencyTest`，要求显式 `MARKET_ISOLATION_TEST=true` 和其专用 MySQL/Redis/HTTP 市场夹具。本轮没有伪造该条件；该组完整交易竞争验收仍未覆盖，不能由普通服务测试或 K 线池并发测试替代。

## 真实数据库工作量与连接占用

使用本会话创建的可销毁 MySQL **5.7.44**，专属 internal Docker 网络，无公开端口，经本机 SSH 隧道访问。每次连接验证服务器 UUID、专属数据库及 ownership marker；内存 768MB、CPU 1、连接上限 32；测试池上限 4。没有共享生产 schema、凭据或数据。

现代控盘夹具含 1000 根原生线、窗口内必要控制、窗口外旧控制，普通旧分钟由 100 增至 1000、其源事件由 1200 增至 12000。最后一轮观测如下：

| N | 增长前总 Handler read delta | 增长后 | 进入重放的源事件 |
| --- | ---: | ---: | ---: |
| 1 | 93 | 93 | 1 |
| 2 | 94 | 94 | 1 |
| 20 | 112 | 112 | 1 |
| 200 | 292 | 292 | 1 |
| 1000 | 1092 | 1092 | 1 |

Handler 数取同一个物理只读 RR 连接的 session counter 差，不是仅看返回行数；查询计划使用 `(tenant_id,symbol_id,received_at,event_sequence,source_time)` 范围索引。普通历史增长探针另达到 10101 个混合分钟，15 组冻结 oracle 比较全部相同；1m/10 的新读取 Handler 为 300→598→591，旧全目录读取为 675→3375→30375。

HTTP/WS/首页尺寸 200/2/20 各两路、每路两次，共 12 次并发读取，峰值活跃连接为 4，结束时 active=0、pending=0。future sequence 业务拒绝、真实 SQL 异常之后也均归零。

HTTP 客户端以 TCP RST 取消时，夹具证明物理事务仍保持 1 条连接，直到有限读取完成后释放，最终 active=0、pending=0，并观察到一次断连写失败。**客户端取消不会自动取消 JDBC 事务**；这是已验证的现有语义，不能宣称取消瞬间就释放连接。该测试使用最小 HTTP 适配器和真实 JDBC，不等同于完整生产 Tomcat/双网关取消链路。

这些是小规模合成数据、暖缓存和有限并发观察。不是磁盘冷读、P95、千万行全量容量或跨所有客户端的持久负载保证。

## 风险相关业务回归

- `MinimalFixRegressionTest` **51/51**：真实密码哈希校验、成功 HTTP 登录、签名 JWT 和后续受保护接口、失效会话拒绝、权限、钱包转账及竞争、服务端订单价格、过期/缺失报价拒绝、下单/平仓/结算、资金回滚等。数据库与账号完全隔离；行情输入等外部能力使用明确的测试替身。
- `TenantJwtBoundaryTest`、`TenantBoundaryTest`、`TenantCorsBoundaryTest`：跨租户/Host/token 拒绝、作用域及异常清理。
- `ExecutionQuoteTest`、`QuoteStateTest`、`MarketPushTest`、`MarketKlinePushTest`、`HomeSparklineCacheTest`：报价时效、提交后推送、同租户共享快照、WS 两根和首页二十根读取。
- 手动单图表、分钟、历史加载事务与价格测试：真实数据库历史分页、精确分钟、不借相邻价格、换算缺失的 425/队列行为、并发快照、异常后的通知回滚。
- 模拟交易和模拟租户：初始化、交易/平仓、资金守恒、幂等、过期报价拒绝、实盘路径隔离。
- 资金与后台：充值、提现、余额调整、资金锁顺序/检查点、admin 资金读取、平台控制读取及审计边界；均在夹具中写入。
- 日/周、周末/闭市、23 小时 DST 日、未来锚点、无原生锚点、深游标、发布/隐藏、手动点、恢复及封存，分别由 SQL 差分和历史保护回归覆盖；H2 与真实 MySQL 均有执行。

## 既存失败与实际影响

1. `ControlRecoveryFlowTest` 继承的 `providerQuoteTimeTailsCannotOverwriteControlledMinuteCloseOrCreateExtraCandles`：原始 provider payload 数量期望 3、实际 1。独立在原修复前 merger、`74e6cf7` 和本轮候选三套基线上复现同一失败。前面的 K 线根数、收盘/最高价 95 均通过，失败发生在原始供应商尾项留存数量；没有删除断言或改动该留存策略。
2. 生产 `equity-capture` 持续失败，发布前 19:16 起的审计记录和更早的 MySQL digest 均有证据。只读检查发现实际估值 `observed_at` 最后仍为 **2026-10-23 08:25:00.148 UTC**，采集 watermark 也停在该分钟。较新的 bucket_start 不能证明生成了新估值。故**净权益历史的新观测采集没有正常推进**，相关历史曲线的新鲜度受影响。失败 digest 涉及历史 quote batch/分钟写入，未获得可确证具体字段或锁问题的 MySQL 错误号，本轮不臆测其进一步根因，也不扩大修改或掩盖异常。它不经过本次 merger；当前资金和订单服务的隔离回归通过，不能替代对生产所有账户的核对。
3. **首页迷你曲线业务失败**：生产抽查 JPY=X/BTCUSDT/AAPL 的三项 points 均为 0，`source_unavailable`，虽然 HTTP 为 200。独立对真实 `HomeSparklineRefreshJob`、`TenantJobRunner`、cache 和数据库消费者做隔离调用：原修复前、`74e6cf7`、本轮候选三套基线均为“直接读取 20 根，定时任务生成 0 点”，捕获 `Market consumer requires a read-only RR transaction`。生产 job/cache/runner 的规范化字节码与所测源码一致，实际 production `readSnapshot` 指令也一致。任务用 `TenantJobRunner.each` 包在写事务内，消费者拒绝加入；cache 将异常转为 `source_unavailable`。这是原窗口算法修复前就存在的事务边界问题，本轮未修改这些类，不属于新回归，也不把 mock cache 单测通过当作真实定时刷新成功。遵守本任务不扩张处理全部无关既存问题的边界，保留并单独报告；首页小曲线当前不能判定为正常。
4. 管理端三项既存静态检查：`Users.vue` 分页默认值、`AnnouncementTemplateEditor.vue` 权限声明、`userIdentityColumns.test.mjs` 的用户 ID/邮箱列规则。修复前后相关前端源码及测试完全相同；未为本次验收顺手修改这些无关功能。

## 线上验证与浏览器

生产完成 29 次主验收探测，另有 4 次 BTCUSDT/AAPL 暖读。**传输成功与业务成功分开**：首页曲线的 HTTP 200 对应上述业务失败。没有把 29 次探测写成 29 项业务全绿。

| 检查 | 实际结果 |
| --- | --- |
| 七周期首轮 | 前六周期 200 根、1w 199 根；多项 pending，日/周提示缺原生锚点 |
| 七周期热读 | 全部 200 根、pending=false、无 missingData，时间递增且去重 |
| 5m/1h 已发布历史 | 各 20 根，分别保留 1/3 个发布标记；redis 别名完整响应相同，保留 protected_control_history |
| 外汇/加密资产/美股 | JPY=X、BTCUSDT、AAPL 报价 available=true、tradeAvailable=true、stale=false；BTC/AAPL 5m 暖读均 20 根、pending=false |
| 首页 mini 曲线 | HTTP 200，三项 points 全空，source_unavailable；业务失败，三基线均可复现 |
| 四路 K 线并发 | 15m/30m/1h/1d 各 200 根，0.166–2.928 秒 |
| 并发中的数据库登录路径 | 0.442 秒返回预期不存在账号 400；只证明数据库路径可响应，不是有效密码登录成功 |
| 真实生产只读前后差分 | 七周期最近页和两种发布历史页，共 9 对同一只读 RR 快照；完整响应逐字段相同，服务器 tx_read_only=1 |
| 等待与日志 | 并发中及结束后 Hikari getConnection 等待栈为 0；新 pool timeout、字节码链接错误、历史排序拒绝均为 0；既存 equity-capture SQL 异常仍出现 |
| 受保护服务 | 原有其他 69 个容器的 ID、镜像及启动时间全部不变；网关配置不变 |

只读差分使用持久化周期行情，周线 199 根；HTTP 热读含供应商缓存补齐的 200 根，二者并非同一数据来源。日/周仍各重放 305 条必要事件，发布历史的 5m/1h 分别为 44/48 条，与验收开始时版本相同，不追求将必要重建降为零。

生产仅做有限只读探测；真实客户登录、生产订单/资金写入、完整登录后后台浏览器业务均不在本轮生产验证范围。

浏览器使用捆绑 Playwright 和系统 Chrome，桌面 1280×800、移动 390×844。当前会话没有 Browser runtime skill；没有另装浏览器依赖。记录了页面身份、非空内容、控制台、截图和实际交互，未把匿名管理/控制登录页可打开当作后台业务通过。

| 浏览器检查 | 结果及边界 |
| --- | --- |
| 页面身份/非空/无框架错误覆盖 | PC、移动、admin、control 入口均符合预期 |
| 控制台 | 四个入口无相关 warning/error/pageerror |
| PC 交互 | 5m 切至 1h，HTTP 返回 200 根并转为非 pending；打开真实登录表单；图表截图可见 |
| 真实 WS | 上线后桌面会话收到 15 个含 K 线的推送帧 |
| 移动交互 | 关闭平台公告，首页点 USD/JPY 后按现有鉴权规则进入登录页；未在生产使用凭据进入移动交易页 |
| admin/control | 登录表单分别显示 2/3 个输入；没有进行客户或管理账号登录，授权后台读取由隔离 MockMvc 回归覆盖 |
| 截图 | 桌面图表/登录、移动首页/登录、管理/控制入口均留有私有截图，已检查桌面和移动实际渲染 |

## 复跑与证据

新增测试见 [KlineIndependentAcceptanceTest](../exchange-backend/src/test/java/com/gtcfesk/exchange/market/KlineIndependentAcceptanceTest.java) 和 [KlineWindowPoolMySqlTest](../exchange-backend/src/test/java/com/gtcfesk/exchange/market/KlineWindowPoolMySqlTest.java)。已有市场 SQL/历史/业务测试保持断言。独立源码构建使用 Java 17、Maven 3.9.9，生产覆盖类按 Java 8 编译。

```bash
mvn -o -f exchange-backend/pom.xml \
  -Dtest=KlineReadWindowTest,KlineIndependentAcceptanceTest,S4BatchHistoryTest,S2CanonicalKlineMergerTest,HistoryOrderingTest,ControlRecoveryFlowTest,S4KlineDifferentialTest,LiveKlineAnchorRegressionTest,HistorySourceRestoreTest,MarketKlinePushTest,HomeSparklineCacheTest test
```

真实 MySQL 测试必须配置原有的 `S4_HISTORY_JDBC/UUID/OWNER/USER/PASSWORD` 专属夹具和 `KLINE_GAP_MYSQL_URL/PASSWORD`，不能指向业务库；新增池测试沿用服务器身份及 ownership guard。不配置条件时的 skip 不构成通过。

原始 JUnit XML、SQL/Handler 记录、三基线失败对照、制品 manifest、只读快照结果、浏览器截图、脱敏汇总和生产诊断日志保存在本机被忽略的私有验收目录。配置、日志、账号和凭据不进入 GitHub。资源清理于 19:53 UTC 完成：核对本会话 container ID、network ID 和 ownership label 后，删除专属 MySQL 容器及匿名卷、internal 网络，终止该专属 SSH 隧道，移除临时密码文件、远端 probe 上传和未使用的构建镜像标签；没有删除其他资源。当前主 API 镜像和精确前版回滚镜像/JAR/Compose 继续保留。最终检查本会话测试容器为 0，用户原有分享图片及附件文件的 SHA256 全部与验收开始时一致。

本轮创建的干净临时工作树在证据归档后清理；所有原始验收证据保留于主工作区的受限私有目录。没有提交用户分享图片脏文件、私有部署信息、业务数据或凭据。
