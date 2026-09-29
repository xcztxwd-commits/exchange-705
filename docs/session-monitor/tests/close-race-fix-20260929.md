# 平仓并发冲突修复与回归（2026-09-29）

## 结论
已落地最小修复，未部署、未重启业务服务、未执行业务数据库写入。
修复的是：后台只更新显示价格后，仍为 OPEN 的订单因版本过旧导致正常平仓返回 400。
不宣称项目所有缺陷已清零，也不宣称所有并发交错均已穷尽。

## 根因和改动
订单在行情/账户锁之前加载。等待期间后台更新 currentPrice 并提交 rowVersion；原平仓继续使用旧实体，提交发生乐观锁冲突。
在公共 settleOrder 中保留原行情、资金锁顺序，取得资金锁后使用 EntityManager.refresh(order, PESSIMISTIC_WRITE) 进行当前读并锁定订单；再次确认 OPEN，再按刷新后的订单计算和结算。
保留 @Version、权限校验、服务端新鲜行情、费用/试用金规则与事务回滚；不吞异常、不在已回滚事务内重试。
已关闭订单仍拒绝重复平仓，并非把重复请求伪装为成功。

改动文件：
- C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ContractOrderService.java:267-275（生产代码净增 3 行）
- C:/workspace/fx/705/exchange-backend/src/test/java/com/gtcfesk/exchange/market/ContractCloseConcurrencyTest.java（新增 MySQL/Redis 并发回归）
- C:/workspace/fx/705/exchange-backend/src/test/java/com/gtcfesk/exchange/trade/CryptoQuantityPersistenceTest.java（重复平仓断言改为准确的 CLOSED 状态业务拒绝；仍验证一成功一失败及余额）

## 测试流程及实测
1. 保留原诊断红灯，复制独立源码快照；生产文件原哈希校验后才原子回写，未覆盖其他会话修改。
2. 先跑原严格回归：原断言要求价格刷新后正常平仓 HTTP 200，修复后通过；未放宽该断言。
3. 扩展真实 HTTP/JWT/业务服务/MySQL 5.7/Redis 7 并发回归，再用全新临时容器独立复跑。
4. 金融相关定向测试 1061 条全部通过。
5. 后端默认完整测试：2259 条，2225 通过，34 因环境开关跳过，0 失败、0 错误。该计数是独立快照，不是所有持续变动的共享文件；定向测试与全量测试有重叠，不能相加。
6. 最终独立 MySQL 回归 11 条全部通过（0 跳过）：
   - 自动检查仅刷新显示价格后，用户平仓成功，重复 3 次；
   - 强平检查仅刷新显示价格后，用户平仓成功；
   - 混合试用金订单在价格刷新后正常结算；
   - 管理员平仓遭遇价格刷新仍成功；
   - 双请求竞争同一订单，仅一次结算；
   - 自动止盈与用户平仓竞争，仅一次结算；
   - 另有持仓冻结资金时，不能重复释放资金；
   - 非订单所有者被拒绝且不变更资金；
   - 无新鲜行情被拒绝且不变更资金。
7. 临时 MySQL/Redis 使用 tmpfs、随机 localhost 端口、专属标签；已清理，未修改现有容器。

资金断言包括现金余额、冻结资金、试用金本金/收益、账本条数、订单状态，以及其他持仓不被释放。
测试订单为合成数据；不覆盖完整开户到下单流程。本轮不重跑全部前端端到端测试，不替代上线验收或长期压测。

## 中间失败记录
- 第一次脚本误用 Windows PowerShell 5，启动就绪探测遇 stderr 提前退出；改用 PowerShell 7，核对标签后清理该次两个临时容器。
- 第一次默认全量出现 10 个错误，全部来自仅用于保留严格红灯的临时子测试类：JUnit 环境条件未继承，错误启动数据库上下文。给该临时类显式添加环境开关后全量复跑成功；没有改变业务断言。此子类未写回项目。
- 历史失败日志与成功日志均保留。历史诊断目录未修改。

## 证据与重跑
独立快照/日志/备份目录：
C:/workspace/fx/new/close-race-fix-20260929-101814

- close-race.log：最终 11 条 MySQL 回归成功
- financial-regression.log：1061 条定向测试成功
- backend-suite.log：默认全量最终结果
- evidence/first-green.log：严格旧红灯转绿及初始 7 条回归，共 8 条通过
- evidence/expanded-12-green.log：扩展 11 条及严格旧红灯，共 12 条通过
- evidence/backend-suite-initial-fixture-gate-error.log：中间夹具错误
- evidence/backend-suite-green/：全量 XML/TXT
- promoted-hashes.json：写回文件 SHA-256
- remaining-test-containers.txt：为空，清理完成
- backup/：写回前原文件

隔离并发回归重跑（需现有 Maven 缓存、Docker 镜像及 PowerShell 7）：

    pwsh -NoProfile -File "C:/workspace/fx/new/close-race-fix-20260929-101814/run.ps1" -Report "C:/workspace/fx/new/close-race-fix-20260929-101814"

该脚本只测试固定快照。测试使用 create-drop，只可启用在专用空测试库，禁止连接业务数据库。

## 回滚
未部署，因此无需回滚业务数据。
若需撤销本轮代码，只逆向撤销 settleOrder 的本轮小段及对应测试变更；先检查 promoted-hashes.json，文件若已被其他会话改动，禁止整文件覆盖。
原文件备份不包含此后他人新增工作，不能直接盲目覆盖共享目录。