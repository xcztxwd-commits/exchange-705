# 外部财经新闻：阶段 3 本地验收报告

日期：2026-10-03，Asia/Singapore。目录：`C:\workspace\fx\705`。来源会话：《外部财经新闻：实现与验收（阶段3）》，ID `01a1009a-58e0-7601-a153-80dc460e5f96`。

## 结论与范围

本会话完成内容/工件交接后继续实现并验证新闻采集模块，而非重启或迁移来源会话。默认官方 RSS 的采集、入库、查询、后台管理、权限、隔离与 Figma 设计已完成本地验证；本报告不是生产部署验收。

三家官方 RSS 的真实 Java 适配器请求均返回 200，净化后持久化 **61 条**：Fed 15、BEA 42、ECB 4。这不表示加密媒体已全部接通，不把宏观新闻改标为加密快讯。手机/PC 用户端仅设计和对接规格，无源码修改或构建；没有部署生产或修改交易、余额、结算、用户授权与原平台公告职责。

交接入口为 `docs/handoff-news-20261003.md`，唯一实际接口合同为 `docs/news-contract.md`。阶段 4 未启动，不以本报告代替后续工单。

## 实现交付

- `exchange-backend/src/main/java/com/gtcfesk/exchange/insights/news/`：11 个新闻实体、目录、解析、持久化、集中采集、缓存、调度和公共控制器。
- `exchange-backend/src/main/java/com/gtcfesk/exchange/admin/AdminNewsController.java`：来源/文章/核验材料/审计入口，复用原管理员权限与令牌校验。
- `exchange-admin/src/views/NewsManagement.vue`：来源开关、许可、额度、状态、核验导入、隐藏恢复、分类排序、审计和只读权限界面。
- `exchange-backend/src/main/resources/db/migration/V2026100302__external_news.sql`：四张增量新闻表，不混入平台公告，不删除原业务数据。
- 共享文件只增加 `/news` 路由、新闻菜单及四动作、GET 公共读取规则、新闻 SQL 的精确 ignore 例外；保留其前两阶段原始内容和换行格式。
- 使用现有 Java/Spring/Vue/Element Plus 与标准库，没有新增运行依赖、真实密钥或用户端项目构建。

## 真实来源证据与许可范围

### 真实网络与材料重放必须分开

1. 07:18 UTC 左右的低频 HTTP 原始材料：`reports/news-20261003/source-http.json` 与 fed/bea/ecb.raw。
2. **生产代码路径的真实匿名网络请求**：`NewsIntegrationTest#liveOfficialAdaptersIntoIsolatedDatabase`，07:47:43–07:47:46 UTC，用 NewsSync.fetch 发请求、NewsParser 净化、NewsService 入隔离 H2 库并由真实 MVC 读取。证据为 `source-live-adapter.json`、`api-live-list.json`、`api-live-detail.json`、`source-live-adapter.log`。该独立测试成功，不是材料重放。
3. 单元、常规集成和浏览器 QA 为已捕获真实 RSS 的确定性重放；浏览器操作仍经过真实后端、权限和 SQL。人工长标题是明确的 `TEST_ONLY` 夹具，不是官方实际文章或生产新闻。

| 来源 | 真实 HTTP | 净化入库 | 跳过 | 范围 |
| --- | --- | --- | --- | --- |
| Fed | 200 | 15 | 0 | 官方政策新闻元数据 |
| BEA | 200 | 42 | 7 | 固定 www.bea.gov/news 路径；旧 apps/newsreleases、非 www 或无 scheme 的历史链接不自动扩大 |
| ECB | 200 | 4 | 11 | 非署名官方新闻稿/理事会决定标题；署名演讲、采访等不复制 |
| GDELT | 独立原始探测 200 | 默认未导入 | — | 适配关闭；不把发现时间冒充发布时间，不宣称稳定实时生产能力 |
| NewsData | 未认证请求 | 未导入 | — | 无 Key、账户权益未核验，保持 UNCONFIGURED/关闭 |

BEA 原始 RSS 有 49 个条目，但保守规则只接受 42 个；报告没有把过滤后数量误报成 49。ECB 原始 15 个条目中 11 个超出自动复制范围；详情 summary=null 合理，不为补齐界面编写摘要。

Fed 官方资料默认公有领域，但第三方/特别标记内容除外；保留归属，不使用官方 logo 或暗示背书。[Fed 使用说明](https://www.federalreserve.gov/disclaimer.htm)

BEA 默认资料范围与归属按其官方说明核验，特别说明的版权材料不自动复制。[BEA 官方问答](https://www.bea.gov/help/faq/147)

ECB 使用条件要求准确归属、明确免费来源、独立窗口而非页面框架；署名文章不能按一般新闻稿许可自动复制。本实现采用更窄的标题链接范围。[ECB 使用说明](https://www.ecb.europa.eu/services/using-our-site/disclaimer/html/index.en.html)

NewsData 原调研的“免费商用”结论不能直接照搬：2026-09-24 更新的官方套餐资料仍列免费 200 credits/day、每 credit 最多 10 项及 12 小时延迟；另一个当前官方页面却把免费商用列为 No。它们不能证明此项目账户获得生产展示权益，因此默认关闭，需要账户套餐/合同核验后再提供后端 Key，不自动注册、购买或声称认证成功。[官方套餐说明](https://newsdata.io/blog/pricing-plan-in-newsdata-io/)、[官方商业使用说明](https://newsdata.io/blog/free-news-api-for-commercial-use/)

## 自动化与构建

| 检查 | 实测结果 | 证据 |
| --- | --- | --- |
| 新闻解析/迁移/集成 | 23 项，21 通过，2 个可选现场模式默认跳过，0 失败/错误 | news-tests-verified.log、news-verified-summary.json、news-verified-xml |
| 新闻与阶段 1/2 相关回归 | 86 项，81 通过，5 个可选模式跳过，0 失败/错误；其中旧功能 60 通过 | related-regression.log、regression-summary.json、regression-xml |
| 真实官方 Java 采集联调 | 独立 1 项通过，三来源均 200，61 条持久化及列表/详情回包 | source-live-adapter.log/json、api-live-list/detail.json |
| 后端编译与打包 | Maven package 成功，NewsService.class 字节码 major=52 | backend-package-final.log、scope-preservation.json |
| 后台类型检查与 Vite 构建 | 成功，保留既有大 chunk 警告 | admin-build-final.log |
| 既有租户前端检查 | npm run test:tenant 成功 | admin-tenant-tests.log |
| MySQL 增量 DDL | H2 MySQL 模式测试通过 | NewsMigrationTest；不是生产 MySQL 联调 |
| 回滚预检 | 20 个新闻域文件、4 个精确共享逆增量通过；未执行 Apply | rollback-preflight.log、rollback-manifest.json |

运行环境是 Windows 11、Maven 3.9.9、JDK 21.0.11，编译目标为 Java 8、Spring Boot 2.7.18。验证了 Java 8 字节码和当前测试运行环境，**未另起真实 JRE 8 进程**。

测试覆盖 RSS/Atom、UTF-8 BOM、未知/异常/未来时间、重复规范 URL、不同来源不合并、纯文本净化、公共链接白名单、禁用 DTD/外部实体、大小/条数上限、人工导入先隐藏、无权限/版本竞争、分页/分类/语言/时间窗口、租户与环境、来源立即禁用、文章立即隐藏/恢复、304/429/超时/空源、旧缓存、来源租约/日预算、并发导入幂等、秘密不出回包、平台公告仍可独立读取。

接续中发现并修复 Fed BOM 导致解析失败、JDK HTML 解析器暴露 style 文本、发现时间纳秒与数据库精度造成重复导入更新。另修正测试对 BEA 过滤数的错误假设，以及 UTC 分钟边界造成限流检查偶发误判；没有通过放宽采集策略或关闭验证来取得通过。

### 重复运行命令

在 `exchange-backend`：

```powershell
mvn -q '-Dtest=NewsParserTest,NewsMigrationTest,NewsIntegrationTest' test
mvn -q '-Dtest=NewsIntegrationTest#liveOfficialAdaptersIntoIsolatedDatabase' '-Dnews.live=true' test
mvn -q -DskipTests package
```

不要并发运行同目录 Maven 验收。真实联调是显式现场模式，会发出三次官方 RSS 请求；普通 suite 不访问上游。后台在 `exchange-admin` 运行 `npm run build`、`npm run test:tenant`。

## 后台浏览器 QA

### 环境与目标流程

目标 URL 为 `https://admin.mt705.test/news`，Playwright 路由保持该 Host/Origin，把 API 转发至本机随机端口的隔离 Spring 后端，静态文件由 127.0.0.1:5189 的 Vite preview 提供。它不是生产域名 TLS/部署验收，也没有 mock API JSON。

Browser 插件/`browser` 技能未提供，按 frontend-testing-debugging 技能使用已安装 Playwright 与无头 Edge，记录原因为 `Browser plugin not available`。没有安装额外依赖或启动可见浏览器窗口。

最终 **50 项检查全部通过**，console/pageErrors 均为空。所有新模块操作按钮至少 44px；320/375/390/430/1280/1440/1920 都检查模块边界与表格水平滚动。窄屏主体检查使用已有侧栏折叠开关，不假称修好了整个后台框架的移动端布局。

| 必查项 | 结果 |
| --- | --- |
| 页面 URL/标题与新闻模块标识 | 通过，Exchange system / 外部财经新闻 |
| 非空真实内容、五个固定来源 | 通过 |
| 无框架错误遮罩 | 通过 |
| 控制台和运行时错误 | 0 |
| 桌面、390 窄屏、只读详情截图 | 已保存并实际视觉检查 |
| 真实交互与后端状态对照 | 通过 |

交互闭环：列表/详情；隐藏后公开详情 404；隐藏筛选与恢复 200；刷新保持设置；Fed 禁用后公开条数 0、重新启用恢复 15；同步退避不请求上游；TEST_ONLY 长中文核验导入新增隐藏且发布时间未知；加密分类真实空态；实际操作者审计；分页更新；键盘焦点；只读界面无写按钮/不可改隐藏状态、直接后端 PUT 403；无菜单权限跳转 forbidden。

证据为 `reports/news-20261003/browser-qa.json`、`browser-qa.mjs`、`browser-qa.log`、`admin-news-first-viewport.png`、`admin-news-desktop.png`、`admin-news-hidden.png`、`admin-news-390.png`、`admin-news-readonly-390.png`、`admin-news-no-permission.png`。

首次 QA 的 loading 遮罩多元素、Element Plus 隐形原生 input、尚未完成查询时焦点检查属于测试定位/等待问题，已改为等待全部过渡结束、点击可见 checkbox 标签并验证 input、等待实际查询完成。复跑时使用全新隔离后端，避免先前 TEST_ONLY 状态污染验收。没有用 force click 或绕过真实权限来通过操作。

仅 Edge 和上述七种宽度实测；物理移动设备、其他浏览器、系统读屏软件和生产网络未实测。两次保持窗口在 QA 后主动终止，临时 JWT 文件已删除，Vite preview 已停止，未干预用户原有服务。Browser 插件可作为未来同类 QA 的可选体验改进，本次不需要安装。

## Figma 与用户端对接

文件 `v4V9L597ML6EhPMLi3kW8O`，手机页 `32:356` 的新闻看板 `211:7050`，PC 页 `47:356` 的看板 `211:10667`。手机分别有 320/375/390/430 列表与详情、390 首页摘要/状态；PC 有 1280/1440/1920 列表与详情、1440 首页摘要/状态。

实际读取所有新增 screen 结构，TEXT/FRAME/INSTANCE/VECTOR 可编辑，flattened image fills 为 0，字体 Noto Sans SC；手机底栏每屏五项。核对并修正 GDP 长标题卡片从错误 Fed 归属为 BEA；手机状态屏增高、导航下移，避免状态文案压住导航。修后手机 320 列表与 PC 1440 列表通过工具原生截图实际查看。

手机本次修正 7 个节点，PC 4 个，具体 mutatedNodeIds、前值、字体、结构计数、screenId 见 `figma-resume-verification.json`；原设计写入证据为 `figma-write.json`。原新闻节点 `37:2546`、`55:2978`、原首页和 A 导航组件未改。覆盖延迟、未知时间、加载/同步、空态、部分失败/旧缓存、禁用/下架 404 和无权限状态。原 `figma-mobile-320.png` 是归属修正前截图，不能作为最新正确归属证据。

用户端实施时按唯一合同复用同一 articleId 的首页摘要、探索列表与详情；新闻/公告分栏，不自行拉上游，不把 GDELT discoveredAt 显示为 publishedAt，不使用 iframe 打开 ECB 原文。具体用户端接入属于后续已授权工单，本阶段没有修改那两个项目。

## 数据与回滚保护

`scope-preservation.json` 核对原阶段 1/2 基线：所有非共享文件哈希未变，只有四个批准共享增量；`git status --porcelain -- exchange-frontend exchange-pc` 为空。HEAD 保持 `6b93d62b5a3e748162f9eaea02f851e9a188e30e`，没有 commit/reset/checkout 或覆盖旧成果。共享差异检查按 Windows CRLF 格式执行 `git -c core.whitespace=blank-at-eol,blank-at-eof,space-before-tab,cr-at-eol diff --check` 通过；默认 Git 检查把保留的 .gitignore CR 字符误判为行尾空白，未为消除该提示改写前阶段文件。

接续前草稿备份在 `rollback/news-handoff-20261003`，前阶段基线在 `reports/news-20261003/baseline.json`，接续草稿快照在 `resume-baseline.json`。

在仓库根目录执行：

```powershell
python reports/news-20261003/rollback_news.py
# 只有明确要回滚本地源码，并已关闭新闻调度/停止指定实例时，才显式执行：
python reports/news-20261003/rollback_news.py --apply
```

默认只读预检；Apply 未实测、未执行。脚本验证 HEAD、先前阶段文件、新闻域当前哈希、路径与精确逆增量；后续变化则拒绝覆盖。Apply 将新增新闻源码/测试/资源归档到独立时间戳目录、备份当前共享文件，并仅删除四个新闻增量，验证结果必须复现原阶段基线字节，不盲目恢复旧共享文件。知识文档/报告与四张数据库表保留，不删除采集记录，也不替用户停止服务、改生产配置或自动部署。

## 未验证与下一步

1. NewsData 后端 Key、实际账户套餐/展示权与认证上游尚未提供；GDELT 默认关闭，未验证长时间生产稳定性。
2. 实际 MySQL 迁移与并发锁、多节点分布式运行、独立 DEMO 进程、真实 JRE 8 和生产网络尚未验证；环境隔离已在 REAL 进程的同库 DEMO 行上测试。
3. 定时方法已实现，但默认关闭；没有声明经过长时定时运行、生产重启恢复或全天实时 SLA 验收。
4. 未部署生产，用户端只完成 Figma/合同；下一阶段按原串行工单继续，不把新闻采集本地成功等同于上线完成。

### 截图

![后台列表交互后的桌面视图](C:/workspace/fx/705/reports/news-20261003/admin-news-desktop.png)
![只读权限的窄屏详情](C:/workspace/fx/705/reports/news-20261003/admin-news-readonly-390.png)
