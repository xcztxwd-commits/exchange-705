# 后台真实 / 模拟账户筛选查看

## 已实现

后台顶部新增“账户筛选查看”，打开只读数据窗口。默认模拟账户，可切换真实账户；按分类、用户 UID、状态筛选并分页。切换立即清空旧结果，以请求序号拦截迟到响应。查询失败保留错误，不回退到另一账户的数据。

覆盖十类：用户、三种钱包余额（资金/合约/期权）、合约订单、期权订单、充值、提现、划转、贷款及还款字段、理财订单、资产历史。只展示既有数据，不自动给尚未开通模拟账户的用户建账；不会合并真实与模拟余额。

这是查看入口，不是把原后台全局切到模拟环境。原后台操作仍针对原环境。本轮不开放模拟写操作、审核、改余额、下单、批量删除；也不将模拟环境的身份资料或管理员权限复制到真实环境。订单展示明确列出的字段，非原业务页面的全部编辑能力。

## 权限和隔离

真实后台 GET /api/admin/account-inspection 执行现有登录认证、用户管理查看权限和所选分类的查看权限。普通管理员必须同时拥有用户管理与该模块的查看权限；超级管理员按现有规则放行。代理账户拒绝该入口。前端隐藏不是权限边界，后端每次复查。

REAL 查询真实数据库；DEMO 由真实后台服务调用独立模拟服务的 GET /api/simulation/inspection。后者仅在模拟进程启用，校验至少 32 字符的服务间密钥，使用常量时间比较。无密钥、错误密钥、真实进程访问均拒绝；非 GET 拒绝。浏览器不接收服务密钥。独立服务不可用或返回环境标签不匹配时返回 503，不回退真实数据库。

查询采用固定表名/字段白名单与参数绑定，页大小最大 100；不返回密码、登录 Token、身份证号、签名图片、收款地址。响应禁止缓存。未新增跨库连接或共用资金表。

## 配置与部署边界

compose.demo.yaml 已补充真实/模拟后台之间的 SIMULATION_INSPECTION_KEY，以及真实后台 SIMULATION_INSPECTION_URL。部署时生成至少 32 字符高熵随机密钥，通过服务器环境变量或密钥管理注入两端；不要提交到源码或前端环境变量。沿用 demo-identity 网络联通，不需要让后台浏览器访问模拟数据库。

本轮未部署，尚未对生产 MySQL 和运行中的双后端环境联调。服务密钥未配置时，模拟查看会明确报不可用。

## 实测

- 后端隔离源码快照：C:/workspace/fx/new/simulation-admin-20260929-1212。
- mvn -f <快照>/pom.xml -Dtest=AccountInspectionTest test：7/7，0 失败、0 错误、0 跳过。覆盖 UID/分页、白名单/参数、十分类查询、密钥/环境限制、禁止写操作、管理员和模块权限、独立模拟 HTTP 查询及错误环境拒绝。
- node node_modules/vue-tsc/bin/vue-tsc.js --noEmit -p tsconfig.app.json（exchange-admin）：退出 0。
- node node_modules/vite/bin/vite.js build --outDir C:/workspace/fx/new/simulation-admin-20260929-1212/admin-dist：退出 0；存在原有大包警告。
- node exchange-admin/tests/accountInspection.browser.cjs：退出 0。真实浏览器 + mock API 验证模拟钱包、UID 筛选、真实/模拟切换、失败清除旧数据、仅 GET、无页面异常。Browser plugin not available，按技能使用普通 Playwright。
- docker compose -f compose.yaml -f compose.demo.yaml config --quiet：执行结果见本轮工具输出；仅结构校验，不启动容器。
- 初次 Maven 快照位于中文用户名临时路径，protoc 无法解析该路径导致失败；迁至上述 ASCII 独立目录后重新执行通过，没有修改业务代码绕过测试。

浏览器截图：C:/workspace/fx/new/simulation-admin-20260929-1212/inspection-browser.png。已查看截图，失败状态清空表格并展示错误及模拟标识。

## 文件与回滚

新增后端 AccountInspection、SimulationInspectionBoundary、AdminAccountInspectionController 与 AccountInspectionTest；新增前端 AccountInspection.vue、accountInspection.browser.cjs；Layout.vue 仅增加组件导入和入口；compose.demo.yaml 仅增加服务间查看配置。

旧文件修改前已备份在 Windows TEMP/simulation-admin-20260929-121210。未操作 Git 索引、提交或部署。回滚时只移除本轮入口、类和 compose 配置，不覆盖其他任务的并发修改。各功能原有独立模拟账户、KYC 豁免、交易逻辑未改。