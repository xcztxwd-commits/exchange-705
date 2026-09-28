# 充值详情：详细设计与实施方案

版本：2026-09-28，供新会话实施。

项目根目录：`C:\workspace\fx\705`。

状态：本文件是设计与实施交接，业务代码、数据库迁移和下文标注“待实现”的测试脚本尚未创建。不得将本方案描述为已实现或已测试。

## 1. 已确认目标与边界

在当前管理后台新增“充值详情”，统一记录用户充值与后台手动充值。支持新增手动充值、查询、筛选、订单详情、凭证预览、审核入口和导出。新增手动充值确认成功后，真实增加目标客户账户余额并留下不可重复的入账记录。

已经确认的产品规则：

1. 管理员手动充值与用户提交充值共用 `deposit_record`，不创建第二套充值订单表。
2. 手动充值由具备相应权限的后台操作人确认后直接入账，不做第二次审核。
3. 用户充值仍须审核；通过才入账，拒绝不入账。
4. 必须分别记录“充值来源”和“充值类型”。后台补录银行卡到账也是管理员手动来源，不是用户提交。
5. 保留“充值审核”待办菜单；新增“充值详情”全量台账菜单。两页面读取同一份订单。
6. 已入账订单不提供删除、修改金额、修改客户、修改币种、修改账户功能。
7. 第一版不做冲正、退款、批量充值、批量审核、任意资产兑换、链上自动监听、新支付渠道、通用账本平台。
8. 截图只用于布局参考。截图里的编辑、删除按钮不等于已授权实现的资金操作。
9. 手动充值备注必填；拒绝原因必填。第一版订单提交后不提供备注编辑，以免额外引入备注版本管理。
10. 本轮只产出方案。新会话才能按方案实施；不得在本轮修改余额、审核订单或部署。

## 2. 代码现状与必须解决的问题

以下为本次读取代码所得事实，执行时必须重新核对最新工作区。

| 位置（相对项目根目录） | 现状 | 实施要求 |
| --- | --- | --- |
| `exchange-admin/src/views/DepositReview.vue` | 已有查询、通过、拒绝、详情 | 保留入口，复用订单数据与审核服务 |
| `exchange-admin/src/views/Users.vue` | 充值与设置余额共用弹窗及旧接口 | 充值改走统一服务；设置余额不算充值 |
| `exchange-backend/src/main/java/com/gtcfesk/exchange/entity/DepositRecord.java` | 有 amount、currency、originalAmount、exchangeRate、凭证、状态、版本号 | 扩展订单号、来源、费率、审计人、入账账户等 |
| `exchange-backend/src/main/java/com/gtcfesk/exchange/admin/AdminUserService.java` | `updateBalance` 的 amount 分支直接加余额，不创建充值订单 | 必须接入统一充值入账逻辑，不能留下漏记入口 |
| `exchange-backend/src/main/java/com/gtcfesk/exchange/admin/DepositReviewService.java` | 审核事务内更新订单、增加 FUND 余额；订单和账户有版本号 | 保留并发保护，统一写入入账记录及审核元数据 |
| `exchange-backend/src/main/java/com/gtcfesk/exchange/admin/DepositReviewController.java` | 手工组装返回字段，遗漏实体已有的原币金额、货币、汇率 | 修复 DTO 映射，不能只改实体而忘了接口 |
| `exchange-backend/src/main/java/com/gtcfesk/exchange/user/DepositController.java` | 用户提交保存汇率快照；用户记录接口当前返回实体 | 保持旧请求兼容，改为用户端安全 DTO，防止内部字段泄露 |
| `exchange-backend/src/main/java/com/gtcfesk/exchange/config/BackendAccess.java` | 控制器到菜单的显式映射、代理动作权限、数据范围 | 新控制器和动作必须注册；不能只隐藏前端按钮 |
| `exchange-backend/src/main/java/com/gtcfesk/exchange/config/OperationLogInterceptor.java` | 请求完成后记录后台操作，非资金事务内审计 | 只能作辅助日志，不能代替原子入账凭据 |
| `exchange-backend/src/main/java/com/gtcfesk/exchange/admin/DashboardService.java`、`AgentPerformanceController.java` | 主要汇总 COMPLETED 订单 amount | 新增手动订单后必须区分来源，避免赠送计入真实入金业绩 |

技术基础：Vue 3、TypeScript、Element Plus；Spring Boot 2.7.18、Java 8、Spring Data JPA；当前 Compose 的 MySQL 镜像是 5.7。已有 BigDecimal、账户唯一约束和乐观锁，不额外引入财务框架。

工作区已有大量未提交改动。实施者必须保留，不得 `git reset --hard`、`git clean`、整目录覆盖或顺带重构其他功能。尤其注意已有行情、汇率、资产历史和手动订单改动。

## 3. 业务模型

### 3.1 来源、类型、业务用途分离

**来源 source：**

- `USER_SUBMITTED`：用户端发起，待审核。
- `ADMIN_MANUAL`：后台发起，授权确认后直接入账；操作人可能是管理员或获得授权的代理，具体身份另外记录。
- `LEGACY_UNKNOWN`：历史数据缺乏证据，无法确定来源。

**类型 type：**

- 保留现有 `bank`、`digital`。
- 增加 `manual`：纯后台加款，无银行或链上收款凭据。
- 用户端不能提交 `manual`，也不能指定 `source`、审核人、创建人等后台字段。

**手动用途 manualPurpose：**

- `RECEIPT`：线下收款补录。
- `BONUS`：赠送。
- `ADJUSTMENT`：经说明的补款；不是“设置最终余额”。
- 仅手动来源可填写，默认 `ADJUSTMENT`；`RECEIPT` 要求 bank/digital 及凭证。
- 用途用来解释资金来源与分组统计，不允许任意输入一个“计入业绩”的布尔值。

第一版用户充值业绩只统计 `USER_SUBMITTED + COMPLETED`；手动实收补录单独展示，不自动并入业绩。

### 3.2 状态与动作

| 来源/当前状态 | 允许动作 | 成功结果 | 余额变化 |
| --- | --- | --- | --- |
| 用户提交 | 创建申请 | PENDING | 无 |
| USER_SUBMITTED / PENDING | 审核通过 | COMPLETED | 增加净到账 USD |
| USER_SUBMITTED / PENDING | 拒绝并填写原因 | REJECTED | 无 |
| 后台手动 | 确认充值 | COMPLETED | 增加净到账 USD |
| 任意 / COMPLETED | 查看、导出 | 不变 | 无 |
| 任意 / REJECTED | 查看、导出 | 不变 | 无 |

历史待审核记录保留已有审核能力，但来源保持未知，不能因处理了一次便伪造成“用户提交”。

管理员手动单的 `reviewedBy`、`reviewedAt` 为空，界面显示“无需审核”；创建人与入账操作人正常记录。不得把创建人自动填成审核人。

用户审核通过后的重试返回原结果或明确“已处理”，绝不再次入账；已通过与拒绝相互冲突时返回冲突错误。

## 4. 页面与交互

### 4.1 菜单和路由

- 新菜单名称：充值详情。
- 新路由：`/deposit-orders`。
- 新页面：`exchange-admin/src/views/DepositOrders.vue`。
- 主菜单代码：`deposit_orders`。
- 原 `/deposit-review` 继续存在，通知铃铛仍跳转审核待办。
- 重用当前后台布局、请求封装、日期格式、权限方式和凭证组件，风格与现有后台一致。

### 4.2 筛选与统计

筛选：UID、订单号、用户备注、创建时间、审核时间、状态、来源、类型、原币货币、钱包地址、网络、入账账户、管理员可用的代理筛选。

默认按 `created_at DESC, id DESC` 排序，分页默认 20 条，允许 10/20/50/100；后端上限 100。时间范围使用项目统一时区，查询边界统一为左闭右开。禁止 `findAll()` 后内存筛选分页。

顶部显示当前筛选范围内：订单数量、待审核数量、已入账 USD、用户审核入账 USD、手动入账 USD、历史未知入账 USD。统计不是当前页求和，必须使用与列表相同的数据权限。

### 4.3 字段定义

| 页面字段 | 语义与显示 |
| --- | --- |
| 用户备注 | 用户资料当前备注，不与订单备注混用 |
| 用户 UID | 客户标识，支持复制；关联查询经过授权 |
| 订单号 | 后端生成唯一值，支持复制；记录 ID 不对外冒充订单号 |
| 数量 | `originalAmount`，后接输入货币 |
| 实到 | COMPLETED 时显示 amount USD；PENDING 显示“预计到账”；REJECTED 显示“未入账” |
| 状态 | 待审核、已入账、已拒绝 |
| 费率 | feeRate 的百分比展示，明确为手续费率 |
| 手续费 | feeAmount + 原币货币 |
| 资源 | 改名“充值凭证”；图片预览，缺失显示 — |
| 订单备注 | 手动原因或用户提交说明 |
| 货币 | 输入金额的计价货币，不从网络名猜测 |
| 充值来源 | 用户提交、管理员手动、历史来源未记录，彩色标签 |
| 充值类型 | 银行卡、数字货币、后台加款 |
| 钱包地址 | 数字货币收款地址；银行卡使用独立收款信息展示，不伪装成链上地址 |
| 地址网络 | 仅数字货币适用；其余显示 — |
| 审核时间 | 实际审核时间；不是 updatedAt |
| 审核用户 ID | 审核人身份、ID、名称；避免用户 ID 与后台账号 ID 冲突 |
| 创建人 | 创建者身份、ID、名称快照 |
| 审核备注 | 通过说明或拒绝原因 |
| 创建时间 | 真正创建时间 |
| 入账时间 | 实际成功入账时间 |
| 入账账户 | FUND / CONTRACT / OPTION 的中文名称 |
| 操作 | 详情；待审核且有权限才显示通过、拒绝 |

三处“备注”明确为“用户备注、订单备注、审核备注”；“创造人”更正为“创建人”。

固定 UID、订单号及右侧操作列；支持横向滚动和列显隐。地址和备注省略显示，可查看完整值。金额字符串不能使用 JS Number 作为计算依据。

### 4.4 新增手动充值

建议复用组件：`exchange-admin/src/components/ManualDepositDialog.vue`，同时供用户管理和充值详情使用。

字段：UID、账户、货币、数量、类型、用途、订单备注、可选凭证；bank/digital 按类型展示收款信息。第一版费率固定为 0，前端不开放调整；后端拒绝未经支持的非零费率，不接受前端提供的手续费或最终金额。

UID 查询显示用户名称、备注及账户余额；未查到用户、无权限、无效数量、货币不支持、缺少备注时不允许提交。账户默认 FUND，保留既有 CONTRACT/OPTION 充值能力。

金额确认采用以下明确规则，不引入新的报价缓存系统：

1. 界面读取现有换汇能力展示原币金额与预计 USD，标注“以服务端提交时汇率为准”。
2. 二次确认展示 UID、账户、货币、数量、费率、预计到账与备注。
3. 服务端提交时获取有效汇率并保存订单快照；成功响应展示最终 USD 与订单号。
4. 汇率不可用则整笔失败，不默认按 1:1 外币入账。若业务以后要求严格锁住预览价格，再单独设计服务端短期报价凭证；本版不假称预览已锁价。

同一弹窗产生一个 UUID 幂等键。提交期间禁用按钮；网络超时保留键，提示“结果待确认”，允许查询或重试原请求；不得自动换键重新充值。确认请求已失败且没有入账、或用户明确新建另一笔时才换键。

### 4.5 详情和导出

详情分为订单信息、金额明细、凭证、审核信息、入账信息。时间线仅依据已保存事实生成，历史缺失事件不虚构。

导出采用 CSV，不新增 Excel 依赖。复用筛选和权限，不导出隐藏越权数据；支持 UTF-8 BOM、CSV 转义、防公式注入（含备注、姓名等用户可控文本）。单次最多 10,000 条，超过返回“请缩小时间范围”，禁止静默截断。金额统一字符串，标注单位与统计口径。

## 5. 金额规则

### 5.1 单位

当前资产账户以 USD 记账。延续现有 `DepositRecord.amount` 的 USD 语义，不把它改成原币数量，以免破坏用户资金明细、首页、统计与代理业绩。

```text
feeAmount       = round(originalAmount × feeRate, 16, HALF_UP)
netOriginal     = originalAmount − feeAmount
amount（USD）   = round(netOriginal × exchangeRate, 16, HALF_UP)
```

存储费率为小数，例如 1% 存 `0.01`。第一版所有新单费率为 0；保留字段与测试非零公式，但不开放收费入口。

继续采用 DECIMAL(32,16)、BigDecimal 和现有 TradeValidation。校验原币数量、汇率、净额、折合 USD 及入账后余额均在合法范围；禁止 NaN、Infinity、超过整数/小数位数、零和负数、转换后四舍五入为零。金额 JSON 优先用十进制字符串；旧接口兼容现有合法数字输入，不经过 double 换算。

### 5.2 币种兼容

本次读取的 `FiatCurrencyService` 支持 USD/EUR/JPY/GBP/AUD/CAD/SGD/CNY，执行时以最新已验证服务为准，UI 不单独放开服务端不支持的货币。

`digital` 目前是收款渠道类型，不表示系统已支持把 BTC、ETH 或 USDT 数量自动换 USD。第一版不新增任意加密资产计价：数字货币渠道仍沿用既有已验证输入货币规则，明确标注“计价货币”，不能把 USDT-TRC20 网络字段当成汇率，也不能把 BTC 数量当 USD。保持现有客户端请求兼容并补测试。

用户提交时锁定汇率与预计净额；审核使用已保存金额，不重新读取市场汇率。手动订单在成功创建时锁定汇率。历史 amount 不重算。

## 6. 数据设计

### 6.1 扩展 deposit_record

保留当前字段及 `row_version`，新增字段建议如下。最终 SQL 以实际 schema 核对后生成，不直接执行概念表。

| 字段 | 建议类型 | 说明 |
| --- | --- | --- |
| order_no | VARCHAR(64)，唯一 | 新单必须有；历史可回填可识别的 LEGACY-DEP-{id}，仅是新展示编号 |
| source | VARCHAR(32) | USER_SUBMITTED / ADMIN_MANUAL / LEGACY_UNKNOWN |
| account_type | VARCHAR(16) | FUND / CONTRACT / OPTION；已有充值订单可根据已验证旧链路映射 FUND |
| manual_purpose | VARCHAR(24)，可空 | RECEIPT / BONUS / ADJUSTMENT |
| fee_rate | DECIMAL(18,8)，可空 | 新单 0；历史未知不能凭空声称收过或没收手续费 |
| fee_amount | DECIMAL(32,16)，可空 | 原币手续费 |
| created_by_type / created_by_id / created_by_name | VARCHAR(24) / BIGINT / VARCHAR(128)，可空 | USER / ADMIN / AGENT；账号名称快照，不保存敏感信息 |
| reviewed_by_type / reviewed_by_id / reviewed_by_name | 同上，可空 | 真实审核人 |
| reviewed_at | DATETIME(6)，可空 | 真实审核时间 |
| review_remark | VARCHAR(500)，可空 | 审核说明，不覆盖原 remark |
| credited_at | DATETIME(6)，可空 | 成功入账时间 |
| idempotency_key | VARCHAR(64)，可空 | 新后台手动单必须有 |
| request_hash | CHAR(64)，可空 | 规范化业务参数的 SHA-256 |

唯一约束：`order_no`；`(created_by_type, created_by_id, idempotency_key)`，手动单的三个字段均不得为空。不要仅靠 Java 注解保证数据库存在约束。

幂等摘要包含客户、账户、币种、原币金额、类型、用途、地址、网络、凭证、备注等实际输入，不包含实时汇率、后端时间、返回状态。金额规范化后再摘要，`100` 与 `100.00` 按同一语义处理。服务端生成的来源、身份由可信登录上下文绑定。

现有 network/address 非空约束应核对：本版无地址的 manual 单使用明确约定的空字符串存储，network 使用 MANUAL 标识，界面仍显示不适用；不填充假的钱包地址，也不强制用户提交无意义字段。银行沿用现有请求兼容语义，接口层分开展示。

建议索引：`(created_at,id)`、`(user_id,created_at,id)`、`(status,created_at,id)`、`(source,status,credited_at)`、`(reviewed_at,id)`。避免为每个筛选字段盲目建索引；用隔离样本的执行计划确认。

### 6.2 新增 deposit_credit_record

目的：一笔已入账充值对应一条不可变入账凭据，不引入通用多资产账本。

字段：id、deposit_record_id、user_id、account_type、amount_usd、balance_before、balance_after、operator_type、operator_id、operator_name、credited_at。

约束：`deposit_record_id` 唯一；金额及余额使用 DECIMAL(32,16)；订单/客户/账户关系必须与订单一致。外键如采用必须 RESTRICT，不能级联删除财务记录。无外键时同样在删除路径禁止清掉关联财务数据。

只有成功入账才写入此表；PENDING/REJECTED 没有记录。金额余额快照来自当前事务里真实读取和更新的账户，不能采用客户端提供值。

历史 COMPLETED 单无入账凭据时显示“历史入账明细未记录”，不得因为没有凭据就重新入账，也不得编造前后余额。

### 6.3 审计

创建、审核、拒绝、入账的事实字段和入账凭据在业务事务内保存；现有 OperationLog 是补充操作日志。第一版无事后编辑入口，不需要额外的订单事件平台。

后台备注、审核身份、内部用途和入账余额快照不得通过用户端实体序列化暴露。后台接口使用后台 DTO，用户接口使用兼容旧公开字段的 DTO。用户充值历史保留可理解的“后台入账”来源标签，但隐藏内部原因与操作人。

核查用户删除/异常删除代码中对 deposit_record 的清理：不得删掉新财务凭据或级联清账；最小策略是对有充值财务记录的客户拒绝物理删除，保留原有禁用/冻结能力。不得顺手改写无关删除逻辑。

## 7. 服务与事务实现

建议只新增一个 `DepositOrderService`，提供：分页查询、详情、创建手动订单、用户订单创建辅助、审核入账、拒绝及统计导出。原 DepositReviewService 可作薄适配以保留旧接口；不要同时保留两套余额更新算法。

### 7.1 手动充值事务

1. 在进入写事务前校验登录身份、动作权限、目标客户范围、请求结构；所有重试也必须重新检查当前权限。
2. 用可信操作人身份和幂等键查现有订单。相同参数返回原订单，不同参数返回 409；这一步优先于读取新汇率，确保原请求成功后即使行情失效也可确认结果。
3. 校验金额、账户、类型、用途、备注和凭证，获取有效服务端汇率，生成订单号与金额快照。
4. 在同一业务事务创建订单，读取/创建目标账户，使用版本控制安全增加可用余额，保存入账前后余额、入账凭据、COMPLETED 状态、creditedAt。
5. 任意一步失败整笔回滚，不能出现有订单无余额、有余额无凭据。强制 flush，使唯一约束及版本冲突在事务结束前暴露。
6. 同时请求触发幂等唯一冲突时，冲突事务必须先回滚，再在新事务读取原单并比较摘要；不要在已标记 rollback-only 的事务中捕获异常后继续保存。

通过 Spring 代理管理事务或已有 TransactionTemplate；不能依赖同类方法自调用触发 `@Transactional`。摘要/幂等比较不允许只在前端完成。

### 7.2 用户审核事务

同一事务读取订单、校验 PENDING、验证可用的已保存金额与入账账户、增加账户余额、写唯一入账凭据、记录审核人/审核时间/备注、标记 COMPLETED。历史待审缺原币快照的订单沿用其已有 USD amount，不重新换算或伪造原币值。

保留 DepositRecord 与 AssetAccount 的 `@Version`，明确处理并发状态/余额冲突；若加行锁，保持一致的订单后账户锁序。只有新增行锁而其他资金写入不受约束，不足以替代现有版本机制。涉及直接 SQL 的写入必须核查 row_version 一致性。

同时通过、同时通过与拒绝、充值与划转/交易并行都要验证：可以一方失败并安全重试，但不得丢余额、重复入账或状态与余额不一致。新账户并发创建依赖已有 `(user_id,coin)` 唯一约束，冲突后完整回滚并明确返回。

### 7.3 原入口兼容

- 新页面与 Users.vue 充值弹窗使用同一组件、同一新接口。
- 旧 `/api/admin/users/updateBalance` 含 amount 时继续兼容，但适配到同一服务，要求幂等键及原因；缺少新必需安全字段返回明确 400，不能悄悄生成随机键重复入账。同步更新所有本仓库调用方和回归测试。这是有意收紧的写入契约，发布说明必须写明。
- 旧 updateBalance 不含 amount 的“设置余额”不生成充值订单，不开放给新增的充值权限；保留既有严格权限和操作日志。混合 amount 与最终余额字段的请求必须拒绝。
- 用户 `/api/deposit/submit` 保持旧参数兼容；可接受可选客户端 requestId 并去重，有键时摘要冲突拒绝。旧客户端无键仍可提交，审核层保证每一订单只入账一次，不能宣称旧无键接口具备跨订单防重能力。
- 用户业务不应因 Java 包组织依赖后台控制器；共用服务放在现有合理的业务包中，以最小改动实现。

## 8. API 契约（新接口，待实现）

统一前缀 `/api/admin/deposit/orders`。前端 request 通常已有 `/api` baseURL，调用时沿用现有约定，避免 `/api/api`。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/list` | page/size + 筛选条件，返回分页 |
| GET | `/summary` | 同筛选范围与权限的统计 |
| GET | `/export` | CSV 导出，独立导出授权 |
| GET | `/recipient/{userId}` | 返回经范围校验后的客户简要信息、可充值账户余额；不借用要求用户管理权限的宽接口 |
| GET | `/{id}` | 订单详情和入账凭据 |
| POST | `/manual` | 新增手动充值，幂等 |
| POST | `/{id}/approve` | 审核通过，复用原业务逻辑 |
| POST | `/{id}/reject` | 拒绝，原因必填 |

示例：

```json
{
  "userId": 123,
  "account": "FUND",
  "currency": "USD",
  "amount": "100.00",
  "type": "manual",
  "manualPurpose": "ADJUSTMENT",
  "remark": "经核对的账户补款",
  "proofImage": null,
  "address": "",
  "network": "MANUAL",
  "idempotencyKey": "由客户端生成的UUID"
}
```

示例 UID 仅用于契约说明，不得据此对真实客户测试。后端拒绝或忽略不应由客户端控制的 source/status/createdBy/reviewedBy/feeAmount/creditedAt 字段，优先用白名单 DTO 避免批量赋值。

响应沿用本仓库统一封装风格：分页包含 list、total、page、size；手动充值包含订单 id、orderNo、status、实际 USD、原币与汇率、是否命中幂等、入账时间。金额用字符串。失败使用清晰业务错误与正确 HTTP 状态，禁止 HTTP 200 包装权限失败。

主要失败：400 参数/范围错误；401 未登录；403 缺权限；404 记录不存在或不在可见范围；409 状态/幂等/并发冲突；503 汇率暂不可用。若现有统一异常约定不同，可统一映射但不得把失败伪装成功。

查询/导出拒绝无效枚举与超长输入，日期有上限；订单号及 UID 精确查询，备注包含查询，地址前缀或精确查询。LIKE 特殊字符转义，排序列白名单，不拼接原始 SQL。

## 9. 权限实施

动作命名：`view_deposit_orders`、`manual_deposit`、`approve_deposit`、`reject_deposit`、`export_deposit_orders`。

1. 超级管理员按现有机制允许；普通后台角色默认不新增资金权限；代理默认不新增手动充值权限。
2. 显式分配新菜单及动作后才可调用对应接口；代理继续仅限自己的下级客户。
3. 现有管理员角色主要按菜单授权，代理按 UserAction 授权。必须利用现有 role-menu/button 模型或最小增加本模块显式动作校验，不能假称 checkMenu(code, action) 已对所有管理员执行按钮检查。
4. 审核动作沿用 `deposit_review` 下原有通过/拒绝权限，新详情页还需具备详情菜单读取权限，不能用新路径绕过旧审核授权。
5. 新旧手动充值写入口都要执行新动作校验；迁移不自动把所有“能查看充值”或“能管理用户”的账号授予手动加款。
6. recipient、list、summary、detail、export 和写接口都执行相同范围校验。不能只在 JSON userId 上检查，忽略路径中的订单 ID 或导出筛选。
7. 基于 SecurityContext 的可信身份提取操作人类型和 ID，不另行解析不可信身份字段；保持后台账号与普通用户/代理 ID 命名空间区分。

菜单、按钮、角色映射与初始化器必须同时核对，避免 SQL 插入后被启动初始化器重置。迁移只能补缺菜单，不自动扩权。

## 10. 统计、历史与迁移

### 10.1 统计口径

- 全部已入账 = 用户审核入账 + 管理员手动入账 + 历史未知已入账，均以 USD amount 汇总。
- 用户充值业绩 = 新明确 USER_SUBMITTED 的已入账；手动 RECEIPT/BONUS/ADJUSTMENT 各自分列，不混入用户实际充值。
- 历史旧统计不可突然归零：旧历史集合保持单独“历史充值（来源未记录）”口径；原累计报表保留可核对的历史部分并标注，新手动订单不增加旧用户业绩。
- 新台账按入账时间统计入账金额；订单列表创建时间筛选只限制订单集合。旧报表按创建时间的语义本版不悄悄变更，明确说明与新入账统计的差异。
- 不同原币金额不直接相加；汇总仅用 USD，原币按币种分组展示。

### 10.2 迁移文件（待实现）

建议路径：`exchange-backend/src/main/resources/db/migration/add_deposit_order_details.sql`。

必须兼容 MySQL 5.7：不要使用 MySQL 8 专属语法，不假设支持 ADD COLUMN IF NOT EXISTS；使用 information_schema 条件检查与项目兼容的执行方式。考虑 DDL 隐式提交，不能把“事务包住整个迁移”当成可靠回滚。

分阶段：

1. 预检字段、索引、引擎、已有订单数、已完成 USD 总额，检测孤儿记录与非法金额。
2. 备份。新增可空字段、入账凭据表、索引，不改余额。
3. 历史 orderNo 可回填 `LEGACY-DEP-{id}`，说明此编号为升级时生成；来源默认 LEGACY_UNKNOWN。只有可靠证据才能改为已知来源。
4. 原有币种快照保留；缺失时页面可明确标为“历史按 USD 记账”，不能伪造原始数量、手续费或兑换汇率。
5. 创建/审核/入账人员与时间无法确认时保持空；updatedAt 不是 reviewedAt。
6. 不从当前余额或含混日志反推旧后台充值，不能造历史入账凭据或重放充值。
7. 建立唯一约束前确认历史数据不冲突；添加菜单/动作元数据，不自动给普通账号授权。
8. 同一迁移连续运行两次应无重复字段、菜单、索引与资金变化，记录两次结果。

虽然当前 Hibernate ddl-auto=update，本功能仍必须有正式迁移脚本；不能只依赖 ORM 自动建列实现历史回填和约束。

### 10.3 回退

发布前保留当前后端/后台镜像标识、受影响源文件备份和数据库备份。上线后发生新充值时，禁止用全库旧备份覆盖，因为会抹除真实资金变动。

优先关闭充值写入入口，保留新表字段与数据，回退 UI 或部署兼容代码；旧 updateBalance 存在不记单路径，直接降级后必须继续关闭该写入路径。回退不删入账凭据、不删订单、不自动减余额。涉及已入账资金的纠错另走人工核对与独立冲正方案。

## 11. 实施步骤与文件范围

### 阶段 A：基线与保护

- 阅读仓库适用 AGENTS.md 和已安装 ponytail/caveman 技能。
- 保存 git status 与当前 HEAD，识别并保留原有未提交改动。
- 核对本文关键调用链、管理员/代理权限、前后端用户充值 DTO、统计与删除路径。
- 修改每个既有文件前复制到带时间戳的备份目录，保留目录结构；写入采用临时文件加原子替换或等效安全方式。不得全盘回滚工作区。

### 阶段 B：模型、迁移、核心服务

- 扩展 DepositRecord、DepositRecordRepository（可用 JpaSpecificationExecutor 做服务端分页）。
- 新建 DepositCreditRecord 与对应 Repository，唯一订单入账约束。
- 实现 DepositOrderService、明确的请求/响应 DTO、幂等与事务异常处理。
- 接入原用户提交、原审核和旧后台充值；保持余额设置独立。
- 完成迁移和核心单元/事务集成测试后再做 UI。

### 阶段 C：API 与权限

- 新增 DepositOrderController；扩展 BackendAccess 的控制器映射、动作与数据范围检查。
- 更新菜单初始化、按钮授权与必要后台日志分类，不改其他模块权限。
- 用户记录返回安全 DTO，后台记录接口补齐快照字段。

### 阶段 D：管理端

- 新增 DepositOrders.vue、ManualDepositDialog.vue。
- 修改 router/index.ts、views/Layout.vue 接入菜单。
- Users.vue 的充值复用弹窗，保留设置余额模式。
- DepositReview.vue 用同源记录，正确显示币种、来源、审核及入账信息。
- 复用 CurrencyPicker 和上传能力，不另造货币列表或无权限文件访问入口。

### 阶段 E：报表与验收

- 修改 DashboardService、AgentPerformanceController、用户后台资金详情展示的统计口径。
- 检查用户删除对财务记录的影响。
- 执行迁移重跑测试、并发事务测试、权限测试、四端相关构建与隔离 UI 验收。
- 输出测试报告、修改清单、兼容变更、发布与回退步骤；未验证项必须列明。

## 12. 测试矩阵与完成条件

不得用真实用户资金测试；任何 create-drop、TRUNCATE、DELETE 或余额变动测试只在随机命名隔离数据库与测试客户执行。

| 编号 | 场景 | 必须断言 |
| --- | --- | --- |
| T01 | USD 手动充值 100，原余额 25 | 一单一凭据，余额 125，原余额未覆盖，冻结余额不变 |
| T02 | EUR 100、快照汇率 1.1 | 净额 110 USD，原币和汇率保存，后续汇率变化不改订单 |
| T03 | FUND/CONTRACT/OPTION 分别充值 | 只改指定账户，不影响其他账户 |
| T04 | 用户提交、通过、拒绝 | 提交不加款，通过一次加款，拒绝始终无入账凭据 |
| T05 | 同幂等键顺序重试、并发重试、响应丢失重试 | 都最多一单一凭据；已成功单不依赖当前汇率确认结果 |
| T06 | 同键不同 UID/账户/金额/备注 | 409，无额外资金变化 |
| T07 | 两人同时通过、通过与拒绝竞争 | 单一终态，无重复加款 |
| T08 | 两笔充值并行、充值与划转/交易并行、新账户并发创建 | 无丢更新；失败可识别且整笔回滚 |
| T09 | 订单写入、账户写入、凭据写入、flush/commit 任一步故障 | 无半笔账；新手动单整笔回滚，审核单仍为原待审核状态 |
| T10 | 0/负数/超限/异常精度/换算后为0/余额溢出 | 拒绝，不留资金副作用 |
| T11 | 汇率缺失、过期、币种不支持 | 外币不按 1:1 兜底；USD 按已验证的原有服务规则 |
| T12 | 无权限、仅查看、代理跨客户、路径越权、导出越权 | 后端拒绝，记录和余额不变 |
| T13 | 旧 updateBalance 充值与设置余额 | 充值必须记单，设置余额不冒充充值；缺幂等键安全失败 |
| T14 | 历史 COMPLETED/PENDING/REJECTED 及缺失元数据 | 不伪造、不重算、不重复入账，历史金额统计一致 |
| T15 | 列表/汇总/导出/详情筛选一致 | 同一权限范围与筛选，total 正确，无全表内存分页 |
| T16 | CSV 注入、长备注、特殊字符与地址 | 正确转义与脱敏，不触发公式 |
| T17 | 用户端查询记录 | 不包含后台内部备注、操作人、余额快照 |
| T18 | 迁移连续两次、含历史数据 | 结构及菜单不重复，余额和旧 amount 不变 |
| T19 | 手动赠送、手动补录、用户充值与历史未知统计 | 各分组清晰，总和可核对，不虚增用户业绩 |
| T20 | 删除带充值记录的客户 | 财务记录不可被级联或异常删除抹掉 |
| T21 | UI 反复点击、关闭重开、超时重试、表单校验 | 提交键生命周期正确，待定结果不显示失败后重新加款 |

至少提供 `DepositOrderServiceTest`、`DepositOrderAccessTest`、`DepositOrderMySqlIT`（名字可等义替换并同步命令）。MySQL 5.7 测试必须调用实际生产服务和真实事务，不得只 mock Repository 证明并发安全。纯 H2 或纯 mock 成功不等于 MySQL 验收完成。

保留并更新 `FiatDepositTest`、`MinimalFixRegressionTest` 相关用例；构造器变化允许适配测试注入，不允许删除安全断言或跳过失败测试。

新增 `scripts/deposit-orders/Test-MySql.ps1`：负责独立临时 MySQL 5.7 容器、仅回环地址随机端口、随机测试凭据、固定测试库前缀 `deposit_order_test_`，执行真实服务集成测试并导出报告。启动前检查目标，失败返回非零；只清理本次创建的容器和测试库，不能复用当前业务 MySQL。该脚本目前尚不存在。

## 13. 新会话可执行命令

以下为 PowerShell 命令；“实现后运行”的命令必须等对应代码/脚本生成后执行。本设计会话没有运行测试或构建，没有操作业务数据库。

### 13.1 进入项目与读取方案（现在即可运行，只读）

```powershell
Set-Location -LiteralPath 'C:\workspace\fx\705'
git status --short
git rev-parse HEAD
Get-Content -LiteralPath 'C:\workspace\fx\705\docs\deposit-orders-design-and-implementation.md' -Encoding UTF8
```

### 13.2 确认工具链（只读）

```powershell
java -version
mvn -version
node --version
npm.cmd --version
docker version
```

后端按已验证 JDK 8 / Maven 环境构建；缺工具时先寻找已配置的本地或 Docker 工具链，不擅自替换项目依赖。仓库已有 docker/backend.Dockerfile 可构建测试镜像，但其中会先执行全部默认 Maven 测试；使用前检查测试数据源隔离，不把构建等同于真实 MySQL 专项验证。

### 13.3 实现后：定向回归和构建

```powershell
Set-Location -LiteralPath 'C:\workspace\fx\705'

# 清除可能指向既有数据库的测试覆盖变量，仅影响当前进程。
Remove-Item Env:QA_MINIMAL_MYSQL -ErrorAction SilentlyContinue
Remove-Item Env:QA_DB_PASSWORD -ErrorAction SilentlyContinue

# 先确认新测试固定使用隔离 H2/测试库，不能继承业务 application.yml 数据源。
mvn -f 'C:\workspace\fx\705\exchange-backend\pom.xml' '-Dtest=FiatDepositTest,MinimalFixRegressionTest,DepositOrderServiceTest,DepositOrderAccessTest' test
if ($LASTEXITCODE -ne 0) { throw '充值专项回归失败' }

Push-Location 'C:\workspace\fx\705\exchange-admin'
try {
    # 缺依赖或锁文件有变化才执行 npm.cmd ci；不要无意义地改锁文件。
    npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw '后台构建失败' }
} finally { Pop-Location }

# 实现者核查全部测试的数据源隔离后才执行全量测试和打包。
mvn -f 'C:\workspace\fx\705\exchange-backend\pom.xml' test
if ($LASTEXITCODE -ne 0) { throw '后端全量测试失败' }
mvn -f 'C:\workspace\fx\705\exchange-backend\pom.xml' package -DskipTests
if ($LASTEXITCODE -ne 0) { throw '后端打包失败' }

git diff --check
if ($LASTEXITCODE -ne 0) { throw '补丁格式检查失败' }
```

如果既有测试失败，保留错误摘要与基线证据；不得修改断言凑通过，也不得将 `-DskipTests` 的打包当作测试成功。

### 13.4 实现后：隔离 MySQL 5.7 专项验证

```powershell
Set-Location -LiteralPath 'C:\workspace\fx\705'
$testScript = 'C:\workspace\fx\705\scripts\deposit-orders\Test-MySql.ps1'
if (-not (Test-Path -LiteralPath $testScript)) {
    throw '专项脚本尚未实现，不得宣称 MySQL 集成验证通过'
}
& $testScript
if ($LASTEXITCODE -ne 0) { throw '充值 MySQL 专项验证失败' }
```

脚本必须在内部设置隔离数据源、执行 DepositOrderMySqlIT（或最终实际类名）并检查 Maven 退出码，导出 `reports/deposit-orders/` 下的结果。缺 Docker 或数据库启动失败就是未验证，不是跳过后通过。

### 13.5 用户端兼容构建（DTO/用户充值展示有改动时必跑）

```powershell
foreach ($app in @('exchange-frontend', 'exchange-pc')) {
    Push-Location (Join-Path 'C:\workspace\fx\705' $app)
    try {
        npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw "$app 构建失败" }
    } finally { Pop-Location }
}
```

### 13.6 发布说明

本文件不提供会直接修改当前业务数据库的通用一键命令。新会话应交付经过隔离验证的数据库备份、迁移、应用发布、预检与回退脚本，并注明目标库、环境、停写要求。没有用户另行要求，不自动部署或重启当前服务。

## 14. 最终交付清单

- 可工作的充值详情页面、共用手动充值弹窗、原入口兼容。
- 统一订单、唯一入账凭据、事务/幂等/并发保护。
- MySQL 5.7 可重复执行迁移、权限菜单及历史兼容。
- 隔离测试脚本、测试结果、必要截图；任何未通过项如实列出。
- `docs/deposit-orders-verification.md`：实际执行命令、退出码、测试数量、MySQL 版本、隔离目标、缺陷与未验证项。
- `docs/deposit-orders-rollout.md`：发布前检查、备份、迁移、授权、回退；不得包含真实密码或令牌。
- 最终摘要说明修改了什么、如何验收、是否已部署。除非真正完成部署，不得写“已上线”。
