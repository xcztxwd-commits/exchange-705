# MT01 手动订单杠杆范围修复交付

2026-09-29，Asia/Singapore；测试完成约 04:41。

## 结论与范围

MT01 已修复并完成定向验收。原独立验收的两个严格反例均通过拒绝断言，未修改独立测试断言。未部署、未提交推送、未操作业务数据库、行情、正式订单或资金；未改客服文件，未使用共享 `exchange-backend/target`。

这是算法和真实隔离 MySQL 服务层验收，不是生产 HTTP 或浏览器真实下单的端到端验收。本轮没有改前端，也没有复跑浏览器和全仓测试。

## 修复

- `ManualOrderCalculation` 增加手动杠杆范围检查，允许两位小数；品种上限未配置时为 100，配置上限须在 1–100。纯算术 calculate 保留原来的数学计算能力，不作为创建许可。
- `ManualOrderGenerator` 接收品种有效上限。候选必须同时满足 1–上限和目标 ±5%；额外加入 1 与品种上限作为候选，避免边界交集漏解。反算杠杆也执行同样过滤。无交集明确拒绝，不先生成再截断杠杆。
- `ManualOrderService` 复用 MarketCategoryService：分类禁用杠杆时上限为 1。generate 搜索、preview 计算、create 最终计算均校验；create 在品种共享锁内额外读取最新数据库 max_leverage/category，管理员遗漏 row_version 更新也不能绕过上限。
- 保留五目标容差、零净收益精确匹配、固定时间/方向、历史分钟价格、数量规格、金额、权限、事务和幂等断言。

旧测试中 600 倍或近 1 亿倍的“数学可行”生成用例改为应拒绝的品种规则测试；低余额比例用例使用余额 10 的合法范围内解。不是放宽生产边界或将错误结果算作通过。纯算术的历史高杠杆测试保持通过。

## 本轮实际结果

1. 定向七类 **695/695**，失败/错误/跳过均 0。包含新增 ManualOrderLeverageBoundsTest 15 项：上下限、±5% 交集精确边界、紧邻超界、无可行候选、未指定杠杆、1 倍、10.4 小数、非法配置；原五目标扰动、公式和矩阵全部保留。
2. 独立 MySQL 原服务套件新增两项，**ManualOrderMySqlIT 39/39**；与 CalculationTest 5 项合计该命令 **44/44**。新增检查覆盖直接 preview/create 绕过 generate、有效预览后数据库上限降低但版本不变、分类关闭杠杆、合法 10.4 持久化；拒绝时订单/审计/钱包不变。
3. 原验收 `ManualToleranceIndependentTest#allTargetsIndependent+rejectConfiguredMax+rejectSubOneLeverage` 原样复制到独立快照，另起全新隔离数据库，**3/3**：max=5 拒绝目标 10，拒绝目标 0.5，合法原生币五目标独立公式和只读断言通过。

各组有重复测试，不将数字相加宣称独立场景总数。

## 独立环境、命令及退出码

证据根目录：`C:/workspace/fx/new/manual-mt01-fix-20260929`。

共享源码复制到该目录的 `source`，排除 target/uploads；Maven 输出只在独立副本 target。Java 21 / Maven 3.9.9；MAVEN_OPTS=-Xmx256m，测试 JVM -Xmx768m。重型 Maven 串行运行。两个 MySQL 5.7 实例均随机名、随机回环端口、tmpfs、2 CPU/2GB，仅合成数据，runner 校验标签后删除；结束时测试容器列表为空。

实际命令：

```powershell
$env:MAVEN_OPTS='-Xmx256m'
mvn -B -f C:/workspace/fx/new/manual-mt01-fix-20260929/source/exchange-backend/pom.xml '-Dtest=ManualOrderLeverageBoundsTest,ManualOrderToleranceTest,ManualOrderGeneratorTest,ManualOrderGenerationMatrixTest,ManualOrderFeasibilityTest,ManualOrderScreenshotRuleTest,ManualOrderCalculationTest' '-DargLine=-Xmx768m' test
& C:/workspace/fx/new/manual-mt01-fix-20260929/source/scripts/manual-order/Test-MySql.ps1
& C:/workspace/fx/new/manual-mt01-fix-20260929/source/scripts/manual-order/Test-Independent.ps1
```

- `focused-final.log` / `focused-final-exit.txt`：exit 0，695 项。
- `mysql.log` / `mysql-exit.txt`：exit 0，44 项。runner 实际 Maven 参数为 `-Dtest=ManualOrderCalculationTest,ManualOrderMySqlIT -DargLine=-Xmx768m test`。
- `independent.log` / `independent-exit.txt`：exit 0，3 项。runner 实际 Maven 参数为 `-Dtest=ManualToleranceIndependentTest#allTargetsIndependent+rejectConfiguredMax+rejectSubOneLeverage -DargLine=-Xmx768m test`。
- XML 在 `source/exchange-backend/target/surefire-reports`。
- 首次 `focused.log` exit 1：新增依赖后遗漏一个测试构造器，已补齐；`focused-retry.log` exit 1：纯算术测试被错误地纳入全局业务限制，已恢复纯算术原行为，只在生成/服务边界限制。失败日志完整保留，最终结果未覆盖它们。

## 源码版本与回滚

`before` 保存本轮修改前文件；`source-before.json` 为本轮初始责任文件指纹，`source-tested.json` 为初始完整快照指纹，`final-source-hashes.json` 为最终 7 个责任 Java 文件共享源码与实际测试副本 SHA-256 比较，全部一致。后者是最终验收版本，不能用初始快照指纹代替。

生产文件最终 SHA-256：

- ManualOrderCalculation.java：`b7a5ee505a924b4b70634f45f8d09ed79d7edf11ff09f7195a5b108b107f6cf4`
- ManualOrderGenerator.java：`b77091060db45174ec2cd951405da3c009589a71619ca960ce4b1b711afdc2c7`
- ManualOrderService.java：`acbcfd66b73eed5d3bb95ed3239932ebaa36b47dc91871c9f8c05a73d6c88981`

回滚前须对照上述最终指纹，确认没有他人后续修改，再从 before 逐文件恢复；新增范围测试可随本轮补丁撤回。不要整体覆盖共享工作区。

## 未验证

未运行 HTTP 登录到实际生成/创建接口、浏览器直连数据库服务、生产部署或全仓回归。已有服务层结果不能替代这些验收。其他会话可能继续修改非责任文件，项目整体未冻结。
