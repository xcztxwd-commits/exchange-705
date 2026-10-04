# 真实市场深度：设计与唯一接口合同

设计日期：2026-10-03（Asia/Singapore）。基线：main / 6b93d62。

## 范围与决策

只修改 exchange-backend、exchange-admin；手机、PC 仅补充 Figma 设计及本合同，不接入用户端代码。现行配置 `market.exchange.provider=${EXCHANGE_MARKET_PROVIDER:binance}`；当前未发现覆盖，默认 Binance。沿用同一供应商，绝不跨供应商回退，不需要账户/交易 Key。不涉及交易、控价、资金或生产部署。

第一版采用**有限档完整快照替换**，不构建全量或增量订单簿：Binance 现货 `depth20` 每秒、USDT 永续 `depth20@500ms`；OKX 公共 `books5`（实际最多 5 档）。只在可见订阅/近期 REST 访问期间集中同步，按实际档数展示；断连时同源 REST 每 5 秒降级。外部参考深度独立于平台报价，禁止平移图形与平台控价对齐。

Binance 现货快照没有源时间字段，`sourceAsOf=null`、`sourceTimeBasis=NOT_PROVIDED`，只显示真实接收时间；不得将收到数据的时间伪装成订单簿生成时间。其他通道使用真实 `T/E/ts`。价格、数量、累计量、价差、比例均为十进制字符串，Java BigDecimal 计算，sequence 亦为字符串，避免 JS 精度丢失。

## 公共 REST

`GET /api/market/depth/{symbol}?levels=20&marketType=spot|swap`。levels 为 1–20；marketType 可省略，按该租户启用品种的 sourceCategory 推导；perpetual 是 swap 的别名。内部 `_PERP` 别名沿用品种表的 alltickSymbol；显式 marketType 与目录不一致返回 UNSUPPORTED，禁止将现货偷偷换成永续。目录不存在/停用品种返回 404、UNSUPPORTED；真实供应商目录再次确认上市状态。无真实品种的外汇、指数等不触发上游请求。

项目行情接口采用直接 JSON；本接口沿用该做法。正常状态 HTTP 200，参数错误 400，未知品种 404，容量限制 429。HTTP 成功不等于数据 LIVE。

字段：`instrumentId`（租户品种 ID）、`symbol`（内部代码）、`externalSymbol`、`provider`、`marketType`、`quantityUnit`（BASE_ASSET 或 CONTRACT）、`quantityCurrency`、`quoteCurrency`、`contractValue/contractMultiplier/contractValueCurrency`（仅官方规格，不猜面值）、`requestedLevels`、`availableLevels`、`displayedLevels`（买卖实际档数分别记录）、`bids/asks`（price、quantity、cumulativeQuantity）、`spread`、`bidQuantity/askQuantity`、`bidRatio/askRatio`（仅所显示范围）、`sequence`、`sourceAsOf`、`receivedAt`、`sourceTimeBasis`、`status`、`enabled`、`refreshMethod`（WS_SNAPSHOT / REST_POLL / NONE）、`refreshIntervalMs`、`externalReference=true`、`reason`、`cacheHit`。

状态：LIVE 是尚未过期的真实快照，必须同时显示刷新方式；REST_POLL 文案为“周期刷新”，不称毫秒实时。SYNCING 为等待目录/初始快照/重连；STALE 保留最后真实数据及旧时间并标过期；UNSUPPORTED 无虚构盘口；ERROR 为无成功数据时的来源故障。禁用额外状态 DISABLED，无盘口、停止此租户采集需求与推送数据。零量价档在快照替换时剔除，不补档。没有买/卖侧时价差或比例为 null，不填零。

## 深度 WebSocket

复用 `/api/ws/market` 的租户主机、Origin 和存活检查，保留原 price 协议不变。独立请求：`{"action":"subscribeDepth","symbol":"BTCUSDT","marketType":"spot","levels":20}`；每连接只保留当前深度目标；新订阅替换旧目标。`{"action":"unsubscribeDepth"}` 立即释放。独立消息 `{"type":"depth","data":上述合同,"serverTime":毫秒}`，最多每秒一次。切品种/断开/租户禁用时释放；重连须重新订阅。发送前再次核对目标版本，旧目标的已排队帧不得发送。

共享采集最大 32 个外部品种、512 个活跃引用；每租户 REST 活跃请求最多 16 个，单连接 1 个深度品种。REST 活跃 60 秒自动回收，WS 断开立即回收最后引用。有界工作池、失败退避及 Retry-After，慢客户端只保留当前发送，不积压盘口历史。深度 REST 工作池为 2 线程、32 个排队任务；复用市场推送的 4 线程、128 个排队任务，单客户端至多一个在途发送，超时关闭并回收。

官方品种规格缓存最多 64 项、有效 1 小时；活跃 WS 品种每小时重新验证上市状态，已下架则清空旧盘口并返回 UNSUPPORTED。现货 Binance 与 OKX 目录按当前外部品种过滤，响应上限 1 MiB；Binance 永续目录上限 8 MiB，盘口 HTTP/WS 上限 64 KiB。配置读取故障时仍回收过期引用，已有快照只能自然过期，不能伪造更新。

快照序号允许跳号（每帧完整，不是漏增量）；倒序/同序异内容拒收并重连重同步。OKX 完整快照的维护序号重置按新源时间接受。连接代数及外部品种匹配阻止旧消息污染。无更新/断流超过 15 秒标 STALE，重连恢复须收到新的完整快照。没有增量数据，无需旧版 CRC 拼接；当前 OKX checksum 弃用和端口变更以官方文档为准。

## 后台

系统设置现有“行情配置”页追加深度健康组件，不新增导航。`GET /api/admin/market/depth/status` 使用 `AdminPermission(settings, '')`；`PUT /api/admin/market/depth/settings` 使用 `AdminPermission(settings, 'save')`。唯一新增持久化开关 `market.depth.enabled` 存于现有租户 system_config；供应商只读，必须由原 EXCHANGE_MARKET_PROVIDER 修改。不允许管理员输入任意上游 URL。

显示本租户启用状态、全局开关、供应商、官方验证支持品种、实际档数/单位、最后成功/接收时间、延迟、连接状态、刷新方式、最近错误、活跃引用及同步计数。禁用保存成功后立即回收本租户需求；其他租户正在用同源缓存不受影响。REAL/DEMO 可共享无账户数据，设置及目录可见性严格按租户隔离。

## Figma / 用户端后续对接

沿用文件 v4V9L597ML6EhPMLi3kW8O，手机主页 34:428、探索 91:2235、深度 37:2315；PC 深度 55:2643。保留绿系、Noto Sans SC、五项导航和 A 版动效，只补来源/单位/真实档数/外部参考/状态。当前原型图表仍属设计示意，不伪称已接入用户端。

首页小图点击携带 instrumentId、symbol、marketType、levels；详情列表显示真实价格累计梯级和当前显示范围比率。无源时间用“接收于”；SYNCING、STALE、UNSUPPORTED、ERROR、DISABLED 和 REST 周期刷新均有明确文案，不能仅以颜色区分。主页摘要降为来源/状态两行，不塞全表。

适配：手机 320/375/390/430 留 16px 边距，元数据自动换行，长品种保留全称或完整查看入口，表格可横滚，按钮至少 44px；PC 1280/1440/1920 保留现有弹层，内容最大 1240px、图表双列。另有最窄 320px 的可编辑字段压力板，核对长中英文、极值、零、负值、缺失值和长日期；非法负价格/数量拒收，空缺用 —。后台继续使用现有桌面管理壳，窄屏折叠现有侧栏后横滚内容及表格，不重做导航；焦点可见，保存中及无权限禁用。

## 验收与回滚

解析/排序/累计/高精度/单位/快照替换/零量过滤/错误/过期/重同步测试；隔离 H2 HTTP + WebSocket + 租户及权限集成；真实来源短时连续验证，分别记录 REST、WS、重连及网络失败，不用单个 200 推断稳定。后台构建及浏览器交互/权限验证。配置使用现有表，无新增表迁移；生产回滚不得执行删除业务数据。实际结果、原型链接、完整运行命令和回滚方法见 [验收报告](C:/workspace/fx/705/docs/market-depth-acceptance.md)。

公开访问与对第三方展示许可不是同一件事。本次只做隔离工程验证，未部署生产。Binance 的本产品展示授权尚未确认；OKX 当前 API 协议明确限制第三方展示/再分发，需要适用范围内的书面许可。生产上线前必须确认相应权限，不能把匿名成功或无需 Key 当成授权证明。许可未确认期间部署配置应保持 `MARKET_DEPTH_ENABLED=false`。依据见验收报告的来源与许可核验。
