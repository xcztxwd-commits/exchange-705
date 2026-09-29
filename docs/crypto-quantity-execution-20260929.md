# 加密数量单位：最小兼容改造、执行与测试交接

编写日期：2026-09-29。项目根目录：`C:/workspace/fx/705`。

本文是下一会话的执行规范，不是实现完成报告。本会话仅交付本文、新会话指令及测试入口；没有迁移数据库、修改交易业务代码、部署或下测试订单。

## 0. 授权与执行边界

用户授权：在其拥有或有权管理的本项目环境中，以环境实际允许的最大权限完成本任务。允许修改相关源码、配置、数据库结构与数据，使用已提供的项目账户登录后台、测试账户及相关服务，操作已授权电脑、终端、Docker 与浏览器，进行备份、迁移、测试、部署及验收。常规、可回滚且范围明确的操作无需反复征求确认。

该授权覆盖本任务必要的所有相关数据库和账户，不等于授权侵入第三方账户或修改无关项目。只使用已提供、现有授权会话或正常登录方式；不绕过 MFA、验证码、安全权限或凭据保护。不得把密码、Token、数据库备份上传到外部平台或输出到报告。权限声明不能替代宿主系统权限和工具审批；若工具能力不足，如实报告，使用正常授权途径。

可以在隔离测试库、测试账户中创建及清理数据。不得在真实客户账户中下单或修改余额来制造验收结果，不实施真实交易所成交、转账、提现等外部资金动作。发现不可恢复的数据损失风险、目标身份无法确认或备份不可用时，停止该写入步骤；继续可以独立完成的编码与测试。

## 1. 已确认目标与本轮最小范围

1. 加密新订单改用原生币数量：BTCUSDT 显示“数量（BTC）”，ETHUSDT 显示“数量（ETH）”，SOLUSDT 显示“数量（SOL）”。0.01 BTC 必须是 0.01 BTC，不再是 10 BTC。
2. 配置存储在品种上，类别只提供新增品种模板，不在前端硬编码 BTC、ETH、SOL 特例。
3. 保留现有数量乘数架构：实际资产数量 = quantity × lotSize。新加密 lotSize=1；不重命名数据库列，不引入新交易引擎。
4. 外汇继续“手”、100000 基础货币/标准手；保留已确认 3.50 USD/手/单边方案。先核实先前外汇修复是否已发布，不能把历史报告当作当前运行事实，也不要为本任务顺便重跑未核实的外汇迁移。
5. 股票本轮保持现有手数、乘数和费率；单位模型支持 SHARE，但不批量切换真实股票配置。
6. 默认杠杆100，服从品种最大杠杆。不得改变现有强平制度、行情来源、身份验证、风控权限、期权或充值提现规则。
7. 不做“按保证金输入”“按USDT金额输入”“张数”“反向币本位”“交易所撮合”“资金费”等额外能力。

### 1.1 佣金：本轮明确选择兼容路径

此前0.05%/单边只是示例，用户没有确认该费率。为满足本次“最小改动、尽量兼容”的要求，第一阶段不新增百分比佣金账务，也不改变同等资产敞口的现有收费水平。

迁移新订单的固定往返费参数：

```text
newLotSize = 1
newFeeMultiplier = oldFeeMultiplier / oldLotSize
原1000币/手、30 USD/手往返，等价于每币0.03 USD往返
新固定往返费 = 输入币数 × newFeeMultiplier
```

这只是计量单位等价转换，不是交易所标准费率，不代表新的商业定价授权。必须读取迁移前真实数据计算，不对所有币无条件硬填0.03；旧乘数缺失、为零，或除法无法在现有精度内精确表达时，拒绝自动迁移并报告。不得使用四舍五入偷偷改变收费。

界面和只读接口应明确“每1 BTC固定往返佣金”，不可继续显示“每手”或伪装成“成交金额百分比”。现有外汇单边佣金显示不变。预留往返费、平仓结清一次的账户流程不改。真实按名义金额计费及maker/taker作为后续独立任务；不要本轮偷偷启用。

## 2. 实施前必须重新盘点

本工作区有大量其他任务未提交改动；禁用 git reset --hard、git clean、全量覆盖、清空业务库及 docker compose down -v。仅备份、修改和提交本任务归属文件，不还原他人变更。共享源码持续变化时使用一致源码副本测试并记录哈希；最终交付必须说明快照与待发布文件是否一致。

先读：
- `C:/workspace/fx/705/docs/all-instruments-audit-20260928.md`
- `C:/workspace/fx/705/docs/fx-standard-fix-20260928.md`
- `C:/workspace/fx/705/docs/crypto-perpetual.md`
- `C:/workspace/fx/705/README-Docker.md`
- 当前目录及子目录有效的 AGENTS.md，ponytail/full、caveman/full 技能。

历史盘点14个品种仅作线索：5外汇、6股票、3加密；当前会话必须查询完整品种清单（含停用），确认有无新增 `_PERP`、其他基础币或不同单位。`Crypto`现货源与`CryptoPerpetual`永续源不得合并，也不能因改数量单位改变源身份和缓存键。

PowerShell只读预检：

```powershell
Set-Location 'C:\workspace\fx\705'
$ErrorActionPreference = 'Stop'
git status --short
git diff --stat
git diff --check
Get-Command git,node,npm.cmd,mvn.cmd,docker -ErrorAction SilentlyContinue
docker compose config --services
docker compose ps
rg --files -g AGENTS.md -g '!node_modules' -g '!target'
rg -n 'contractLots|contractMinLot|contractLotStep|lotSize|feeMultiplier|quantityFromAllocation' exchange-backend/src exchange-admin/src exchange-pc/src exchange-frontend/src
```

不要输出完整 `.env`、`后台密码.txt` 或 `docker compose config` 解析后的敏感值。记录实际Compose项目、数据库实例/库名、服务镜像摘要与前端端口。本地默认端口17050/17051/17052不是其他环境的保证。

## 3. 最小数据模型

### 3.1 品种字段

保留 baseCurrency、quoteCurrency、lotSize、feeMultiplier、maxLeverage、价格精度与来源身份。新增（若最新代码已有等价字段则复用）：

| 字段 | 规则 |
|---|---|
| quantityUnitType | LOT、BASE_ASSET、SHARE；基础资产标签取baseCurrency，SHARE显示股 |
| minOrderQuantity | 以输入单位表示，正数 |
| quantityStep | 正数；数量必须为其整数倍 |
| minOrderNotional | 账户USD门槛；0明确表示关闭，不能把NULL默认为无限制的新规格 |
| specVersion | 独立交易规格版本，不复用行情不断更新的rowVersion |

BASE_ASSET、SHARE 强制 lotSize=1；LOT允许乘数，外汇标准手沿用已有验证。显示小数位由step推导，不增加第二个冲突配置。minOrderQuantity须为step整数倍；新增品种配置完整后才能启用。

保留旧contractMinLot/contractLotStep接口兼容字段，但新客户端使用新语义字段；它们对非LOT不应再误称“手”。commissionMode可根据现有固定计费给出 PER_INPUT_UNIT_ROUND_TRIP 或等价明确值，不创建无需求的佣金引擎。

### 3.2 新加密建议配置（本项目政策，并非外部平台实时限制）

| 品种 | 单位/乘数 | 最小数量 | 步长 | USD最低名义金额 |
|---|---|---:|---:|---:|
| BTCUSDT | BTC / 1 | 0.001 | 0.001 | 10 |
| ETHUSDT | ETH / 1 | 0.001 | 0.001 | 10 |
| SOLUSDT | SOL / 1 | 0.01 | 0.01 | 10 |

同基础币新增永续源可采用相同本地数量模板，但须独立审核品种并迁移，不自动改源分类。未知币种不要套BTC步长；缺规格保持不可新开仓。

### 3.3 订单与请求

已有订单lotSize、fxBaseCurrency、quoteCurrency、marginConversionRate、settlementConversionRate等快照保留。新增最少的订单单位类型、显示基础资产、specVersion以及必要的数量规则快照，防止旧挂单在触发时读到新品种规则；往返费继续复用订单fee快照，不增加重复计费列。

NULL单位快照表示旧协议，不可解释为“当前品种单位”；依已有快照计算，不改quantity、lotSize、margin、fee、profit。旧lotSize=NULL的历史特殊计算路径保留。旧挂单若无法恢复原数量约束，按已有成交规则执行，不用新品种门槛阻断；新挂单使用下单规格快照。

新开仓请求新增specVersion及单位类型，服务端比对。已切换单位的品种必须拒绝旧客户端缺版本请求；版本不一致在锁定资金前拒绝，提示刷新确认。LOT未迁移品种可保留旧协议兼容，但不能把缺版本的旧“手”请求当“币”。限制管理员绕过此保护的手工接口。交易规格更新与下单校验须有锁/版本冲突测试，不能只在客户端比对。

## 4. 代码修改顺序和关键路径

路径以下均相对本项目根目录；定位时使用完整路径。

1. `exchange-backend/src/main/java/com/gtcfesk/exchange/entity/TradingSymbol.java`、`ContractOrder.java`：新增兼容字段；`admin/AdminSymbolService.java`提供模板、验证与规格版本递增。只修改交易规格时递增版本，行情更新不递增。
2. `common/TradeValidation.java`：不删除仍被外汇调用的contractLots；新增/复用按品种配置校验的方法，检查正数、数值上限、step余数、最小数量。BigDecimal精确校验。
3. `trade/ContractOrderService.java`、`trade/dto/CreateContractOrderRequest.java`：读取服务端新鲜价格，检查版本、最小USD名义金额和余额；保留quantity×lotSize既有保证金/PnL；只在新规格将lotSize置1。最新代码可能已抽出ContractValuation，先搜索并复用，不再创建第二套估值函数。
4. 检查所有平仓/自动止盈止损/强平/权益/挂单成交/手工模拟的调用者；`ManualOrderCalculation`、`ManualOrderGenerator`、`ManualOrderService`、`EquityValuationService`等不得遗留统一0.01手约束。只减仓/全平旧余额不受新开仓最低门槛限制。
5. PC与移动端 `src/utils/contract.ts`、`useOrderSizing.ts`、交易页、订单列表、分享图及多语言：复用现有计算，quantityFromAllocation接受step与最小规则，预算反算向下取整。没有满足最低数量/名义金额的预算时返回0并提示，不自动放大订单。
6. `exchange-admin/src/views/Symbols.vue`新增数量规格分组；币/股模式乘数只读1；显示变更前后实际数量与费用对照。`components/ManualContractOrder.vue`及估算工具同步读取规格。
7. 小额佣金不能显示为“0”让人误认免费。保存仍用数据库16位小数；显示可用有效小数或“<0.01 USD”，不把展示四舍五入值写回账务。

## 5. 固定测试样例（USD与USDT沿用当前1:1记账假设）

基准：旧加密lotSize=1000、feeMultiplier=30，新lotSize=1、feeMultiplier=0.03；价格为测试夹具，不修改真实行情。

| 用例 | 数量 | 价格 | 杠杆 | 名义金额USD | 保证金USD | 往返费USD | 总占用USD |
|---|---:|---:|---:|---:|---:|---:|---:|
| BTC | 0.01 | 80000 | 100 | 800 | 8 | 0.0003 | 8.0003 |
| BTC | 0.01 | 80000 | 50 | 800 | 16 | 0.0003 | 16.0003 |
| BTC | 0.001 | 80000 | 100 | 80 | 0.8 | 0.00003 | 0.80003 |
| ETH | 0.004 | 2500 | 100 | 10 | 0.1 | 0.00012 | 0.10012 |
| SOL | 0.10 | 100 | 20 | 10 | 0.5 | 0.003 | 0.503 |

必须说明固定费用很低是保留旧敞口费率的结果，不是照搬交易所标准。若运行库费率不同，等价迁移预期跟随实际值；上述夹具仍保持固定以便检测回归。

### 必测清单

- 3币 × 至少5合法数量 × 杠杆1/5/10/20/50/100 × 多空 × 市价/限价，包含省略杠杆默认100的额外用例。数值断言独立计算，不能让预期调用被测函数。
- BTC做多0.01，80000到80100：毛盈亏1 USD、净盈亏0.9997 USD；做空同价差毛亏1、净亏1.0003。保证金归还不计为盈利，佣金不重复扣。
- BTC旧0.01手=10 BTC，旧fee=0.3；同等敞口新10 BTC、新fee=0.3。旧与新不同单位并存，修改品种后旧仓保证金/PnL/费用不变。
- BTC 0.0009、0.0015拒绝；ETH0.001在2500时不满足10USD拒绝，0.004通过；SOL0.01在100时拒绝，0.10通过。
- step0.005可接受0.015但拒绝0.016；拒绝零、负数、NaN/Infinity、不合法字符串、超精度/超范围数量。最大金额防溢出。
- 预算恰好8.0003时允许上述BTC0.01；略低于该值不超额分配。滑块0/1/25/50/100%、失效行情、失效换汇、价格跳变、用户修改数量后提交复核。
- 开仓available减少margin+fee、frozen等额增加；未成交撤单完整返还；平仓释放保证金并结算PnL与fee；重复/并发平仓不重复扣减。使用独立账户核对总账恒等式。
- 新请求缺规格、旧版本、伪造单位、管理员错误乘数均在资金变化前拒绝；配置更新与下单并发不会混用两个规格。
- 挂单使用自身快照；价格变化导致资金不足时不负余额、不部分写入；按现有业务约定保持待成交并记录原因。新开仓名义金额门槛与只减仓不混淆。
- 外汇5品种、股票6品种继续走原正确规则。USDJPY标准手100x=1000USD、往返7USD；AAPL旧1手1000股、价格200、100x=2000USD、往返30USD。若本环境外汇迁移尚未落地，将旧运行与目标夹具分开报告。
- 后台保存后刷新仍正确；PC/移动/后台一致。旧订单列表、分享图不得把10 BTC旧仓显示0.01 BTC。期权、法币存取款无回归。
- 修改新规则不会直接改变历史账户余额、冻结值、订单时间与已结算profit；迁移前后按主键对比这些字段。

## 6. 执行命令与文件状态

### 6.1 本会话已提供

`C:/workspace/fx/705/scripts/crypto-quantity/Test-CryptoQuantity.ps1` 是测试调度入口。CheckOnly只检查工具、目标文件和测试存在性，不执行数据库或业务操作。当前新功能与新测试尚未编写，因此CheckOnly显示缺项是预期，不是业务验收失败。

```powershell
Set-Location 'C:\workspace\fx\705'
pwsh -NoProfile -File '.\scripts\crypto-quantity\Test-CryptoQuantity.ps1' -CheckOnly
```

### 6.2 下一会话必须实现的测试文件

- `exchange-backend/src/test/java/com/gtcfesk/exchange/trade/CryptoQuantityRulesTest.java`
- `exchange-backend/src/test/java/com/gtcfesk/exchange/trade/CryptoQuantityLifecycleTest.java`
- `exchange-backend/src/test/java/com/gtcfesk/exchange/trade/CryptoQuantityCompatibilityTest.java`
- `scripts/crypto-quantity/test.mjs`：实际导入PC/移动/后台相关计算，不可只测独立玩具公式。
- `scripts/crypto-quantity/Test-Migration.ps1`：参考现有fx-standard脚本，用唯一名称/标签和tmpfs隔离MySQL5.7；验证建表升级、配置转换、二次执行幂等、失败恢复、旧订单/余额不变。清理前核对容器名称和标签，绝不挂业务数据卷。
- `scripts/crypto-quantity/preview.sql`、`migrate.sql`、`rollback.sql`：实施后产生，当前未提供可直接执行的业务库SQL。preview只读；migrate有前置条件及持久备份；rollback拒绝回退已经产生新规格订单的环境。
- 如选择自动浏览器测试，创建 `scripts/crypto-quantity/browser.cjs` 或沿用项目工具；仅对显式测试地址和标记测试账户执行。

新Java测试必须使用mock/H2或隔离MySQL，不读取默认业务连接。测试文件命名可按项目习惯调整，但必须同步调度入口，不得通过移除用例或允许无测试成功规避失败。

### 6.3 实现后运行

```powershell
Set-Location 'C:\workspace\fx\705'
# 集中单元/业务回归、新前端计算、已有外汇与全品种断言、三个前端构建。
pwsh -NoProfile -File '.\scripts\crypto-quantity\Test-CryptoQuantity.ps1'
if ($LASTEXITCODE -ne 0) { throw '测试或构建失败，不得发布' }

# 下一会话创建并审核隔离迁移测试后再执行。
pwsh -NoProfile -File '.\scripts\crypto-quantity\Test-Migration.ps1'
if ($LASTEXITCODE -ne 0) { throw '隔离数据库迁移测试失败' }

# 在确认所有测试均不连接业务库后运行完整后端回归。
mvn.cmd -B -f '.\exchange-backend\pom.xml' test
if ($LASTEXITCODE -ne 0) { throw '完整回归未通过；区分已有故障与新增回归' }
git diff --check
if ($LASTEXITCODE -ne 0) { throw '补丁格式检查失败' }
```

前次全品种脚本可能硬编码“所有品种0.01手”和旧单位。保留旧规格测试，再新增新规格fixture；不要把历史行情快照改写成“已上线结果”，不要直接删除旧兼容断言。构建目录如被并发写入，冻结待验收源码副本；测试后比较发布文件哈希，变化则重测相关路径。

## 7. 数据库备份、迁移、发布

### 7.1 先建立可恢复备份

在任何写业务库动作前：确认目标是本项目数据库，短暂阻止相关新开仓/规格写入；记录仓位与挂单数；使用一致性dump备份，并在独立MySQL中恢复验证。备份包含敏感信息，只保存在本机受限目录，不进Git。

以下命令适用于已核实的本地默认Compose；其他环境必须替换为已核实的项目、服务和凭据来源。容器内读取密码，不把密码拼到宿主命令行或写入文档。此步骤不修改业务数据。

```powershell
Set-Location 'C:\workspace\fx\705'
$ErrorActionPreference = 'Stop'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$backup = Join-Path (Get-Location).Path "rollback\crypto-quantity-$stamp"
New-Item -ItemType Directory -Path $backup -ErrorAction Stop | Out-Null
$tempDump = "/tmp/crypto-quantity-$stamp.sql"
$mysqlId = (docker compose ps -q mysql | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or !$mysqlId -or $mysqlId -match '\s') { throw '不能唯一确认MySQL容器' }
docker compose exec -T mysql sh -lc 'test -n "$MYSQL_DATABASE" && test -n "$MYSQL_ROOT_PASSWORD" && export MYSQL_PWD="$MYSQL_ROOT_PASSWORD" && exec mysqldump -uroot --single-transaction --routines --triggers --events --hex-blob --result-file="$1" "$MYSQL_DATABASE"' sh $tempDump
if ($LASTEXITCODE -ne 0) { throw '备份失败' }
docker cp "${mysqlId}:$tempDump" (Join-Path $backup 'database.sql')
if ($LASTEXITCODE -ne 0) { throw '取回备份失败' }
if ((Get-Item -LiteralPath (Join-Path $backup 'database.sql')).Length -eq 0) { throw '空备份' }
Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $backup 'database.sql') |
    Format-List | Out-File -LiteralPath (Join-Path $backup 'database.sha256.txt') -Encoding utf8
Write-Host "备份路径：$backup；下一步必须在隔离库恢复验证。"
```

single-transaction只保证事务表数据快照，备份期间不得并发DDL；核实引擎，必要时在维护窗口暂停所有相关写入。零退出码和非空文件不是恢复验证。容器临时备份在恢复验证后按已记录的确切路径清理，禁止通配清理。源码原件、迁移前配置、镜像摘要、精确部署命令也要留档。

### 7.2 迁移要求（必须先在恢复出的隔离库演练）

1. 采用增加字段、兼容读取的顺序；MySQL5.7 DDL隐式提交，不能声称一条ROLLBACK会撤销所有DDL。
2. 生成明确的品种ID和源身份白名单；先预览实际oldLotSize/oldFeeMultiplier、订单数、目标值，禁止无WHERE全表改乘数。
3. 保存原始配置到持久备份表/文件；配置更新事务化，加锁并复验旧值；迁移标记/版本确保重复执行不再次除1000。
4. 只更新获准品种的新订单配置。不得更新历史quantity、lotSize、fee、margin、profit及账户余额；必要新增NULL快照列不改变旧数据解释。
5. 先部署能读旧新规格的后端/前端，验证旧模式，再在短维护窗口切换配置。对缓存旧页面，新协议拦截必须有效。缓存仅清理相关键，不FLUSHALL。
6. 临时禁止新开仓应不影响旧仓安全平仓；当前isEnabled是否会阻断平仓需先检查，不能直接假定可用。

下一会话生成迁移脚本后，以审核过的具体目标执行preview和migrate，不在本交接中捏造生产库名或给出无保护的UPDATE命令。

### 7.3 发布命令（仅在上述门槛通过后）

本地服务名已见于compose.yaml；执行时复核。若实际运行用了production覆盖文件，则必须使用相同文件组合，不能混用默认Compose。

```powershell
Set-Location 'C:\workspace\fx\705'
docker compose build backend admin mobile pc
if ($LASTEXITCODE -ne 0) { throw '构建失败' }
docker compose up -d --no-deps backend admin mobile pc
if ($LASTEXITCODE -ne 0) { throw '服务更新失败' }
docker compose ps
pwsh -NoProfile -File '.\docker\smoke.ps1'
if ($LASTEXITCODE -ne 0) { throw '基础HTTP/API/WebSocket验收失败' }
```

smoke只证明站点/接口/WS可达，不证明交易正确。下一步用专用测试账户验证 `/api/trade/contract/order`、`/orders`、`/balance`、`/order/{orderId}/close`、`/order/{orderId}/cancel`。登录路由/Token方式读取最新控制器或现有测试，不猜地址，不在脚本硬编码真实账号密码。后台、PC与移动端截图均遮住隐私。

## 8. 回滚与交付标准

- 尚无新规格订单：可在停止相关开仓并验证数据后恢复原配置；先保留新增兼容列，再按已验证镜像回退。不要为回滚删除列和历史数据。
- 已有新规格订单：禁止直接把品种乘数改回1000并运行不识别快照的旧代码。停新开仓、保留兼容版本，让新旧订单按自己的快照处理；优先前向修复。全库旧备份恢复会丢失备份后真实业务，绝不可作为自动回滚。
- 提交报告必须包含：目标环境、实际改动文件、迁移前后品种表、版本、未改变的旧仓/资金证据、测试命令及退出码、用例数/跳过数、构建日志、截图、备份和恢复演练结果、待部署/已部署状态。
- 不能引用之前891项或2016项测试当本次通过；记录本轮真实结果。已有无关测试失败不能掩盖，也不顺手大改其他模块。

## 9. 外部依据与限制

2026-09-29核查官方资料：
- Binance支持基础币数量、报价币名义金额与初始保证金三种输入概念：https://www.binance.com/en/support/faq/detail/437059503f11472a8c92aa4533f7859c
- Bybit区分产品category及minOrderQty、qtyStep、minNotionalValue：https://bybit-exchange.github.io/docs/v5/market/instrument
- Bybit线性合约订单成本与实际成交费用分别计算：https://www.bybit.com/en/help-center/article/Order-Cost-USDT-Contract

仅学习单位、参数、校验分层，不声称已逐币核实这些平台的最新实时限制。本项目建议数量门槛、100倍杠杆及等价固定佣金属于本项目配置，不是平台通用规范。
