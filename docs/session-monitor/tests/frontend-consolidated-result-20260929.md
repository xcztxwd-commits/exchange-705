# 前端合并补验结果（2026-09-29）

## 结论

**部分验收通过，不能关闭整仓总闸门。** 已独立复核新 i18n、执行三端完整类型检查及生产构建、当前前端单元测试与下述隔离浏览器用例。发现 F01：分享图把 `0.00000001 BTC` 显示为 `0.00 BTC`；已在独立快照最小修复并重测，尚未写回共享业务源码。后续同一快照另完成历史专项与UI缺口补验，新增F02/F03，详见下节；三份补丁均待调度整合。

历史 `chartWorkspace.browser.cjs` 首轮真实退出 1：等待精确 200 根超时，当时实际已自动补历史到 300 根。该旧套件不能算通过；未降低其断言或重写历史结果。首轮当前界面用例只验证双端 8 个周期、时区及显示。随后在同一快照完成以下历史专项补验；旧套件原始失败保留，不改为通过。

本轮不做真实后端 E2E、资金操作、部署、Maven、容器、Git 索引/提交/推送。未重试受历史平台拦截的 round2 模拟账户/客服任务。

## UI缺口补验与F03（同一快照，2026-09-29 11:37 完成）

**限定UI矩阵6/6通过，新增F03仅快照修复。** 先固定覆盖及证据范围到 `C:/workspace/fx/new/frontend-consolidated-20260929-103240/ui-gap-scope.txt`，未无限扩展视觉组合。浏览器仍由本线单独操作，未启动子代理。Browser plugin not available，使用既有Playwright与Chrome。三端独立loopback服务、API mock及外联阻断沿用前述隔离。

### 具体覆盖与证据

证据根：`C:/workspace/fx/new/frontend-consolidated-20260929-103240/browser/ui-gaps-final`。`results.json`为6组结果，`wire.json`为请求方法/参数/响应条数，`browser-events.json`为URL/title/正文/覆盖层/日志，`run.exit=0`。这6组内部包含以下操作，不能把分步骤再重复累计成独立通过数。

| 覆盖对象 | 实际UI操作与断言 | 证据 |
|---|---|---|
| PC DesktopTrade 分类搜索 | 打开实际分类下拉；全部+Forex+USStock+Metal，排除仅禁用品种Oil、禁用配置CFD和空Empty；Forex内带空格大小写代码搜索；USStock内AAPL/Apple搜索；分类与关键词取交集；目录移除当前分类后回到全部 | pc-category-options.json、pc-categories-1440.png、pc-categories-1024.png；目录移除采用Vue store状态辅助，不冒充后台配置编辑 |
| PC宽窄布局 | 1440×1000与1024×1000；真实下拉打开/关闭，菜单边界位于视口内，搜索框可见；1024下内容改为纵向排列 | 上述两张截图及脚本几何断言；没有认证任意宽度或全局箭头 |
| 手机实际入口 | 390×1000，从/home品种USDJPY点击进入trade；返回首页点击搜索，输入EUR并点击EURUSD结果；返回首页点击底栏Trade | mobile-home-entry.png、mobile-search-entry.png、mobile-tabbar-entry.png；三个入口均默认contract，前两项还核对目标symbol及URL |
| 后台Orders合约分页 | 页面组件壳加载23行；默认10，点击第3页得到3且Next禁用；末页选择代理后最终回第1页3行；重置；用户ID搜索回第1页；切20后末页3；CANCELLED筛选空表并回第1页；重置恢复20行 | admin-contract-last.png、admin-contract-agent.png、admin-contract-size20.png、admin-contract-empty-filter.png、contract-agent-state.json |
| 后台Orders期货分页 | 与合约相同的独立操作，分别核对option查询参数与显示行数 | admin-option-*.png、option-agent-state.json |
| 合约与期货软删除/恢复 | 各用两条已平仓mock订单7/8；PC和手机用户历史初始显示二条；后台先取消删除（无变更），再确认删除7，后台灰色已删除行及恢复按钮；用户重新加载后只剩8；后台筛选已删除并恢复7，筛选列表清空；重置后恢复普通行；双端用户重载重新显示7/8 | admin-{contract,option}-{deleted,restored}.png；{pc,mobile}-{contract,option}-{hidden,restored}.png；wire记录各一次DELETE及一次POST restore，共4次mock变更 |

代理筛选在旧末页会先请求page=2，得到total=3/list=[]，Element Plus依据新total校正后再请求page=0。最终状态正确，双标签均通过；本次不将这一次额外请求误报为分页产品缺陷，也未修改后台代码。

**软删除边界：** mock服务维护deleted标记，并在用户列表响应中主动过滤；前端根据重新获取的列表隐藏/恢复卡片。此测试证明页面在指定响应下更新、后台按钮和端点选择正确，不能证明真实服务正确过滤、权限有效、资金不变或跨账户隔离。用户列表没有在本次新增客户端deleted防线。后台组件壳安装了实际permission指令，但使用合成菜单授权，因此仍不是实际权限验收。未调用真实删除、恢复或资金接口。

### F03：PC品种筛选未处理禁用/空目录及名称关键词

位置：独立快照 `C:/workspace/fx/new/frontend-consolidated-20260929-103240/source/exchange-pc/src/views/DesktopTrade.vue`，涉及categoryOptions、初始选中品种、symbols/filteredSymbols和分类回退watch。

- 修复前：配置enabled=false的Oil仍出现在下拉，isEnabled=false的DISABLED仍出现在列表；浏览器实际5个选项，预期4。证据 `browser/ui-gaps-before/pc-category-before-check.png`、pc-category-options.json、run.exit=1。这是前端面对含禁用条目的目录响应时未过滤，不声称真实服务器一定返回这些条目。
- 同一筛选逻辑还未trim关键词、只匹配symbol/displaySymbol，不支持nameEn/nameCn；失效的当前分类不会自动复位。新增直接提取真实computed/watch的回归验证这些情况；原源码3/3失败，修复后3/3通过，见categories-unit-before.log与categories-unit-after-final.log。
- 最小修复：可用symbols排除禁用品种及禁用分类；分类选项只保留启用且有可用品种的配置；当前分类移除后复位；关键词trim并匹配代码、展示名、基础/报价币和中英文名称；首次选中只取可用symbols。未改交易请求、数量、资金、服务端权限或目录配置接口。
- 新增 `C:/workspace/fx/new/frontend-consolidated-20260929-103240/source/exchange-pc/tests/instrumentCategories.test.mjs`，覆盖独立禁用品种/禁用分类/空分类、关键词与分类交集、目录移除/禁用后的回退。
- 独立交付补丁：`C:/workspace/fx/new/frontend-consolidated-20260929-103240/pc-instrument-category-filter.patch`。仅DesktopTrade.vue和新增回归；SHA-256 `AB449BFF8F5490F2A5EC3214FA4B5BC34BBB36B5D810B46E1E3B24C092B70DA0`。
- DesktopTrade.vue原指纹 `39FA87F1AFF4BAB94F6D2A95F0C292C6CD7712692FD6AB52CBA947226DD2E098`，新指纹 `DC1D46B1C548078B4A283504429058F89AF124EDBFBC1291E9E8AE2F6E722798`。备份 `C:/workspace/fx/new/frontend-consolidated-20260929-103240/ui-gap-backup/exchange-pc/src/views/DesktopTrade.vue`。
- 所有权交调度串行整合。核对原指纹后应用；若共享文件已变，逐块合并，不整体覆盖。回滚仅反向撤销F03变更及新增测试，保留其他人的后续改动。F01/F02补丁文件均保持原指纹，见ui-gap-delivery.json。

### 回归、证据质量及清理

- `QA_ROOT=C:/workspace/fx/new/frontend-consolidated-20260929-103240 node run-ui-gap-checks.cjs`：PC77/77、移动10/10、后台10/10；三端vue-tsc -b及vite build全部退出0，见F03-checks.json与F03-*-unit/build/bundle.log。构建警告保留，不为消警告改变产品。
- 浏览器复现：证据根cwd，分别启动serve.cjs的三个app；设置QA_EVIDENCE为独立输出目录，运行 `node --require ./guard.cjs ./ui-gaps.cjs`。脚本包含完整mock和断言，无真实账户信息。
- 最终6/6、10个页面身份记录均非空、0个pageerror、0个框架覆盖层，见ui-gap-summary.json。28条外部资源阻断对应28条预期ERR_FAILED；首页批量K线占位mock产生6条“Unexpected batch kline response format”警告，未测试首页迷你走势，不称零控制台错误。后台permission指令本次已注册，无缺失指令警告。
- 已目视检查PC1024布局、后台删除状态和手机隐藏/恢复截图。ui-gaps-six早期后台截图捕获了确认框退出动画；最终shot等待message-box隐藏后重新截图，旧截图不作为最终视觉证据。显示代码EUR/USD的断言按现有displaySymbol纠正，未放松禁用目录或行数断言。汇总脚本曾遇到Windows默认GBK解码，使用Python -X utf8后同样断言通过；不属于产品故障。
- 597个共享基线源文件漂移0，见F03-drift.json；最终快照F03-post-manifest.json，补丁指纹ui-gap-delivery.json。共享仓仅更新本报告，不写业务源码；没有Maven、业务库、部署、Git索引/提交操作，也没有重试round2。
- 三个自有服务已关闭，18471/18472/18473监听均0，见ui-gap-cleanup.json（11:37）。未关闭其他服务。
- 此次仅关闭指定UI缺口的有限mock覆盖。其他后台列表、所有分辨率、真实目录策略、真实鉴权/账户/资金和后端联测仍未认证，总闸门不关闭。

## 历史图表专项补验（同一快照，2026-09-29 11:09 完成）

**专项 mock 验收通过，真实后端总闸门不变。** 新发现 F02：请求错误本地化丢失 HTTP 状态，导致图表无法识别 404/405 并进入历史回退。仅在独立快照修复双端 request.ts；未写共享业务源码。

### 200 与 300：契约及确定性复现

首批 latest 请求固定 200，但图表的可见数据总数不固定 200。KlineChart 的 requestedBarCount 根据可视缺口和 barSpace 计算历史页，向 100 的倍数取整、单页上限 200；更宽视口会立即自动补历史。原旧夹具在允许历史并发时等待“恰好200”，受布局及时序影响。

在修正独立 Vite cwd 后，另用 2100px 视口、实际图表宽1392、barSpace=7复现：先阻塞历史响应，严格断言200；释放后 latest200 + history100，严格断言300；随后真实拖动到500。首批最早时间1790580300000，历史请求endTime1790580299999，历史100根从1790550300000至1790580000000。证据 history-2100 的 wire.json、results.json、run.exit=0。原始旧失败没有逐请求轨迹，因此这是当前契约的独立复现，不声称还原了旧运行的全部请求顺序。

2600px试验实际补200而非预设100，保留为 history-wide 失败证据；它说明视口影响分页量，不是缺失蜡烛。未把断言放宽为“大于等于200”；最终夹具仍严格检查初始200、已返回且严格早于边界的数据并集，以及每根timestamp/OHLCV。

### F02 根因、最小修复及回滚

- 根因：双端 request.ts 的通用 HTTP 错误分支创建新的 Error，只保留本地化 message，丢弃 response.status；KlineChart.fetchHistory 的404/405检测始终不命中。既有chartWindow测试直接模拟原始错误，未覆盖真实拦截器。
- 修复前浏览器 pc-fallback/mobile-fallback：latest200，history404反复重试，未发 expanded latest，数据停200，错误状态不清。原始证据 browser/history-before/run.exit=1 与 wire.json。
- 修复：每端仅替换两个 return，保留既有文案，同时附加 status 和 response.status。只复制数值状态，不复制响应正文、请求头、配置或token。无响应网络错误仍无status，写请求结果不确定提示保留；KYC403、401和注册专用分支未改。
- 新增 exchange-pc/tests/requestStatus.test.mjs，直接转译并加载双端真实拦截器：404/405/503、有/无错误文案、网络写入不确定、KYC专用错误。修复前12失败4通过，修复后16/16通过。启动夹具的import.meta语法错误另存request-status-fixture-syntax.log，不计产品缺陷。
- 独立补丁：`C:/workspace/fx/new/frontend-consolidated-20260929-103240/chart-history-http-status.patch`，仅两份request.ts和一份新增测试。SHA-256 `6FFDEB09B37E08D5FE85BAFDBEC79A38346FDA235F95D21F0610F16D5D6C9E6E`。
- PC request.ts：原 `157975C9E302AF61DD088336E5B4C4D046248E3EF1C6ACB7DBFCAA750F89A7B8`；新 `A8D186FD54B2A086B0D763FD5750FBBDBD3900D84CEDFBD88729B6E43D899CF8`。
- 移动 request.ts：原 `5F04B7E77DD74543FB48A0BB48E7D640446BF65F461BDE41BC27B234C16A3F08`；新 `D10B4ED7ABD074FB928967D15ACBE509E0142B4A346AAF0941DE01D324BC89D7`。
- 备份在证据根 history-backup/{exchange-pc,exchange-frontend}/src/utils/request.ts。交由调度串行整合；先核对原指纹，有漂移则合并两个返回行，不整文件覆盖。回滚只撤销本补丁两个返回行及新增测试，保留其他人的后续改动。F01数量补丁保持独立、未重写。

### 最终历史矩阵：10/10

固定1600根连续5分钟合成数据，末时刻2026-09-29T00:00:00Z。每场新页面、独立mock响应与随机测试标识。初始历史响应闸门保证严格200；通过图表API设置barSpace触发自动补页（不是物理滚轮测试），之后PC真实鼠标拖动、移动真实CDP触摸拖动。每个历史请求严格检查endTime等于当时最旧时间戳减1ms；最终逐根比较独立响应参考并集、OHLCV、排序、连续间隔、唯一性。

| 场景 | PC 自动/最终 | 移动 自动/最终 | 实际断言 |
|---|---:|---:|---|
| 正常历史 | 600/1000 | 400/1600 | 自动补页、拖动、游标、全量逐根对照 |
| HTTP404回退 | 600/1000 | 400/1000 | expanded latest按400/600/800/1000扩展，上限1000，limited=true |
| 首次HTTP503 | 600/1000 | 400/1600 | 原数据保留，错误可见，无点击自动重试，同游标与页量，等待至少2800ms |
| 首次网络connectionreset | 600/1000 | 400/1600 | 原数据保留，无刷新自动恢复，同游标与页量 |
| 重复/乱序/越界响应 | 600/1000 | 400/1600 | 反序、重复、endTime+1伪造999价格均不污染最终OHLCV |

移动触摸惯性可继续分页直到合成源1600根耗尽，故不假定只新增一页。稳定性等待要求连续6次读数相同且不loading；不减少或忽略已交付数据断言。早期无界数据源造成移动惯性读数竞态、barSpace低于库允许下限造成缩放失败，均保留原日志，并在有限确定数据和正确缩放范围下重测；不是产品缺陷，也不是吞异常。

browser/history-final 的results.json、wire.json、run.log、run.exit=0、browser-events.json及10张场景截图保存完整证据。最终均无pageerror或Vite覆盖层。外部资源依然被主动拦截；不能称零console错误。已目视检查PC和移动fallback截图：PC显示1000根及history limit提示，移动截图为拖动后的图表；移动limited由组件状态断言，不把截图中未显示的提示声称为可见。

### 回归、复现、证据与边界

以下路径均以 `C:/workspace/fx/new/frontend-consolidated-20260929-103240` 为证据根：

- `node run-history-checks.cjs`：PC74/74（含新增16），移动10/10；双端vue-tsc -b与vite build全部退出0，见F02-checks.json与F02-*-unit/build/bundle.log。后台业务未改，此次不重复后台构建；首轮后台通过仍保留。
- `node --test source/exchange-pc/tests/requestStatus.test.mjs`：request-status-before.log退出1，request-status-after.log退出0。
- 先分别启动 `node serve.cjs exchange-pc`、`node serve.cjs exchange-frontend`。设置QA_EVIDENCE为独立目录，执行 `node --require ./guard.cjs ./history-final.cjs`（10场）或 `node --require ./guard.cjs ./history-2100.cjs`（200/300定量复现）。各脚本及日志均在证据根，运行cwd为证据根。
- F02后重跑current-ui.cjs，browser/F02-auth-regression/run.exit=0：双端8周期、时区/DST、移动路由、PC登录/注册回车与IME/repeat/慢响应防重均通过。认证仍为mock，不是账户E2E。
- 保留history-recheck、history-before、history-after、history-wide、history-2100、history-final全部历史证据，未将失败日志覆写为成功。
- 本次最终597个共享基线文件漂移0，见F02-drift.json；快照指纹F02-post-manifest.json、交付指纹history-delivery.json。只对597文件作此声明。
- 自有PC/移动服务已关闭，18471/18472/18473监听0，见history-cleanup.json。没有触碰其他服务、数据库、Maven、部署、Git索引或提交。
- 本节关闭的是确定性mock历史专项缺口；不关闭真实供应商周/月边界、partial/pending/revision全组合、多标签并发、真实账户/资金/权限和总闸门。旧chartWorkspace的其他指标/截图用例未新认证。

## 版本、隔离、复现

- 证据根：`C:/workspace/fx/new/frontend-consolidated-20260929-103240`。独立源码：`C:/workspace/fx/new/frontend-consolidated-20260929-103240/source`。
- 读取了任务给出的 AGENTS 指令、ponytail full、caveman full、前端测试技能，以及 session-audit、consolidated-recheck、global-i18n-recheck、MT02 独立报告、分享图重设计、资金历史沿用说明和数量改造说明。项目物理 `AGENTS.md` 不存在，未假称读到。
- 597 个原始文件逐个复制并核对 SHA-256；初始 564 个前端文件及检查器，随后补齐权限静态检查实际依赖的 JSON、PermissionCatalog 和 Controller 源文件。补拷不含配置或业务数据，未编译后端。
- `C:/workspace/fx/new/frontend-consolidated-20260929-103240/source-manifest.json` 为基线，`C:/workspace/fx/new/frontend-consolidated-20260929-103240/post-manifest.json` 为最终快照，`C:/workspace/fx/new/frontend-consolidated-20260929-103240/drift.json` 为结束复核。共享源 597 文件漂移 **0**。只覆盖清单，不宣称冻结整个后端或整个仓库。
- 白名单复制 src/public/tests、package/lock、tsconfig、vite、index、PostCSS/Tailwind 配置；排除 `.env*`、凭据/密钥文件、SQL、uploads、reports、dist、target、.git、旧构建缓存。未复制项目根密码文件。
- 现有 node_modules 仅通过 junction 使用，不安装或更新依赖。六个快照 tsconfig 的 tsBuildInfoFile 改到快照 `.qa-cache`，有 `.original` 备份；Vite 缓存独立，避免写入共享 node_modules 缓存。这些环境调整不进入业务补丁。
- Node `v24.18.0`；使用已安装 Playwright 与 headless Chrome。Browser plugin/专属 browser skill 未提供（`Browser plugin not available`），按测试技能使用常规 Playwright；未安装浏览器或关闭浏览器安全检查。
- 三个独立 Vite 进程：PC `http://127.0.0.1:18471`、移动 `http://127.0.0.1:18472`、后台 `http://127.0.0.1:18473`。`envDir:false`，API 基址固定 `/api`，不配置到现有后端的代理；未被浏览器 mock 的 API/上传由本轮 middleware 返回 503，绝不转发到共享服务。
- 创建 BrowserContext 后、导航前拦截请求：仅允许上述三个 loopback 端口，其余 abort；service worker 禁止，WebSocket 由夹具处理，不连接业务服务。外部 IP 查询及主机安全软件注入资源被拦截，记录在每个 `browser-events.json`。这不是平台操作拦截，也不是全机网络审计。
- 新认证用例使用运行时随机标识/密码；没有后端 JWT 签名密钥。未读取真实凭据。旧浏览器夹具只使用虚构 token；最终分类复测改为随机测试标识。所有登录、注册和订单响应均来自 mock。
- 测试结束关闭三个自有服务，端口监听剩余 0：`C:/workspace/fx/new/frontend-consolidated-20260929-103240/cleanup.json`。Ctrl-C 导致服务 session 退出 1 是主动清理，不是构建或测试失败。

## F01：小额币数量在分享图被四舍五入为零

位置：三端 `src/utils/orderShare.ts:319`。入口分别为 PC/移动 `OrderShareModal.vue` 和后台 `ShareTemplatePreview.vue`，三个渲染器原本逐字节一致。

1. 使用已平仓合成订单，quantity=0.00000001、quantityUnitType=BASE_ASSET、quantityAsset=BTC。
2. 选择 light 模板、同时显示盈亏和收益率、启用数量。
3. 预期 `Quantity  0.00000001 BTC`；修复前实测 `Quantity  0.00 BTC`，断言退出 1。原始证据：`C:/workspace/fx/new/frontend-consolidated-20260929-103240/share-precision-before.log`。
4. 根因：数量调用 `shareNumber` 默认仅保留两位小数；不是订单量为零，也不是后端结算错误。
5. 最小修复：三端各替换一行；非 lots 单位复用同函数已有的最大 16 位小数 formatter，旧 lots 继续两位。未改资金、手续费、收益率、订单数量或 API。
6. 新增可运行回归 `exchange-frontend/tests/shareQuantityPrecision.test.mjs`：极小 BTC、8 位小数 BTC、股、旧 lots。修复前失败，修复后与既有分享完整测试一起通过；三端浏览器 Canvas 再测 16 模板 × 正/负/零 × 币/股/手，共 **432 次本轮渲染**，其中 297 次数量文本断言；需要图表的模板按现有设计不展示数量，未虚称每个模板都显示数量。
7. `C:/workspace/fx/new/frontend-consolidated-20260929-103240/browser/components/quantity-18472.png` 已人工查看，显示 `0.00000001 BTC`。

交付补丁：`C:/workspace/fx/new/frontend-consolidated-20260929-103240/share-quantity-precision.patch`，仅三个渲染文件和一份新增回归测试。文件所有权交调度整合；本会话未覆盖共享业务源。

三个渲染文件均为：
- 修改前 SHA-256：`DA085469B7A1E6700AECD73490C859668E4AB2BEC87C9C0800438738B644D7D2`
- 修改后 SHA-256：`E3535045833B8A91A07C66C96376BD6716C7AC37191B94DCF138FA82CC067D2C`

备份：`C:/workspace/fx/new/frontend-consolidated-20260929-103240/backup/{exchange-admin,exchange-pc,exchange-frontend}/src/utils/orderShare.ts`。整合前核对共享文件仍等于修改前指纹；若有漂移，手工合并一行，禁止整文件覆盖。回退仅反向撤销本次一行及新增测试，先核对后续改动。数值仍沿用既有 number 模型，不能宣称任意 16 位十进制金融数字无损。

## 需求到源码和实测映射

以下 UI 均为 **mock API/合成数据**。组件壳与完整应用分别标注；“通过”只限该行断言。

| 范围与源码 | 步骤和预期 | 本轮实测 | 状态/证据 |
|---|---|---|---|
| 双端 store/uiMessages.ts、locale.ts | 重跑全局及新增数量/周期日语检查 | 19 语言；移动 18905、PC 18924 字典查询；各 424 日语 UI 项；聚焦 3 项通过 | 通过，i18n-final.log、focused-final.log |
| 三端 package.json/tsconfig/vite | 每端先 vue-tsc -b，再 vite build，不跳过类型检查 | 三端两个阶段均退出 0 | 通过，checks.json 和各 build/bundle.log |
| PC DesktopTrade.vue、双端 KlineChart.vue/chartData.ts | 完整页面逐点 1m/5m/15m/30m/1h/1d/1W/1MO，断言实际 interval、非空及时间戳去重 | 双端 16 次周期切换通过；合成 OHLC，不认证真实周/月行情聚合 | 通过，browser/current-ui-final/results.json |
| 双端 KlineChart.vue、visitorRegion.ts | PC 直接、移动先全屏，打开时区选择 Tokyo；测试纽约 DST 跳时及下午显示 | 图表 timezone 为 Asia/Tokyo；EST 1:59:00 am、EDT 3:01:00 am、EDT 1:00:00 pm 符合当前格式 | 通过，同上；IP 默认/回退另由现有 visitorRegion 单测覆盖，未访问真实 IP 服务 |
| KlineChart 历史专项矩阵 | 独立确定性分页、拖动、自动补页、404回退、503及网络恢复、边界去重 | 双端10/10通过；初始精确200，最终逐根OHLCV对照；发现并修复F02 | 见下方专项补验；旧chartWorkspace仍记失败，不认证其未运行的其他测试 |
| 移动 Trade.vue 分类搜索 | 打开品种选择，切美股、组合 EUR/apple 查询，删除当前分类，320px 检查横向滚动与弹窗不溢出，选中品种 | 分类/搜索/隐藏禁用品种/分类回退/选中均通过 | 通过，browser/categories-final/run.log；测试注入 Vue catalog，不是目录 API 整合测试 |
| PC 分类组合、全局箭头 | 同一快照追加分类与代码/名称搜索、禁用/空分类、1440/1024px | F03修复后通过限定组合；全局箭头未全验 | 见UI缺口补验，不能全局视觉验收 |
| 移动 Trade.vue/入口 | 直接 /trade、/trade?tab=term，再返回 /trade | contract、term、contract 符合预期 | 直接路由通过；后续另完成首页品种、搜索结果、底栏三个真实点击入口，见UI缺口补验 |
| AssetPixelChart.vue/assetPixelWindow.ts | 组件壳，真实 CDP touchStart 长按、touchMove、touchEnd；切 1D | 时间由 09/28 08:00 跟随到 10:00，金额 100 到 110；松手清空选中；请求 range=1D | 通过，browser/components/results.json、asset-longpress.png；零/空/跨月等另属现有单测，不冒充本触控场景 |
| 后台分页 | 运行 paginationDefaults.test.mjs | 所有被扫描分页默认 10、可选 page-size 断言通过 | 静态通过；后续Orders组件壳双标签末页/筛选通过，真实后台及权限组合仍未验证 |
| MarketMinutePicker.vue | 组件壳，合成乱序及重复日期；选择日期/小时/分钟后确认；切前月；空日历重载 | 9月28/17/03倒序去重；确认原始 local 和 +09:00；8月同序；空日历禁止确认 | 通过，browser/calendar/results.json；前月切换用组件状态辅助，不算真实月份控件全交互 |
| 三端 orderShare.ts | 16模板×正负零×币股手，Canvas 文本与 PNG 编码断言 | 432渲染、297数量断言通过；BTC微量不再为零 | 通过，browser/components/results.json |
| 双端 OrderShareModal.vue | 实际组件壳，按语言加载模板、切换语言、逐个16模板、PNG下载；19语言×16模板×2重心 | 双端各608次 Canvas 导出，本轮1216；没有页面异常 | 通过，browser/shares-final/run.log；不是后端订单API验证 |
| ShareTemplateSettings.vue | 后台组件壳，旧配置加载、切重心预览、保存/重载、语言范围拦截 | mock保存及预览断言通过 | 通过，browser/admin-share/run.log；组件壳未装真实 permission 指令，因此不验证权限 |
| DesktopTrade.vue 登录/注册回车 | 完整PC页，空输入/密码不一致拒绝；合成IME/229/repeat事件取消；正常回车；慢响应期间重复 requestSubmit | 登录请求1次、注册1次；模拟400错误显示且不重复提交 | 通过，browser/current-ui-final/results.json；不是真实账户认证，也不是操作系统输入法自动化 |
| ManualContractOrder.vue 多目标 | 后台组件壳，纯价格目标、混合目标、最新分钟、7日无解错误、调整杠杆保留原目标 | 展示与按钮状态通过；未调用创建订单 | 通过，browser/admin-manual/run.log；真实生成/创建/摘要不在本轮 |
| 软删除/历史提示/权限 | 现有 orderSoftDelete、manualOrderBadge、permissionCoverage 单测 | UI处理函数/源码覆盖通过；310控件、24基础菜单、107基础动作、127后端注解静态核对 | 单测通过；后续mock删除/隐藏/恢复页面链路通过；未证明数据库不变量或真实API鉴权/过滤 |

## 命令、退出码和日志

工作目录均在 `C:/workspace/fx/new/frontend-consolidated-20260929-103240/source` 或各 app 子目录；脚本没有写共享业务源。

| 命令/阶段 | 最终退出码 | 绝对日志 |
|---|---:|---|
| node scripts/check-i18n.cjs | 0 | C:/workspace/fx/new/frontend-consolidated-20260929-103240/i18n-final.log |
| node --test scripts/check-trade-i18n.cjs exchange-frontend/src/utils/*.test.mjs | 0，5/5 | C:/workspace/fx/new/frontend-consolidated-20260929-103240/focused-final.log |
| node --test tests/*.test.mjs（admin） | 0，10/10 | C:/workspace/fx/new/frontend-consolidated-20260929-103240/exchange-admin-unit.log |
| 同上（PC） | 0，58/58 | C:/workspace/fx/new/frontend-consolidated-20260929-103240/exchange-pc-unit.log |
| 同上（mobile） | 0，10/10 | C:/workspace/fx/new/frontend-consolidated-20260929-103240/exchange-frontend-unit.log |
| node node_modules/vue-tsc/bin/vue-tsc.js -b（三端分别） | 全部0 | C:/workspace/fx/new/frontend-consolidated-20260929-103240/exchange-admin-build.log；C:/workspace/fx/new/frontend-consolidated-20260929-103240/exchange-pc-build.log；C:/workspace/fx/new/frontend-consolidated-20260929-103240/exchange-frontend-build.log |
| node node_modules/vite/bin/vite.js build（三端分别） | 全部0 | C:/workspace/fx/new/frontend-consolidated-20260929-103240/exchange-admin-bundle.log；C:/workspace/fx/new/frontend-consolidated-20260929-103240/exchange-pc-bundle.log；C:/workspace/fx/new/frontend-consolidated-20260929-103240/exchange-frontend-bundle.log |
| 新数量反例，修复前/后 | 1 / 0 | C:/workspace/fx/new/frontend-consolidated-20260929-103240/share-precision-before.log；C:/workspace/fx/new/frontend-consolidated-20260929-103240/share-precision-after.log |
| 新完整页面矩阵 current-ui.cjs | 0 | C:/workspace/fx/new/frontend-consolidated-20260929-103240/browser/current-ui-final/run.log、run.exit、results.json |
| 真实Canvas/触控组件矩阵 components-ui.cjs | 0 | C:/workspace/fx/new/frontend-consolidated-20260929-103240/browser/components/run.log、run.exit、results.json |
| calendar-ui.cjs | 0 | C:/workspace/fx/new/frontend-consolidated-20260929-103240/browser/calendar/run.log、run.exit、results.json |
| 分类/分享最终重跑 | 各0 | C:/workspace/fx/new/frontend-consolidated-20260929-103240/browser-final-runs.json；C:/workspace/fx/new/frontend-consolidated-20260929-103240/browser/categories-final/run.log；C:/workspace/fx/new/frontend-consolidated-20260929-103240/browser/shares-final/run.log |
| 后台手工单/分享设置 | 各0 | C:/workspace/fx/new/frontend-consolidated-20260929-103240/admin-browser-runs.json；C:/workspace/fx/new/frontend-consolidated-20260929-103240/browser/admin-manual/run.log；C:/workspace/fx/new/frontend-consolidated-20260929-103240/browser/admin-share/run.log |
| 原 chartWorkspace 浏览器套件 | 1 | C:/workspace/fx/new/frontend-consolidated-20260929-103240/browser/charts/run.log |

`run-checks.cjs` 是单元测试和三端构建的复现入口；`serve.cjs exchange-pc`、`serve.cjs exchange-frontend`、`serve.cjs exchange-admin` 必须各在独立进程运行。浏览器脚本均用 `node --require C:/workspace/fx/new/frontend-consolidated-20260929-103240/guard.cjs SCRIPT`，并设置 QA_EVIDENCE 指向对应独立证据目录。`fixture-manifest.json` 记录脚本/补丁/结果指纹。不同命令可能覆盖同一功能，不能把这些计数与历史成功相加。

## 失败、警告与证据质量

- 初始 admin 单测失败两次都是快照漏拷静态检查依赖（admin-permissions.json、Controller目录）；补齐并保留指纹后原断言完整通过。原日志保留为 `exchange-admin-unit.log.initial`、`exchange-admin-unit-rerun.log`。
- 初始三个 Vite 共进程使用了共享 cwd，造成 PC Tailwind 内容扫描目录错误。本轮夹具改为各 app 独立进程，未改业务 CSS，重新截图确认 PC 样式正常。旧图不作为最终视觉证明。
- 新夹具调试失败均有保留：语法漏括号、英文 Done 实际翻译为 Completed、AM/PM 实際小写、移动时区需先全屏、未知后端错误被规范化、资产1小时窗口两点同属一小时、Element Plus占位覆盖/弹出动画、组件不直接导入element-plus。修复的是夹具入口/正确期望/等待条件；没有吞掉业务异常、降低 BTC 数量断言或把旧历史套件改绿。
- 最终浏览器记录均无 pageerror，已记录页面URL/title、非空正文和无Vite覆盖层。不是“零控制台错误”：拒绝外部资源产生ERR_FAILED；登录/注册及手工单负例产生预期400；后台两个独立组件壳未注册 permission 指令，出现Vue警告。它们明确不能验证权限。
- 三端构建保留包体过大警告；移动开发服务提示变量SCSS为空；未为消警告改配置或业务代码。
- 已人工查看最终PC、移动、BTC分享图及触控截图。早期移动截图只截到启动过渡动画；最终用 `.forex-transition` 隐藏条件重拍，旧图不作为页面验收。

## 尚未验证/总闸门

1. 真实后端API、真实行情、登录认证、订单创建/平仓/软删除资金不变量、账户隔离，以及后端线修复后的联合E2E均待联测。本轮mock成功不能替代。
2. 历史专项 mock 矩阵已独立闭环，见下节；旧套件仍保留失败，其其他指标、截图等后续测试不据此认证。周/月真实时间边界、供应商历史完整性、partial/pending与revision全组合、多标签并发仍待联测。
3. PC分类搜索/禁用目录与1440/1024px布局、手机首页/搜索/底栏三个实际入口、后台Orders双标签分页/筛选、软删除与恢复的mock页面链路已补验。其他后台列表、真实服务分页/删除过滤、全局箭头全部尺寸、三端全部语言人工质量仍未全验；不把有限组合推广为全局通过。
4. 静态i18n扫描仅覆盖现有单引号 text 调用等脚本规则，不覆盖所有动态/模板字符串，也不是19语言人工审定。股份单位在英语分享图沿用现有“股”，本轮只校验类型与数值，不认证这一词条的语言质量。
5. 贷款合同/期限产品规则冲突未擅自决定。模拟账户503、round2及跨账户资金隔离仍为外部验收门槛，未被本轮通过项移除。
6. F01、F02与F03补丁须由调度核对指纹、串行整合并再次运行全仓联合回归；未整合、未提交、未推送、未部署。
