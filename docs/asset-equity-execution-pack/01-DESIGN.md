# 净权益分钟历史与分层归集：最终实施规格

日期：2026-09-27。工作目录：`C:/workspace/fx/705`。状态：待实现；文档不是已上线功能的证明。

本文件是新会话的权威实施规格，替代此前方案中“只统计可用＋冻结资金”的口径。只实施净权益估值、历史采集归集、个人中心图表和直接相关测试；不扩展无关业务。

## 1. 用户已确认的目标

1. 分钟记录保存完整净权益：计入全部可估值的持仓浮盈浮亏，扣除全部实际未清偿负债，不能只记钱包余额。
2. 原始分钟记录保留，归集不删除源数据。小时从分钟归集，4 小时从小时归集，日从 4 小时归集。
3. 日图最近 24 小时/1 分钟；周图最近 7 天/1 小时；月图最近 30 天/4 小时；年图最近 365 天/1 天。
4. 主曲线使用归集 close；保留 open/high/low 及真实时间供极值显示和上级归集。
5. 每个周期主体读取对应归集表，不在每次请求时扫描完整的长期分钟数据。
6. 保留当前绿色像素渐变、边缘贴齐、长按拖动、前亮后暗、动画、金额隐藏、周期收益与括号百分比。不恢复已删除的纵轴价格和说明文字。
7. 期初基数缺失或为 0，使用最早可发现的非零正权益记录作为回退基数；净权益可能为负，新实现对非正基数都执行相同回退，避免负本金造成收益率符号倒置。没有正基数则返回不可计算，不伪造 100%。

## 2. 已核查的代码事实

所有路径以 `C:/workspace/fx/705/` 为根。新会话先复核实际文件，不能假定此后没有其他人的修改。

| 文件 | 已确认事实/实施用途 |
| --- | --- |
| `exchange-backend/src/main/java/com/gtcfesk/exchange/user/AssetHistoryService.java` | 当前 capture 为 fixedDelay=60000；只采集 FUND/CONTRACT/OPTION 的 available+frozen；prune 删除 370 天前数据；年图是 2 天；已有自然周期收益及最早正快照回退 |
| `exchange-backend/src/main/java/com/gtcfesk/exchange/user/AssetHistoryController.java` | 已有认证 GET /api/user/asset-history，身份取 Authentication |
| `exchange-backend/src/main/java/com/gtcfesk/exchange/entity/AssetSnapshot.java` | 旧表仅保存 total 和 captured_at；不能据此恢复历史浮盈亏与负债 |
| `exchange-backend/src/main/java/com/gtcfesk/exchange/entity/AssetAccount.java` | 三种账户、available/frozen、乐观锁；保证金和部分预留手续费已经包含在 frozen 中 |
| `exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ContractOrderService.java` | 新订单按 quantity×lotSize 计算报价币盈亏，再换算 USD；历史 lotSize=null 使用原杠杆乘数；新单预留 fee 在平仓结算时收取，历史单采用不同退款规则 |
| `exchange-backend/src/main/java/com/gtcfesk/exchange/market/ForexQuoteMarketService.java` | snapshotPrice 提供版本化行情、时间/有效性、换汇信息；有 freshPrice、conversion、requireConversionRate。不能把旧 order.currentPrice 当成新行情 |
| `exchange-backend/src/main/java/com/gtcfesk/exchange/trade/OptionOrderService.java` | 期限方向型产品，冻结 amount；到期按方向和配置 payout 比例结算，不是普通线性合约 |
| `exchange-frontend/src/views/Orders.vue` | calculateOptionProfit 当前按方向判定给出全额 payout 式参考盈亏；不能把它当作已有可交易的期权公允价格 |
| `exchange-backend/src/main/java/com/gtcfesk/exchange/admin/LoanReviewService.java` | APPROVED 操作写 approvedAt，并在同一事务把贷款本金加到 FUND；SIGNED 本身未放款 |
| `exchange-backend/src/main/java/com/gtcfesk/exchange/user/LoanService.java` | getTotalLoanAmount 仅排除 COMPLETED/REJECTED，会把申请/签约状态算进去，不能直接用作净权益负债；earlyRepayment 按实际使用整天和 freeDays 计算利息 |
| `exchange-backend/src/main/java/com/gtcfesk/exchange/entity/LoanRecord.java` | amount、totalInterest、repaymentAmount、overdueFee、approvedAt、actualRepaymentAt；未见部分还款余额字段 |
| `exchange-backend/src/main/java/com/gtcfesk/exchange/user/FinancialService.java` | 理财本金转入 frozen，不能再将 financial_order 本金重复计为独立资产 |
| `exchange-backend/src/main/java/com/gtcfesk/exchange/entity/FinancialYieldRecord.java` | PENDING/PAID。是否为已确认无条件应收与单纯待审核奖励，必须按实际发放流程核实 |
| `exchange-backend/src/main/java/com/gtcfesk/exchange/user/WithdrawController.java` | 提现申请金额及费用先冻结；不能同时把冻结金额和未出账申请再扣一次 |
| `exchange-frontend/src/components/AssetPixelChart.vue` | 10 秒轮询，约 68 列像素，长按及请求版本保护已存在；当前金额校验不支持 netEquity=null，需升级 |
| `exchange-frontend/src/utils/assetPixelWindow.ts` | 时间查找/显示补零/纵向范围辅助；已有负值价格范围处理，但必须补负净权益测试 |
| `exchange-backend/src/main/resources/application.yml`、`compose.yaml` | Java 8 / Spring Boot 2.7.18 / MySQL 5.7 / Redis；JPA ddl-auto=update，不能只依赖自动建表做迁移 |

## 3. 净权益的唯一服务端公式

```text
walletBalance = Σ(三个账户 available + frozen)
unrealizedPnl = 所有未结持仓按产品类型计算的浮盈浮亏，换算为 USD
receivables   = 已确认、尚未入账、且未包含在 walletBalance 的应收资产
liabilities   = 未清偿放款本金 + 截至采样时点已计提未付利息
              + 已生效未付逾期费用 + 尚未从余额扣除的已发生交易费用
              + 代码核查确认存在的其他未清偿债务
netEquity     = walletBalance + unrealizedPnl + receivables - liabilities
```

全部金额采用 BigDecimal/DECIMAL(32,16)，统一 USD；API 返回十进制字符串。负净权益真实保存，不 clamp 到 0。中间运算不使用 double，不额外把名义持仓本金当资产。

新增一个纯读取的 EquityValuationService，分钟采集、图表 live 点和个人中心顶部总额都调用相同计算逻辑。不得调用有平仓、强平、撮合、放款、收益发放等副作用的业务入口来“获取权益”。估值任务不修改资产、订单状态或负债本金。

### 3.1 合约浮盈亏

- 仅 OPEN 持仓参加；PENDING、CLOSED、CANCELLED 不计算未实现盈亏。
- 新单报价币盈亏：BUY=(估值价-openPrice)×quantity×lotSize；SELL 反向。
- lotSize=null 的存量订单保留现有结算兼容规则，不统一改乘 lotSize 或再多乘一次 leverage。
- 按订单 quoteCurrency/quoteSource 使用同一采样批次的有效 USD 转换率。不能把非 USD 报价币盈亏直接与 USD 余额相加，也不能默认所有币种汇率为 1。
- 将现有纯计算部分提取复用并加回归测试；禁止为权益统计复制一套未来会与结算公式漂移的实现。
- 优先使用已有服务端权威估值价格源，记录其类型与来源。若现有源只有 last/trade price，必须如实标明，不能凭空称为 mark price；不为本任务接新交易所、改行情源或修改价格控制逻辑。
- 保证金已在 frozen 中，只加浮盈亏，不重复加 margin。
- 新 OPEN 单已经确认应收取但仍冻结未扣的手续费应从权益扣除一次；旧单按现有实际收费规则判断，不能一概扣 fee。挂单可退的费用预留不等于已发生费用。已入账扣费不能重复扣。

### 3.2 期限方向型订单是必须处理的估值边界

当前产品并非普通线性期权；尚未发现可交易中间价、提前平仓报价或已定义的公允价值模型。现有前端“当前方向胜出则 amount×profitRate，否则 -amount×lossRate”只是到期假设式参考盈亏，不能不加说明地称为完整公允净权益。

实施规则：

1. 先在现有代码/配置中核实是否已经存在可靠的持仓估值/提前赎回价格。存在则复用，并保存方法、参数和时间。
2. 有公允价值 V 且本金 amount 已包含在 frozen 时，只加 `V-amount`，不能再加整个 V。
3. 若只有 payout 参考公式，可将该值保留为 `indicativeOptionPnl` 参考字段，但不得当成 COMPLETE 净权益的已确定组成部分。不能采用后台预设盈亏标志估算资产价值。
4. 存在未到期订单却没有有效估值时，该分钟仍保存已知组成部分，net_equity=NULL、valuation_status=INCOMPLETE、reason=OPTION_VALUATION_UNAVAILABLE；不以 0 隐瞒该项，也不宣称已实现所有产品的完整净权益。
5. 此时新会话应就“该产品使用什么估值口径”提出一个具体必要问题，其他已确定的采集/归集/测试继续完成。必须在完整口径发布前解决；不得在真实账户上自动切换到不完整净权益。

这是已识别的数据/产品缺口，不是让执行者自行猜测的自由选项。

### 3.3 借款和其他负债

- 当前放款证据是 approvedAt 与 APPROVED 放款事务；PENDING、SIGNED（未放款）、REJECTED 不形成已放款本金负债。
- 已放款且未还清的 APPROVED/OVERDUE 等记录计入剩余本金。状态异常但有放款且无清偿证据的不能静默忽略，需标记数据质量异常。
- COMPLETED/有合法清偿证据的不再扣本金；当前未见部分还款字段，不自行生成部分还款能力。若实际代码已新增部分还款则必须按剩余本金计算。
- 利息按截至 observed_at 已产生、尚未支付的金额计算；从 approvedAt 起算并复用现有 freeDays/日利率/整天口径，不能把申请时生成的整个贷款期限 totalInterest 提前全部扣掉。
- 逾期费用仅计入真实已生效、尚未清偿且未包含在其他债务项中的金额。核实 overdueFee 的计算及更新来源；若缺少可确定的计提规则，记录缺口并提出必要问题，不新增任意罚息算法或计费任务。
- 不把 repaymentAmount、amount、totalInterest 和 overdueFee 简单相加，防止总额和组成项重复计算。
- 已放款金额进入 walletBalance 后，同额本金列为负债，所以借款本身不凭空增加净权益；还款时现金减少且负债解除，不重复形成损失。
- 负账户余额已在 walletBalance 里体现，不能再次取其绝对值作为同一笔负债扣除。

### 3.4 其他资产/应收

- 理财本金已冻结，不再加一次；已到账收益已在余额里，不再加一次。
- PENDING yield 只有经过核查属于已确认应收而非可能驳回的奖励时才计入 receivables；未知应收性质不能默认为确定资产。不存在独立应收时此项为真实 0。
- 充值审核前不算已到账现金，赠送实际入账后由余额反映；估值任务不审批、不发放、不补资金。
- 已冻结待提现资产是否仍归用户应与实际出账/撤销流程一致，不能仅凭申请创建就额外减去冻结本金。

### 3.5 行情新鲜度与一致性

- 一批用户复用相同 symbol、quote source、currency 的行情快照，不每用户调用外网；直接用现有内存/Redis 行情服务。
- 保存行情的报价时间、来源、版本和汇率；持仓记录量大时按采样批次保存去重后的行情证据，分钟记录引用批次，而非重复写全部原始报文。
- 先准备有界行情快照，再在短只读一致性事务中读取账户/持仓/负债。同一用户使用同一数据库快照，防止读到平仓后的余额又加平仓前的浮盈亏。
- 最终读取发现新持仓品种不在行情集合内时重试该用户/批次，或标记不完整；不能漏算。定价时间与资产读取时间偏差设明确阈值，使用现有行情时效设置，不自行放宽。
- 行情断流/过期、汇率缺失、规则未知时保存 NULL 权益及原因；不把浮盈亏设为 0，不复制上一分钟为“新鲜实测”。界面可以保留最后一次有效权益，但必须带实际更新时间和 stale 状态。
- 休市期间使用官方最后有效收盘值需要明确的 CLOSED_MARKET 估值规则及原始时间；不能把休市识别当成无限期接受陈旧价格。无该规则时如实标记不可估值。

## 4. 历史表与口径版本

### 4.1 asset_history_1m：每分钟原始估值记录

最少保存：

```text
user_id, basis_version='net_equity_v1', bucket_start, observed_at
wallet_balance, contract_unrealized_pnl, option_unrealized_pnl
receivables, loan_principal, accrued_interest, overdue_fees, accrued_trading_fees
other_liabilities, liabilities_total, net_equity (允许 NULL)
valuation_status, reason_code, quote_batch_id/valuation_evidence
created_at
```

NULL 代表无法完整估值；真实零必须是 0。正负净权益都允许。字段中的总负债与组成项满足可核对关系；若证据表是最简实现所需，增加一张按批次去重的估值证据表，否则用有限结构 JSON 保存必要证据，不增加通用审计平台。

唯一键 `(user_id,basis_version,bucket_start)`；批量归集索引 `(basis_version,bucket_start,user_id)`。每分钟第一条成功观测固定不覆盖；计算失败且已经保存当时账户及行情证据时可用原证据重算，不能拿新余额伪装成旧观测。

采集现存用户，不依赖登录或页面打开；没有资产/订单/负债的已存在用户记录真实 0，注册前/系统采集前不补造。采集任务超过分钟边界时记录真实时间，不把后来的数值回填前一分钟。

### 4.2 asset_history_1h / asset_history_4h / asset_history_1d

同一结构，保存净权益而非旧余额的：

```text
user_id, basis_version, bucket_start, bucket_end
open, high, low, close
open_at, high_at, low_at, close_at
source_count, valid_sample_count, invalid_sample_count, expected_sample_count
finalized, quality, source_through, updated_at
```

唯一键 `(user_id,basis_version,bucket_start)`；批量索引 `(basis_version,bucket_start,user_id)`。OHLC 只取有效 net_equity，负数参加 min/max；NULL 不当 0。全部无有效样本时不得写四个 0，可保留空值和缺失质量元数据。已封闭但有漏采/不可估值仍为 PARTIAL，不冒充完整。

小时从分钟归集；4 小时从小时的 open/high/low/close 归集；日从 4 小时归集。上层 high=max(child.high)、low=min(child.low)，绝不只从 child.close 取极值。极值同值取最早原始时间，close 按真实观测时间取最后，不按最大 ID 代替。

每次重算 UPSERT，不重复累加；各级不得混用 basis_version。曲线画 close，区间极值单独标记，不把未发生在收盘时的 high 伪插为 close。

### 4.3 基准及任务进度

- asset_history_baseline：`(user_id,basis_version)` 唯一，保存采集起点和该口径最早正净权益及时间。
- asset_history_job_state：任务名、口径版本、区间水位、用户游标、成功时间及错误。结果和游标同一批次事务提交。
- 所有分钟及下级记录均保留，无自动删除；取消本功能原来的 370 天清理，但先做迁移与备份保护，不删除别的业务清理任务。

## 5. 时间、定时任务、当前桶

存储 UTC 固定桶，左闭右开；1m=60000、1h=3600000、4h=14400000、1d=86400000 毫秒。4h 为 UTC 00/04/08/12/16/20 起点，日为 UTC 00 点起点。页面按配置时区显示，不能把 UTC 日桶冒充本地自然日。

| 任务 | Spring 六字段 cron（UTC） | 职责 |
| --- | --- | --- |
| 分钟估值采集 | `5 * * * * *` | 保存当时账户、浮盈亏、负债及净权益；更新有效正值基准 |
| 小时封闭 | `15 2 * * * *` | 分钟表归集已结束小时及断点积压 |
| 4h 封闭 | `30 4 0/4 * * *` | 从已处理的小时表归集 |
| 日封闭 | `45 8 0 * * *` | 从已处理的 4h 表归集 |
| 恢复归集 | `0 */5 * * * *` | 按断点补算；不补造停机期间的账户快照 |

分钟提交后异步更新当前小时 DRAFT，再更新当前 4h 和当前日 DRAFT；写同一粒度表，finalized=false。正式任务负责封闭。这能让年图当天的数据随每分钟权益变动，而不是等第二天。

采集与归集使用分离的有界执行通道；归集同一桶串行并合并重复任务。每用户当前桶通常最多读 60 条分钟、4 条小时、6 条 4h，必须批量查，禁止 N×70 次 SQL。

上级封闭检查下级处理水位，不仅依赖触发时间；旧 DRAFT 不能覆盖 FINAL。修复历史必须从分钟依次重算父桶，失效缓存，并保留规则版本。

Spring + 现有 MySQL 即可，无需新调度框架或消息队列。使用数据库任务锁加唯一键；若用 MySQL GET_LOCK，锁/受保护操作/释放必须处于同一物理连接，归还池前 finally 释放，丢锁停止。每批短事务且持锁运行路径唯一，不能在另一个异步连接继续写。

## 6. API 与前端

维持 `/api/user/asset-history?range=1D|1W|1M|1Y`，服务端认证和表名白名单不变。新返回包含 schemaVersion、basisVersion、净权益组件、估值状态、更新时间、各桶 OHLC/实际时间/quality、真实极值、缺失区间及独立 live 点。

`total` 若保留兼容名称，其含义明确为 netEquity；不同时让顶部显示 walletBalance、曲线显示 netEquity。旧 PC/其他调用方如果复用本接口，必须保证结构兼容或显式版本化，不能悄悄破坏；不顺便重做 PC 页面。

范围路由：1D/1m 表，1W/1h 表，1M/4h 表，1Y/1d 表。点数约 1440/168/180/365 加首尾部分桶，视觉保持约 68 列；长按查完整底层数据，年图显示真实日桶及 close_at，不假称能查任意分钟。

滚动窗口首桶可能被截断，例如七天前 15:37。主数据查对应表，只对首桶和必要的未完成尾桶有界读取下级修正，避免把 15:10 的峰值算进窗口；禁止为完整月份扫描全量分钟。每次请求用同一个 asOf，拒绝未来观测。

图表坐标容纳负权益；零只是参考线，不是下限。金额隐藏时净权益组件/长按细节一起隐藏。

区分三种空值：

- 实际观测权益 0：有效数据，参加归集。
- 从未记录的历史：沿用用户要求的显示层补零，标识为填充，不写数据库/不参与真实极值或本金。
- 当时有资产但报价或估值不完整：不能按“账户为 0”处理，保留上次有效显示或断开曲线并携带质量状态；不新增长段页面说明，只使用必要状态/长按细节。

前端更新接口类型、NULL 校验、极值来源、金额符号、负值缩放和数据质量处理。维持现有周期请求竞态保护、长按期间时间窗稳定及无障碍键盘行为。

## 7. 收益数字：明确边界，禁止暗改

本次用户明确修改的是“资金历史/总资产”的净权益口径，未明确要求将顶部自然周期已实现收益改为全权益回报。当前 income 是已结算盈亏与已到账收益，先保持该分子及自然周期统计方式；API 继续明确 incomeBasis，不能称作含浮盈亏的周期总收益。

百分比分母改为同版本净权益期初；非正/缺失时回退同版本最早正净权益，金额和基数均保存来源及时间。缺少可用净权益基数时返回 NULL，不借用旧余额本金伪装净权益本金。

若业务要求 income 也计入浮盈亏及债务费用变化，需要用户另外确认并实现：区分外部充值/提现/赠送与投资回报，处理期初/期末未实现盈亏差额、费用计提与已实现结转，防止重复计算。当前缺统一资金流水，不可简单把净权益差额标成交易收益。本执行包不包含这个额外改造。

## 8. 旧数据不能直接迁移成完整净权益

旧 asset_snapshot 只有钱包余额，缺当时的浮盈亏、有效行情、负债及费用明细。绝对禁止把原 total 直接复制成 netEquity 并标 COMPLETE。

采用以下迁移策略：

1. 备份旧数据，保留为 `wallet_balance_v1` 遗留记录，不破坏现有用户历史。
2. 新增表从切换时刻 T 开始采集 `net_equity_v1`。四级归集和 baseline 按版本隔离。
3. 能以完整、可验证的当时订单/成交/行情/汇率/放款/还款/费用证据重建的区间才可回填，保存 evidence 和 RECONSTRUCTED 来源；不能用今天行情或当前贷款状态推过去。
4. 无法重建的过去净权益保持未知。旧余额历史可在后端兼容查询中保留，但不能和新净权益拼成同一条无区分实线。
5. 原先“最早正余额”不自动变成“最早正净权益”；基准按版本重新建立。
6. 发布前对临界 T 的采样生产者做唯一切换，防重复或漏点；保留兼容读取开关。

## 9. 最小实现顺序

1. 读取本包，检查工作区变化，只备份即将改动的文件，不 reset/stash/clean 他人代码。
2. 完成只读估值规则清单，优先解决期权估值、债务计提不确定项；能确定的部分按代码复用，不问已经确认的周期/样式。
3. 实现纯估值服务和单元测试，验证重复计费、借款净额、平仓结转、报价时效及负净权益。
4. 新建版本化 DDL、四级表、baseline/job_state、分钟采集和数据质量字段。
5. 实现一个通用 OHLC 归集算法、当前 DRAFT、三级 FINAL、水位/锁/恢复。
6. 改查询路由和 API、个人中心顶部金额、现有图表；不增加新 UI 风格或依赖。
7. 补 MySQL 5.7 幂等/并发/NULL/索引集成测试和前端回归。
8. 执行备份、测试、局部构建、明确发布前检查；仅在完整口径可验证且用户已授权本地发布时更新 backend/mobile。执行命令见 02-EXECUTION.md。

## 10. 必须通过的验收

### 净权益数值

- 余额 1000，无持仓/债务：权益 1000。
- 同余额下合约浮盈 100：权益 1100；浮亏 80：权益 920，无现金操作也产生不同分钟记录。
- 贷款放款 500 同时新增未偿本金 500：不因本金导致权益增加；已计提利息 10 应使权益减少 10。
- 用账户资金偿还本金与已计提利息时，债务同步解除，不能把已扣的利息再计损失一次。
- margin、理财本金、提现冻结不重复加减；借款申请/未放款签约不扣实际本金负债。
- 合约新/旧订单、BUY/SELL、非 USD 换汇、已付费/预留可退费/已发生未扣费分别测试。
- 平仓前未实现利润转为余额后净权益连续，除真实价格/费用差异外不得翻倍或消失。
- 期限订单有验证估值时参与；无有效方法不得填 0 并标完整。参考 payout 与真正公允值必须区别。
- 负净权益真实存储；行情断流/过期、休市、FX 缺失、负债异常必须有质量状态。

### 存储及归集

- 每用户每分钟至多一条，各级唯一键有效，基准按版本隔离。
- 小时/4h/日极值及原始时间与有效分钟直接扫描一致；NULL 不当 0，计数不翻倍。
- 停机只补可归集的已有记录，不能补造分钟资产；DRAFT 不覆盖 FINAL，上级不越过下级水位。
- 所有分钟数据保留；历史来源、金额口径及估值时间可追查。

### UI/发布

- 年图 1 天；顶部总额、live、分钟历史口径相同。
- 负值符号正确，隐私隐藏有效；原像素样式/拖动/亮暗/刷新不退化。
- 所有 API 只返回当前认证用户数据，报价证据不能泄露其他用户持仓。
- 后端测试、前端测试/构建、MySQL 5.7 集成、healthcheck 通过；无法验证的项目明确列出，不写“全部完成”。

## 11. 容量与范围限制

按一个用户全年持续采样：1m 525600、1h 8760、4h 2190、1d 365，合计 536915 行/年；净权益拆分字段使每条记录比旧表更大。1000 人约 5.37 亿行/年，另外还需索引/日志/备份空间。

按当前真实规模检查磁盘和批量耗时，不自行更换数据库、分库分表或增购服务；未授权不要删历史。不得为采集引入逐用户外网请求或逐订单 N+1 SQL。先复用缓存、按用户/品种批量估值。

本包不授权交易、发放资金、改变贷款合同/费率、改预设盈亏或价格控制、全站重构、清库、升级依赖、部署其他项目、创建新工作树或新会话。必要业务估值规则无法确定时只问该规则，不凭空替用户定价。
