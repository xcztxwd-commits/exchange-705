> 历史原型文档：独立全功能版已取代简化现货页面。当前方案和验证见 demo-full-account-design.md、demo-full-account-validation.md。

# 模拟账户验证与交付清单

## 验证环境

- 工作区：`C:/workspace/fx/705`，Windows，Java 21，项目以 Java 8 目标编译。
- 前端：移动端 `http://127.0.0.1:5313/demo`；PC `http://127.0.0.1:5315/demo`。
- Browser 插件及 browser 技能未提供，按测试技能使用已经安装的 Playwright + 本地 Chrome 无头模式，没有安装新依赖。
- 视口：390 × 1000、1440 × 1000。
- 浏览器测试拦截 API，使用确定性的虚拟行情与未实名用户，不是生产数据、不是已部署后端验收。
- 事务测试使用 `jdbc:h2:mem:demo-isolation`；安全测试使用生产 SecurityConfig/JwtFilter 与可控 token 解析 fixture。

## 验证项

| 验证 | 结果 |
| --- | --- |
| 无实名用户开通；匿名 401；代理 token 403 | 通过 |
| 同时开通只赠金一次 | 6 个并发连接，通过 |
| 同请求键同时买入只扣款一次 | 6 个并发连接，通过 |
| 同一订单并发卖出不重复入账 | 6 个并发连接，通过 |
| 并发不同订单超额消费 | 仅一笔成功，无透支，通过 |
| 注入流水持久化失败 | 订单与现金一起回滚，通过 |
| 他人订单、冻结账户、金额越界、精度、重复键冲突 | 拒绝，通过 |
| 重置有仓位限制、24 小时冷却、旧 generation 拒绝 | 通过 |
| 行情过期/断源 | 拒绝成交，估值不伪造为零，通过 |
| DEMO 上下文请求真实交易/提现/划转/借贷 | 后端阻止，通过 |
| 浏览器入口、非空、标题、框架错误覆盖层 | 通过 |
| 浏览器丢失买入响应后同参数重试 | 请求键不变，不生成第二笔，通过 |
| 买入、持仓、卖出、历史、重置、切换真实 | 双端通过 |
| 浏览器运行时异常、横向页面溢出 | 未发现 |

后端总计 15 个测试：DemoTradingTest 8 个、DemoPersistenceTest 3 个、DemoSecurityTest 4 个。

最终移动端、PC 的 TypeScript 检查与 Vite 构建均通过；存在既有大体积 chunk 提示，不是构建错误。

## 重现命令

```powershell
cd C:/workspace/fx/705/exchange-backend
mvn '-Dtest=DemoTradingTest,DemoPersistenceTest,DemoSecurityTest' test
cd C:/workspace/fx/705/exchange-frontend
npm run build
cd C:/workspace/fx/705/exchange-pc
npm run build
cd C:/workspace/fx/705
node exchange-frontend/tests/demoAccount.browser.cjs
```

浏览器测试要求先在对应目录启动 Vite：移动端 `npm run dev -- --host 127.0.0.1 --port 5313 --strictPort`，PC 端口改为 5315。

## 产物与修改范围

- 新增后端 `exchange-backend/src/main/java/com/gtcfesk/exchange/demo/`：3 个实体、3 个仓储、服务、控制器、边界拦截器。
- 新增迁移 `exchange-backend/src/main/resources/db/migration/create_demo_accounts.sql`。
- 新增后端测试 `exchange-backend/src/test/java/com/gtcfesk/exchange/demo/`。
- 双端新增 `src/views/DemoTrading.vue`、`src/components/AccountModeSwitch.vue`、`src/utils/accountRequests.ts`。
- 双端仅在现有 `App.vue`、`router/index.ts`、`utils/request.ts` 增量接入；未替换已有真实交易与实名逻辑。
- 新增浏览器测试 `exchange-frontend/tests/demoAccount.browser.cjs`。
- 修改前集成文件备份：`rollback/demo-account-20260929/`。工作区原本已有大量其他修改，未清理、提交或回滚它们。

## 截图证据

截图保存在 Windows 临时目录 `C:/Users/徐乾妖/AppData/Local/Temp/demo-account-qa/`：

- `pc-empty.png`、`pc-position.png`
- `mobile-empty.png`、`mobile-position.png`
- `results.json`

已经检查 PC 持仓态及移动端长页面：账户提示、资产卡、输入/费用、成交入口、持仓操作、重置和教程均可见，无页面横向溢出。截图中的价格 100 是测试 fixture，不是实际 BTC 报价。

## 未验证/未交付边界

1. 没有迁移或重启生产服务；没有做真实行情与真实登录环境的完整 E2E。
2. H2 并发测试不能代替预发 MySQL/InnoDB 的死锁、锁等待和迁移测试。
3. 初版仅现货市价买入/整笔卖出，未实现合约、外汇、期权、杠杆、强平、限价、部分成交。
4. 未做真实盘口滑点、市场冲击、专业交易仿真或盈利能力验证。
5. 未做全语言母语审校、屏幕阅读器人工测试、浏览器全矩阵及负载压测。
6. 工作区有并行修改；一次移动端构建曾受新增 SupportThread.vue 的 Array.at 类型错误阻断，未修改该不相关文件；其变更更新后，最终移动端重新构建通过。

完整产品与架构设计见 `demo-account-design.md`。上述边界应在预发验收单中继续跟踪，不能把“代码和 mock UI 通过”表述为“生产已上线”。
