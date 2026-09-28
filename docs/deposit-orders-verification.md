# 充值详情隔离验收记录

日期：2026-09-28。基线 HEAD：`0f0c294fe1505c88d64adddd92316929d111e5e6`。本记录对应当前未提交工作区，验收范围与 21 项场景以 `deposit-orders-design-and-implementation.md` 第 12 节为准。**未迁移业务库、未部署或重启、未操作真实客户资金。**

## 实际执行及证据

| 检查 | 实际入口 | 结果 | 证据 |
| --- | --- | --- | --- |
| 后端全量回归 | 隔离 Docker JDK 8/Maven 3.9.9，`mvn -o -B test`，`--network none` | 退出 0；441 tests，0 failures，0 errors，14 skipped | `reports/deposit-orders/all-tests-final.log`、`all-tests-final-exit.txt` |
| MySQL 5.7 事务/迁移 | `& ./scripts/deposit-orders/Test-MySql.ps1` | 退出 0；MySQL 5.7.44，22 tests，0 failures/errors/skipped；临时库与容器已清理 | `reports/deposit-orders/mysql-final-exit.txt`、`mysql-d1ae185e77a748f8b4f9a4dd05e5d596/` |
| 迁移重跑 | 同一隔离脚本运行 SQL 两次，逐次比对旧订单金额/状态及账户可用/冻结余额，第二次比对结构与菜单计数 | 退出 0；旧资金数据未变化；结构与菜单计数稳定为 `32/18/4/3/1`（列/索引/菜单/动作/凭据表） | 上述目录 `migration-result.txt`、`migration-1.log`、`migration-2.log` |
| 后端打包 | Windows 本机 Maven 3.9.9/JDK 21，`mvn -o -B package -DskipTests` | 退出 0；Spring Boot 可执行 JAR 生成。`skipTests` 不替代上面的测试 | `reports/deposit-orders/package-host.log`、`package-host-exit.txt` |
| 后台构建 | `npm.cmd run build --prefix exchange-admin` | 退出 0；`vue-tsc` 与 Vite 通过 | `reports/deposit-orders/admin-build-continue.log`、`admin-continue-exit.txt` |
| 用户端、PC 构建 | 各目录 `npm.cmd run build` | 均退出 0；本次续做未改这两端代码 | `reports/deposit-orders/exchange-frontend-exit.txt`、`exchange-pc-exit.txt` |
| 浏览器组件夹具 | `node scripts/deposit-orders/test-browser.cjs` | 退出 0；7 项断言通过、2 次请求、1 次夹具入账；**不是**真实后端 HTTP + MySQL 端到端 | `reports/deposit-orders/browser/result.json`、`browser-final.log` |
| 差异检查 | `git diff --check` | 退出 0；本文件写入后再执行最终复核 | `reports/deposit-orders/diff-check-final.log`、`diff-final-exit.txt` |

MySQL 脚本在回环地址发布随机端口，随机生成仅测试用库名与容器名，使用 `--tmpfs /var/lib/mysql`，Maven 加入该容器网络命名空间。没有使用业务数据库连接变量。最终 `docker ps --filter label=deposit-test-run` 无残留容器。JUnit 报告在专项目录 `TEST-com.gtcfesk.exchange.user.DepositOrderMySqlIT.xml`。全量测试的 14 个跳过项是既有外部/实时依赖类测试，不能被计入本次通过项。

## 场景对照

| 场景 | 结论与证据 |
| --- | --- |
| T01–T03 手动入账、汇率快照、三账户隔离 | 通过：`DepositOrderServiceTest` 的 USD/EUR/账户测试，MySQL 专项继承执行。余额为原值加净额，冻结余额不变。 |
| T04–T07 提交/审核/拒绝、幂等冲突、并发终态 | 通过：同一生产服务的 H2 与 MySQL 事务测试覆盖重试、双审核、审核/拒绝竞争及单一凭据。 |
| T08 并行写余额/新账户 | **部分**：与实际 `TransferController` 划转并行、两笔充值、同账户新建并发均验证；未运行充值与真实交易入口并发压测。 |
| T09 写入及提交故障 | 通过：订单、账户、凭据、flush 检查点及 `beforeCommit` 注入故障，核对整笔回滚与待审核原态。 |
| T10–T11 非法金额/币种/汇率 | **部分**：0、负数、超限、过高精度、换算为零、未知币种、余额溢出及成功单离线重试覆盖；新外币请求的过期报价场景依赖 `ForexQuoteMarketService` 抛错契约，未单独做真实报价超时集成测试。 |
| T12–T13 权限及旧 `updateBalance` | 通过：`MinimalFixRegressionTest` 用 MockMvc 检查未授权、普通管理员、代理范围、导出/详情/收款人越权；缺幂等键拒绝，旧金额分支记单，设置最终余额不记充值单。新详情审核仍需旧审核权限。 |
| T14 历史记录 | 通过：临时 MySQL 含 COMPLETED/PENDING/REJECTED 三态；双迁移不改金额与余额、不补造入账凭据；已完成历史单不重复入账。 |
| T15–T16 筛选/统计/CSV | **部分**：列表、分页、详情、汇总、导出及代理范围一致性，CSV BOM/公式前缀/引号、超长备注拒绝已测；尚未穷举全部时间边界与地址特殊字符组合。 |
| T17 用户安全 DTO | 通过：服务白名单断言与 MockMvc `/api/deposit/records`，不返回后台备注、操作人、入账凭据及余额快照。 |
| T18 迁移幂等 | 通过：MySQL 5.7.44 上连续两次执行，资金快照与结构/菜单数量稳定。仅适用于隔离夹具；生产须先备份并人工核对。 |
| T19 分类统计 | 通过：MockMvc 测试区分 RECEIPT/BONUS/ADJUSTMENT、用户单及历史未知，合计与分页核对。 |
| T20 删除保护 | 通过：带充值历史的客户无法删除，记录仍在；未使用客户仍可正常删除。 |
| T21 UI 重试与表单 | **组件夹具通过**：必填校验、响应丢失、关闭重开、同键同体重试、单次夹具入账；未接真实后端做浏览器端到端。 |

专项测试类为 `DepositOrderServiceTest` 与 `DepositOrderMySqlIT`；权限/API 等价覆盖在 `MinimalFixRegressionTest`，并非另建 `DepositOrderAccessTest`。HTML/截图与夹具证据在 `reports/deposit-orders/browser/`，只证明前端交互，不证明数据库事务。

## 失败记录与剩余门禁

- 较早定向回归 `targeted-exit.txt` 失败，原因为夹具并发版本冲突；修复后 `targeted-continue-exit.txt` 为 0。续做首次全量 `all-tests-continue-exit.txt` 为 1，原因为新代理范围测试复用固定代理 ID 污染共享 H2；改为每次创建独立代理后，`all-tests-final-exit.txt` 为 0。保留失败日志，不把失败删除或算作通过。
- 首轮 MySQL 夹具曾缺 `created_at` 而失败；修复夹具后完成专项。此次 Docker/Windows bind mount 上的 `mvn -o -B package -DskipTests` 曾在 Spring Boot `repackage` 报 `.jar.original` 缺失，见 `package-final.log`；同一源码在本机 Maven 重新打包成功，且 JDK 8 Docker 编译/全量测试和 MySQL 专项均通过。发布前仍须由发布环境复核最终镜像/制品摘要。
- 未验收业务库现状、生产 MySQL 版本及数据异常；未在真实报价服务故障、真实交易并行写入、真实后端 HTTP + MySQL 浏览器链路上做完整联测。不得因此宣称已上线或可直接开放资金写入。
- 发布前按 `deposit-orders-rollout.md` 完成独立预发布复制库迁移/对账、权限矩阵人工验收、写入口维护窗口与回滚准备。业务库迁移、部署、重启和真实资金验证必须另行人工批准。
