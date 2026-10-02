# 平仓乐观锁竞争：第一阶段诊断

日期：2026-09-29，独立快照约09:51，诊断约09:52–10:08（Asia/Singapore）。

## 结论

**已可控复现真实缺陷，尚未修复生产代码，发布回归仍失败。**

- 后台价格刷新可使正常用户平仓返回HTTP400“平仓失败: 数据已变更，请刷新后重试”。两条生产写入路径均已验证，不再只是时间相关猜测。
- 诊断套件最终7个调用通过，含4次明确期待并确认HTTP400的缺陷复现；“诊断通过”不代表业务问题解决。
- 独立行为回归要求平仓HTTP200，实测400，1项失败、Maven exit1。保留为未修复发布闸门。
- 已测交错下，失败事务的资金/流水回滚；重复手动平仓、自动与手动竞争、存在其他订单冻结资金时均只结算一次。API重复调用仍返回400，不是返回相同成功结果的幂等接口。
- 共享生产/测试源码均未改。本轮只在独立快照新增两个夹具，在共享项目新增本报告；不接续其他验收任务、不部署、不操作Git索引。

## 1. 确认的竞争路径

快照主文件：`C:/workspace/fx/new/close-race-20260929-095129/source/exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ContractOrderService.java`。

1. `closeOrder`（253–258）普通读取订单并检查归属；`settleOrder`（267起）先检查OPEN，此时实体持有rowVersion=0。
2. 用户事务尚未进入真实`quotes.freshPrice`时，夹具用CountDownLatch暂停该请求。没有修改订单值、替换事务或伪造数据库错误。
3. 另一个事务调用真实生产方法：
   - `checkAndAutoCloseOrders`（426–444）：即使无止盈/止损触发，仍对托管实体`setCurrentPrice`；事务提交脏检查写库，rowVersion从0变为1。
   - `checkAndForceCloseOrders`（493起、542）：即使持仓盈利、没有强平条件，也会更新托管实体currentPrice；同样提交并推进版本。该分支也已单独实测。
4. 用户请求恢复，使用真实本机HTTP行情得到105。控盘品种锁、用户/资金锁并不刷新先前读取的订单实体；`settleOrder`仍持有旧版本0。
5. 平仓与资金变更到事务提交时，Hibernate执行`UPDATE contract_order ... WHERE id=? AND row_version=0`影响0行，抛出`ObjectOptimisticLockingFailureException` / `StaleStateException`；整笔事务回滚。
6. `ContractOrderController.closeOrder`（164–196）捕获异常并映射HTTP400。

生产调度来源：`MarketOrderProcessor.start` 每秒依次执行挂单撮合、`checkAndAutoCloseSnapshotOrders`及`checkAndForceCloseOrders`。第一条再调用`checkAndAutoCloseOrders`。两个刷新分支都是真实竞争写入者，而不只是测试代码直接UPDATE。

历史D04第三轮日志与此异常一致，但旧日志没有记录竞争线程/交错点，**不能断言旧失败当次一定来自两个分支中的哪一个**。本次证明两条路径均足以产生同类错误。

### 品种锁为何未消除问题

`PersistentPriceControl.display`调用`ControlHistoryStore.locked`；后者在事务中`SELECT ... trading_symbol ... FOR UPDATE`。此锁获得时订单已经读入旧版本，所以串行化行情读取不等于刷新订单快照。

诊断曾把屏障放在资金锁之前，结果太晚：用户已持有品种锁，后台行情读取被阻塞，屏障超时。线程栈准确显示主线程卡在`ControlHistoryStore.locked`，因此调整到**订单读取之后、真实行情/品种锁之前**。没有通过放宽超时掩盖阻塞。

## 2. 可控夹具与资金验证

独立根目录：`C:/workspace/fx/new/close-race-20260929-095129`。

新增文件仅：
- `source/exchange-backend/src/test/java/com/gtcfesk/exchange/market/CloseRaceDiagnosisTest.java`
- `source/exchange-backend/src/test/java/com/gtcfesk/exchange/market/CloseRaceBehavioralRegressionTest.java`

真实部分：Spring事务、Controller、JWT校验、AuthService登录签发token、Repository、MySQL、Redis、TrialFunds、行情/控盘计算。平仓走随机端口的真实TCP HTTP；登录通过真实AuthService，不宣称登录HTTP端点也测过。

替代/控制部分：供应商为父夹具提供的本机HTTP合成行情；`@SpyBean ForexQuoteMarketService`仅在屏障处暂停，随后`callRealMethod`，不替换价格计算；`@MockBean MarketOrderProcessor`只关闭非确定性定时器，再显式调用它原本调用的真实业务方法。其余结算服务和数据库未mock。

合成订单：开仓价100、当前报价105、BUY、数量1、乘数10、保证金100、手续费2，毛利润50、净增48。账户和订单直接种入独立数据库；不是开仓全流程验收。

### 最终7个诊断调用

- `quoteRefreshRace`重复3次：确认后台版本0变1、订单仍OPEN；用户HTTP400、真实可用898/冻结102和活动资金/流水不变。另发明确重试请求才成功，账户1048/冻结0；再重复平仓400且不重复入账。
- `forceCheckRefreshRace`1次：相同断言，写入者换为真实强平检查；持仓盈利，没有真正触发强平，仍能复现冲突。
- `duplicateManualClose`1次：两HTTP请求均先读OPEN，再一起放行。混合资金初始真实可用938/冻结62、活动冻结40；结果一200一400，真实可用1048/冻结0、活动可用40/冻结0、活动利润48，结算流水恰好1条。
- `autoCloseRace`1次：手动请求先读OPEN后暂停；真实止盈检查先平仓。随后手动返回400，混合资金结果与上项相同，仅1条活动结算流水。
- `duplicateWithUnrelatedReserve`1次：额外保留另一笔OPEN订单，真实可用796/冻结204。两次同订单平仓一200一400，失败明确为版本冲突而不是余额不足；最终可用946/冻结102，另一笔订单仍OPEN、版本0，未盗用其冻结资金。后续重复调用仍不改变结果。

最后一项防止“只有冻结资金为0才避免重复扣款”的假阳性。以上仍不是所有并发排列、所有资金状态的数学证明。

### 明确失败的行为回归

`CloseRaceBehavioralRegressionTest#userCloseShouldSucceedAfterDisplayRefresh`使用同一真实交错，期望有效平仓200；实测400，严格断言失败。失败后账户仍为初始状态。

该测试没有被删除、标记跳过或改为接受400。诊断用例接受预期缺陷与发布回归要求缺陷消失，二者分开保存。

## 3. 源码指纹与隔离边界

- 保存了420个原始backend src/pom文件；逐文件SHA-256见`source-before.json`，清单哈希：`4614379AD3921D9733B992C95F28AEC7D6E83C85C4BFFE87F069AE04A2A3E97E`。
- 原420个文件结束核验修改0个：`original-source-changes.json`为空数组；仅增加上述两个夹具，见`added-fixtures.json`。
- `ContractOrderService.java`快照SHA-256：`9790C9545DE8FB976AFD33F46DC052E86935DB6364E2FD64C58578755327BA37`。
- 最终诊断夹具SHA-256：`5076FBDC3B6128962AD493DD4CD73C28109F2E88E5FB6B0ACB5A0CB455A68300`。
- 行为回归夹具SHA-256：`2425B8398B9A9D273D525C5684E1EC09A4FC56D849F55363EA3453DB310F280D`。
- 7个关键生产文件快照/共享树核对见`relevant-source-hashes.json`；当次核对一致，不代表整个并行变化工作区已冻结。
- 日志、XML、运行器和夹具完整哈希清单见`evidence-hashes.json`。

每轮独占MySQL5.7/Redis7，随机127.0.0.1端口、tmpfs、合成数据；仅清理`qa705-close-race=true`标签核验过的本轮容器。邮件配置为127.0.0.1:1，外部行情URL覆盖本机，WebSocket关闭/指向本机拒绝端口。最终Maven使用`-o`离线。没有复制或读取业务dump/业务库，没有部署/重启业务服务，没有共享target写入。

最终测试容器已清理，`remaining-test-containers.txt`为空。未开展平台拦截的客服任务。

## 4. 命令与退出码

运行目录：`C:/workspace/fx/705`。运行器配置临时数据库环境，不能单独省略它的隔离连接配置执行应用。

```powershell
$q = 'C:/workspace/fx/new/close-race-20260929-095129'
& "$q/run-offline.ps1" -Report $q
& "$q/run-regression-offline.ps1" -Report $q
```

内部最终命令：

```powershell
mvn.cmd -o -B -f "$q/source/exchange-backend/pom.xml" '-Dtest=CloseRaceDiagnosisTest' '-DargLine=-Xmx768m' test
mvn.cmd -o -B -f "$q/source/exchange-backend/pom.xml" '-Dtest=CloseRaceBehavioralRegressionTest#userCloseShouldSucceedAfterDisplayRefresh' '-DargLine=-Xmx768m' test
```

- 最终诊断：Maven exit0，7 tests / 0 failures / 0 errors / 0 skipped。`close-race.log`，`evidence/TEST-com.gtcfesk.exchange.market.CloseRaceDiagnosisTest.xml`。
- 最终行为回归：Maven exit1，1 test / 1 failure / 0 errors / 0 skipped。`behavioral-regression.log`，对应BehavioralRegressionTest XML。
- `exits.txt`记录各次Maven真实退出码。运行器随后执行日志输出及容器清理，外层PowerShell可能退出0；**不得把该外层退出码冒充测试通过**。
- 初期夹具失败保留：`attempt1-content-type.log`是HTTP测试客户端漏设JSON类型；`attempt2-barrier.log`、`attempt3-control-lock-diagnostic.log`是屏障放在品种锁之后造成超时，不算新业务缺陷。第三份含线程栈，不再使用外部jstack结果作证。
- 中间5/6项诊断通过证据也保留；只以最终7项及严格红色回归作为本阶段结论，不以通过次数抵消历史D04失败。

## 5. 最小修复范围建议，尚未实施

建议调度先协调以下文件所有权：

1. 主修复候选：`C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ContractOrderService.java`。
2. 回归：本轮独立两个夹具；获准后再决定迁入共享test目录。
3. 如果采用仓储锁查询封装，再协调`ContractOrderRepository.java`；已有EntityManager可用，不必预先新增仓储抽象。

修复必须保证：决定平仓/计算资金前，在一致锁序下重新读取或刷新并锁定订单，重新检查归属、状态及结算快照；不能继续使用行情/账户锁之前加载的旧实体。也可评估在完整事务回滚后进行有界事务级重试，但不能在同一rollback-only事务中捕获后继续保存。

还须覆盖两条后台价格写入路径。不能只删`@Version`、吞400、重复执行资金返还、只改controller返回成功，或只给一个后台循环加锁。盲目让平仓先锁订单再锁用户可能与既有用户/品种/订单锁序形成死锁；具体锁序与备选方案必须在下一阶段验证。

本轮仅确认根因与候选范围，**没有验证任何生产补丁，也没有声称上述设计已经通过回归**。默认不需要修改全局控盘锁、TrialFunds或其他负责人功能。

## 6. 未验证项

- 历史D04失败当次到底是哪个后台循环获胜，旧证据不足。
- 多实例部署、高压力/长时间竞争、死锁排列、所有订单类型和资金来源。
- BASE_ASSET/Forex新数量规格在本轮并发夹具中的全矩阵；本轮种入的是合法历史LOT快照。
- 管理员HTTP手动平仓、客户端自动重试、软删除/恢复与平仓交叉、强平亏损分支、挂单成交竞争。
- 全项目、最新并行变化版本、发布与线上表现；未重跑全量。

下一阶段应先拿到文件所有权，再落地最小修复，使严格行为回归由红变绿，并保持资金/流水恰好一次。原始D04失败和本次红色回归均继续保留。
