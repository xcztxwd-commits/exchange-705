# 工单 1：真实市场深度交付与验收

日期：2026-10-03，Asia/Singapore。项目：`C:\workspace\fx\705`。起始与当前 Git 基线：`main / 6b93d62b5a3e748162f9eaea02f851e9a188e30e`，本次未提交、未回退其他成果。

## 结论与边界

已完成设计先行、真实来源适配、可运行的公共 REST/独立深度订阅、集中同步与有界缓存、租户持久化开关、后台健康管理及隔离验收。相关自动测试 35 项通过；后台构建、租户前端测试和浏览器交互通过；Binance 现货与单独选择 OKX 的匿名真实连续验证分别持续约 65 秒。不是空接口或运行时示例盘口。

**实现状态与外部验证状态分开**：Binance 永续在本机仍超时，真实目录/REST 未验证成功，因此该通道真实 WS 未启动验证；同协议的隔离 WS 测试已通过。展示/再分发许可仍是上线门槛。依照工单“网络阻塞时完成可验证核心并准确列出缺项”的分支交付；不声称全品种、全球网络、长期 SLA 或生产联调完成。

手机与 PC 只交付 Figma 与对接合同，`exchange-frontend`、`exchange-pc` 及其产物没有修改。未部署生产、未执行订单、未修改价格控制、资金、用户授权、账户余额或结算核心。没有新增依赖、微服务或数据库表。

## 合同与实现对应

唯一业务合同：[market-depth-contract.md](C:/workspace/fx/705/docs/market-depth-contract.md)。

| 工单要求 | 当前实现与证据 |
|---|---|
| 当前供应商与市场映射 | 沿用 `ExchangeQuoteSource` 的 `EXCHANGE_MARKET_PROVIDER`，本机默认 Binance；先校验租户目录和 spot/swap 分类，再读所选来源官方目录。无隐式跨源回退，未索取账户 Key。 |
| 公共真实深度 | `GET /api/market/depth/{symbol}?levels=1..20&marketType=spot\|swap`，正常直接 JSON；参数 400、未知/停用 404、容量 429；外汇/指数或不存在交易所品种明确 UNSUPPORTED，无假盘口。 |
| 精度与单位 | BigDecimal；价格、数量、累计量、价差、比例、sequence 和 instrumentId 为字符串。Binance 现货及 USDT 永续数量为官方 baseAsset；OKX swap 数量为张，ctVal/ctMult/ctValCcy 直接来自官方规格，不猜面值。 |
| 同步和异常顺序 | Binance depth20、永续 depth20@500ms、OKX books5 全部完整快照替换。完整快照跳号不是漏增量；倒序、同序不同内容或时间回退拒收并重同步。连接代数、品种匹配避免旧消息污染。零量价档剔除，不补齐实际档数。 |
| 时间与降级 | Binance 现货源时间未提供时 sourceAsOf=null，明确 NOT_PROVIDED；不冒充真实 asOf。同源 REST 至少 5 秒刷新，REST_POLL 显示“周期刷新”；15 秒过期保留最后真实时间和数据，标 STALE。 |
| 独立订阅与慢客户端 | `/api/ws/market` 保留 price 协议，新增 subscribeDepth/unsubscribeDepth 与 `type=depth`。每连接一个目标，最多 1 次/秒；切换、断开、禁用释放引用；旧排队目标发送前再次验证，发送工作池/排队长度有界。 |
| 生命周期与限流 | 32 个共享外部品种、512 个引用、每租户 16 个 REST 活跃目标、60 秒 REST 空闲回收；两个采集线程/32 个排队任务；Retry-After、失败退避。官方规格缓存 64 项/1 小时，活跃品种每小时复查下架；配置 DB 故障也回收过期引用。 |
| 管理、权限和租户 | 系统设置“行情配置”加入来源健康；GET `/api/admin/market/depth/status` 需 settings 权限，PUT `/api/admin/market/depth/settings` 另需 save。只接受 `{enabled:boolean}`，保存现有租户 system_config，提交成功后立即回收该租户需求；其他租户继续运行。 |
| 原型与范围 | 首页摘要、探索入口、手机/PC 深度和全部状态均可编辑；来源、单位、档数、参考说明、时间为空时的文案对应真实合同。未将平台报价平移到外部深度上。最终范围与源码 SHA-256 见 scope-and-hashes.json。 |

管理实现文件：
- [MarketDepthHealth.vue](C:/workspace/fx/705/exchange-admin/src/components/MarketDepthHealth.vue)：查询、刷新、保存禁用/启用、最近错误、连接、档数、单位和时间。
- [Settings.vue](C:/workspace/fx/705/exchange-admin/src/views/Settings.vue)：沿用当前 tab、权限和租户配置可编辑策略；只在行情 tab 激活时挂载健康组件。
- [AdminMarketDepthController.java](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/admin/AdminMarketDepthController.java)：现有 AdminPermission、严格 Boolean 和提交后回收。
- MarketDepthController/MarketDepthService/ExchangeDepthSource/ExchangeDepthStream/DepthBook：公共合同、共享缓存、官方取数、有限档流和精确计算；MarketWebSocketHandler 只增加独立深度，不替换价格协议。

REAL/DEMO 允许共享无账户的外部公共数据；管理员配置与品种可见性仍按租户隔离。没有新增用户资金或交易状态读写。后台支持状态分“官方目录已确认”和“待访问验证”，不会将所有后台列表项自动称为已支持。

## 原型交付与对接规格

Figma 文件 `v4V9L597ML6EhPMLi3kW8O`，保留 Noto Sans SC、现有绿色体系、五项导航和 A 版实例。没有删除其他完整页面。

- [手机首页摘要](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=34-428)、[探索入口](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=91-2235)：来源、数量单位、最多档数与 SYNCING 上下文；跳转合同携带 instrumentId/symbol/marketType/levels。
- [手机深度 390](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=37-2315)、[手机状态与规格](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=185-6347)。
- [320](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=189-6358)、[375](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=189-6447)、[430](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=189-6536)：实际可编辑尺寸，不只是文字声明；内容纵滚、元数据自动扩高，图形按宽度缩放。
- [PC 深度 1440](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=55-2643)、[PC 状态](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=186-2942)、[1280](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=191-2956)、[1920](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=191-3075)：弹层最大 1240px、双列，1280 对应 1152px 内容宽度。
- [320 字段压力核验](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=195-6535)：长 instrumentId/英文品种/中文说明/日期、极大极小十进制、零量过滤、负值拒收、空侧为 —。640px 数值表在 288px 视口内横滚，输入样例明确标注不是行情。

可见状态含 LIVE、SYNCING、STALE、UNSUPPORTED、ERROR、DISABLED 和周期刷新，均有文字说明，不仅靠颜色。原型曲线仍明确标成设计示意，PC 数字用 —；禁止把静态图称为用户端已接通。首页是详情摘要；正式用户端代码接入仍需另行授权。

保存的最终结构与截图：[figma-final-audit.json](C:/workspace/fx/705/reports/depth-20261003/figma-final-audit.json)、figma-stress-edits.json、figma-mobile-states.png、figma-mobile-320.png、figma-pc-1280.png、figma-stress-320.png。压力板初稿发现文本被 resize 固定高度，已改成 HEIGHT/HUG，并以最后截图核验，无叠字。

## 真实来源验证

公开匿名、只读，无注册、购买、Key 或账户请求。两次运行分别显式选一个供应商；OKX 是隔离适配器验收，不是应用失败后回退，应用配置没有被改为 OKX。

| 真实运行 | 观察证据 | 结果 |
|---|---|---|
| Binance | 04:53:28.832–04:54:39.069 UTC，连续观察 65,168ms；现货 REST+官方规格成功，各 20 档；spot 收到 60 帧、3 次连接；BTC 40/ETH 18 个不同序号 | 25 秒强制重连、45 秒切 ETH；最后旧 BTC 缓存已回收，无错误。Binance spot sourceAsOf 真实为空。 |
| OKX（单独隔离选择） | 04:55:29.057–04:56:35.364 UTC，连续观察 65,110ms；spot/swap REST+规格成功；spot 98 帧/3 次连接，swap 460 帧/1 次连接；不同序号 45/33/234 | books5 实际不超过 5 档；重连、切 ETH、旧目标回收成功。swap 单位 CONTRACT，ctVal=0.01、ctMult=1、ctValCcy=BTC。 |
| Binance 永续 | 初次匿名 depth 请求连接超时；最终生产适配器的官方目录/REST 路径仍 timeout（约 4060ms） | 没有 REST 成功证据；目录未确认，所以真实 WS 未尝试。未用 OKX 贴 Binance 标签。需在部署网络补验。 |

原始盘口与序号：[live-binance.json](C:/workspace/fx/705/reports/depth-20261003/live-binance.json)、[live-okx.json](C:/workspace/fx/705/reports/depth-20261003/live-okx.json)、source-validation-summary.json、rest-probe.json、contract-spec-probe.json。65 秒是短时验证，不是长期稳定性证明，sourceAsOf 与 receivedAt 按实际记录保留。

首次墙钟观测遇主机时钟跳变，不作为有效时长证明（live-binance-clock-jump.json）；最终使用单调 nanoTime 计时。源时钟与本机存在数秒差异时，不把 `max(0, receivedAt-sourceAsOf)` 当纯网络延迟或毫秒 SLA；两个原始时间都提供，部署环境应同步时钟。Binance 未过滤现货目录约 17.68MB，曾触及响应上限；最终改为按 symbol 过滤、showPermissionSets=false，真实重测成功，仍保留大小限制。

### 官方协议与许可核验

深度通道依照当前官方文档：Binance [现货公共流](https://developers.binance.com/en/docs/catalog/core-trading-spot-trading/api/ws-streams/~)和[USDT 永续公共流](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/ws-streams/public)，永续深度使用 `/public/ws`，不复制价格流的 `/market/ws`。Binance [REST 文档](https://github.com/binance/binance-spot-api-docs/blob/master/rest-api.md?plain=1)用于过滤官方品种规格。

OKX [API 文档](https://tr.okx.com/docs-v5/en/)用于 books5 快照及合约规格；[更新日志](https://www.okx.com/docs-v5/log_en/)说明端口迁移及 checksum 弃用。新增深度使用默认 443；既有价格流的 8443 配置未在本工单改动，需在其停用日（2026-10-31）前单独处理，不能因本次深度验收声称原有价格链路永久正常。

**免费访问不代表第三方展示许可。** 已读 OKX [新加坡站 API 协议](https://www.okx.com/en-sg/help/okx-api-agreement)及 [UAE 对应页](https://www.okx.com/en-ae/help/okx-api-agreement)第 9.4 节：公开免认证数据也受第三方展示/再分发限制，需要适用协议下的书面许可。该项目没有提供该许可；未发布面向用户的 OKX 数据产品。Binance [条款入口](https://www.binance.com/en/terms)当前为动态入口，本次没有从可读取的第一方条款确认本项目商业展示权；不能以匿名 API 或第三方文章宣称已有授权。部署前保持深度禁用，确认目标地区与用途适用的授权后再启用。未擅自接受合同或办理许可。

## 自动测试与构建

工具链：Windows PowerShell，Maven 3.9.9，Java 21 编译器的 release 8 配置，Spring Boot 2.7.18，Node 24.18.0。编译产物 MarketDepthService.class 的 major version 为 52（Java 8）。测试使用隔离 H2、测试管理员及本机来源夹具；不连接真实业务数据库。

| 验证 | 当前结果与覆盖 |
|---|---|
| MarketDepthTest（6） | 精确排序/累计/显示范围比例、序号字符串、完整替换/零量/空侧；非法值/溢出/未来时间；官方规格与单位/同源/缓存；租户可见性/禁用/空闲；过期、容量、Retry-After；下架复查与 DB 故障仍回收。 |
| DepthStreamTest（2） | 真实本机 WebSocket 容器覆盖 Binance/OKX 的 spot/swap 四种协议；初始化、完整快照跳号、同序异内容、倒序、重连、切换旧消息、断流过期/释放；禁用不连接。 |
| DepthPushTest（3） | price 与 depth 均可送达、深度 1 次/秒；已排队旧目标丢弃；慢客户端不无限积压、超时关闭并释放。 |
| 原链路回归（21） | MarketPushTest 1、ExchangeQuoteTest 8、ExchangeConnectionTest 3、AdminPermissionIntegrationTest 9，均通过。 |
| MarketDepthIntegrationTest | 3 个正常集成测试通过，1 个可选浏览器保持窗口默认跳过；实际 MVC/JWT/权限/tenant SQL/HTTP 错误及高精度、禁用保存持久化与双租户、真实下游 WS 切换/禁用/恢复/取消/断开回收。 |
| 浏览器保持窗口 | 单独执行成功，供真实构建管理端操作真实隔离后端；临时 token 文件已删除。 |
| 后端与后台构建 | `mvn -q -DskipTests package`、后台 `npm run build`、`npm run test:tenant` 全部 exit 0。Vite 既有大 chunk 提示仍存在，非构建失败，未引入新图表库。 |

汇总：[test-summary.json](C:/workspace/fx/705/reports/depth-20261003/test-summary.json)。相关 35 项通过，不将目录中早期其他工单的 XML 当本次证据；未宣称全仓所有测试通过。日志：regression-tests.log、integration-tests.log、backend-package.log、admin-build.log、admin-tenant-tests.log。

## 管理端浏览器 QA

**Summary**：实际查询来源健康，保存关闭、重载仍关闭、再次启用成功；只读管理员不能保存、无 settings 权限进入 forbidden。QA 通过。

**Environment**：`https://admin.mt705.test/settings` 的真实租户 Host/Origin，通过只用于验收的转发访问本机 H2 后端和真实 Vite build 预览。Browser 插件/skill 不可用（Absent，`Browser plugin not available`），使用已有 Playwright 1.62.1 和 Edge headless，没有安装依赖。320/375/390/430/1280/1440/1920 全部核对。

**Changes Verified**：Settings/MarketDepthHealth，真实 GET/PUT；持久化并非浏览器响应 mock。上游仅为确定性协议夹具，截图中的价格/目录不能作为真实交易所行情证明；真实来源证明单独见上一节。

| Checks | 结果 |
|---|---|
| 页面 URL/title、非空、有意义组件 | 通过 |
| 框架报错覆盖层 | 无 |
| Console / runtime | 无相关 console/pageError |
| 保存与重载交互 | PUT 后 GET、关闭持久化、重新启用均通过 |
| 权限 | settings:view 无保存按钮；直接 PUT 403；无菜单权限路由 forbidden |
| 触控、焦点和横滚 | 新按钮高度 44px，键盘焦点可见；窄屏折叠既有侧栏，真实点击刷新可达；主内容横滚和表格滚到最近错误列均验证 |
| 截图 | live / disabled / readonly / forbidden / 390 窄屏，已实际查看 |

**Interaction Loop**：进入系统设置，点击行情配置，读取状态，点击禁用，刷新确认 DISABLED，重载确认持久化 false，点击启用；改变七组视口并操作刷新/表格横滚；再以只读和无权限账号复验。

**Findings / fix ledger**：早期验收代理使用 Node fetch 丢失 Host，触发正确的租户边界 403，已改用 native http 保留 Host，未放宽生产校验。窄屏原管理壳将内容压成细栏，新行情 tab 保留最小内容宽度并使用既有主区域横滚；复验不再把“元素宽度足够但被祖先裁剪”当通过。旧 token/保持窗口和预览进程已清理。

**Remaining Risk**：后台仍是桌面管理壳，手机需要折叠侧栏并横滚，不是本次重做全局导航；未测试 Safari/iOS 或长期连接/生产网络。许可与 Binance 永续真实连接待补验。可安装 Browser 插件以便后续在应用内检查 DOM、日志和交互。

浏览器记录：[browser-qa.json](C:/workspace/fx/705/reports/depth-20261003/browser-qa.json)、browser-qa.mjs、browser-qa.log、browser-backend.log。截图置于本报告末尾。

## 配置、运行与复验命令

保留当前唯一供应商，禁止不知情同时采集全部来源。生产公开展示许可未确认时设置：

```powershell
$env:MARKET_DEPTH_ENABLED = 'false'
# 正式确认许可与网络后，才按现有部署流程启用；本次没有执行生产启动。
```

现有 `market.exchange.provider` 默认 binance；需要改 OKX 必须由运维显式设置 `EXCHANGE_MARKET_PROVIDER=okx` 并重启，不能靠运行时回退。深度全局 `market.depth.enabled`、15s 过期、5s REST、60s 空闲、官方 WS 地址在 application.yml；`market.depth.allow-test-sources` 默认为 false，不允许生产启用测试 loopback。

本域无新增 DDL 迁移。租户 `market.depth.enabled` 缺项默认启用，管理员开关保存到原有 system_config；全局禁用优先，后台不能绕过。无需求时不采集，不配置账户/交易 Key。必要的部署启动仍沿用仓库原部署方案，本工单不自动执行。

```powershell
Set-Location C:\workspace\fx\705\exchange-backend
mvn -q '-Dtest=MarketDepthTest,DepthStreamTest,DepthPushTest,MarketPushTest,ExchangeQuoteTest,ExchangeConnectionTest,AdminPermissionIntegrationTest' '-Dmarket.depth.enabled=false' test
mvn -q '-Dtest=MarketDepthIntegrationTest' test
mvn -q -DskipTests package
# 显式隔离、匿名真实来源复验；不是生产切换或 fallback。
mvn -q '-Dtest=DepthLiveProbeTest' '-Ddepth.liveProbe=true' '-Ddepth.liveProvider=binance' test
mvn -q '-Dtest=DepthLiveProbeTest' '-Ddepth.liveProbe=true' '-Ddepth.liveProvider=okx' test
Set-Location C:\workspace\fx\705\exchange-admin
npm run build
npm run test:tenant
```

浏览器复验需先在两个终端运行下面的隔离窗口与 build 预览，然后第三个终端执行脚本。夹具没有生产数据库或账户配置，脚本保持真实 Host/Origin，完成后删除短期测试 token 文件并停止窗口。

```powershell
# terminal 1，exchange-backend
mvn -q '-Dtest=MarketDepthIntegrationTest#browserAcceptanceWindow' '-Ddepth.browserHold=true' test
# terminal 2，exchange-admin
npm run preview -- --host 127.0.0.1 --port 5187
# terminal 3，705 根目录；已有 bundled Playwright，脚本不安装依赖。
node reports\depth-20261003\browser-qa.mjs
```

## 回滚与源码范围证明

原文件备份：`C:\workspace\fx\705\rollback\depth-20261003` 下对应相对路径的 Settings.vue、SystemConfigService.java、MarketWebSocketHandler.java、application.yml；与 HEAD 原内容逐项校验一致（忽略原有 LF/CRLF 差异）。备份目录里其他新文件的中间版本不是原项目文件，不用于恢复基线。

可执行的安全回滚预检：[rollback-depth.ps1](C:/workspace/fx/705/reports/depth-20261003/rollback-depth.ps1)。默认只预检，不执行；`-Apply` 仅在当前文件 SHA-256 与本阶段清单一致时恢复上述四个原文件，并将本域新增产品文件移入恢复备份，保留报告和原型。所有路径限制在该 workspace，先核对再操作；后续阶段若已改共享文件则拒绝整体覆盖，应采用精确反向补丁。没有 reset/checkout，没有删除真实数据或表。

```powershell
& C:\workspace\fx\705\reports\depth-20261003\rollback-depth.ps1
# 只有用户明确要求回滚时才执行：
# & C:\workspace\fx\705\reports\depth-20261003\rollback-depth.ps1 -Apply
```

配置值回滚通过授权管理员恢复以前的开关值，不删除 system_config 或业务数据。全局快速停用可设置 MARKET_DEPTH_ENABLED=false 并按已有维护流程重启；不会改变价格、交易或资金数据。

范围/hash：[scope-and-hashes.json](C:/workspace/fx/705/reports/depth-20261003/scope-and-hashes.json)，仅深度相关 backend/admin/文档。`git diff --check` 使用 cr-at-eol 临时选项兼容原 SystemConfigService 的 CRLF，未修改 Git 全局/仓库配置。`git status --porcelain -- exchange-frontend exchange-pc` 为空。完整交付审计通过的是本工单核心及明确允许的外部缺项分支，不是生产全链路上线声明。

## Screenshots

![后台健康查询（隔离协议夹具）](C:/workspace/fx/705/reports/depth-20261003/admin-depth-live.png)
![禁用后状态（真实隔离配置保存）](C:/workspace/fx/705/reports/depth-20261003/admin-depth-disabled.png)
![390 窄屏，折叠原侧栏并可横滚](C:/workspace/fx/705/reports/depth-20261003/admin-depth-390.png)
![Figma 深度可见状态](C:/workspace/fx/705/reports/depth-20261003/figma-mobile-states.png)
![Figma 最窄字段压力核验](C:/workspace/fx/705/reports/depth-20261003/figma-stress-320.png)
