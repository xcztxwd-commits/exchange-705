# 管理员维护的交易员展示：实现与验收

日期：2026-10-03（Asia/Singapore）。实现路径：`exchange-backend`、`exchange-admin`；Figma 用户端设计另见 [`traders-ui-design.md`](traders-ui-design.md)。这是本机隔离验收，不是生产发布。

## 实现项

| 项目 | 实现 |
|---|---|
| 管理入口和权限 | 后台 `/traders`；列表/只读/编辑/发布/下架/禁用/推荐排序/曲线/历史/导入分别检查已有 RBAC 权限。无写权限时不显示写控件；服务端仍强制鉴权。 |
| 资料与生命周期 | 租户和 REAL/DEMO 环境隔离；草稿预览、发布、下架、禁用/恢复、推荐排序；新建不自动公开。`VERIFIED_PLATFORM` 被拒绝。 |
| 曲线与历史 | 人工净资产点、缺口/基数/点数状态；未知现金流保持 null。不从净资产推算 ROI。历史仅公开管理员录入的闭仓展示字段，内部核验/授权注记、账户余额、未平仓和私人订单不进入公用 DTO。 |
| 导入、审计与冲突 | JSON 模板；最多 500 行，先全量校验、再事务写入，错误带行号且整批不写；每个写入带 `rowVersion` 与 `reason`；审计记录操作者、时间、修改前后和依据。 |
| 头像和缓存 | 复用已有当前租户图片上传流程；只允许发布资料引用可访问的租户头像。公开接口 `Cache-Control: no-store`，下架/禁用立即让公共读取变为 404。 |
| 存储 | 增量迁移 `V2026100303__curated_traders.sql`，新增 profile/equity/history/audit 四表；无预置人物、外部账号 Key 或真实账户数据。 |

字段、权限、状态机及精度合同见 [`traders-contract.md`](traders-contract.md)；四域统一 API 映射见 [`insights-integration-contract.md`](insights-integration-contract.md)。

## 实际验证

| 验证 | 结果 | 证据与边界 |
|---|---|---|
| Maven 后端回归 | `TraderIntegrationTest`: 14 项运行、13 通过、1 项可选跳过、0 失败；`TraderMigrationTest`: 1 项通过 | 本机 H2 隔离数据库、TEST_ONLY 测试身份；不等同 MySQL 生产迁移。原始日志在 `reports/traders-20261003/trader-final-regression.log`。 |
| 四域联合确定性回归 | 12 个深度/日历/新闻/交易员测试类共 80 项；74 通过、6 项条件/可选跳过、0 失败/错误，Maven exit 0 | 排除联网 live probe；四个域的外部来源实测单列，不把外部实网状态混进模拟测试。命令与汇总日志 `reports/traders-20261003/four-domain-regression-final.log`。 |
| 后台构建 | `npm run build` exit 0 | 最终产物含 `TraderManagement` chunk；既有主 chunk >500 kB 警告仍在，不是构建失败。日志 `reports/traders-20261003/admin-build-final.log`。 |
| 浏览器 + 本机真实 HTTP/API | Playwright + Edge，30 项断言全通过；所有 CRUD/状态/公开读经本机隔离服务实际请求，不是 mock | 320/375/390/430、1280/1440/1920 七种宽度；44px 控件、键盘焦点、只读角色、错误行回显、私有草稿/发布/下架/禁用、头像、曲线、闭仓历史。日志 `reports/traders-20261003/browser-qa.json` 与 `http-smoke.json`。 |
| 实际公开响应 | 发布时列表/详情/曲线/历史 HTTP 200；离线与禁用后公共详情 HTTP 404；响应有 no-store | 隔离测试人物 `e04cd90a-f3a2-4879-a4c8-6db1574ace52`，`TEST_ONLY`，`UNVERIFIED`，`ADMIN_CURATED`，两个人工曲线点（状态 GAPPED）、一条闭仓记录。没有任何真实交易员或账户记录。 |
| Figma | 手机状态板 320/375/390/430；PC 状态板 1280/1440/1920；结构及节点文本已由 Figma Plugin API 检查 | 只是设计交付；用户端源代码没有构建。未声称 Figma 图通过像素级视觉截图验收。 |

浏览器的 390px PNG 取于脚本连续写操作后，画面暂时叠有多条成功 toast；测试断言确认模块边界/可点击尺寸，但该截图不作“干净最终视觉版”证据。当前没有单独在 toast 消退后重新截取一张最终视觉图。

### 未完成的实网/发布验证

- 交易员采用管理员人工录入，无外部交易员 API，故本域无上游 API Key/网络连通性测试；浏览器中的签名身份只用于本机隔离验收，未使用生产 Key/许可。
- 没有第三方账户/订单授权链，资料授权由发布必填的内部说明保留，不能据此标记真实核验；用户提供的资料真实性/展示权也未由本工单外部审查。`VERIFIED_PLATFORM` 继续禁用。
- 后端迁移只在 H2 MySQL 兼容模式测试；真实 MySQL 5.7/8、既有库校验/演练、外部权限角色同步、生产 RBAC 及部署均未执行。
- 未构建或接入 `exchange-frontend` / `exchange-pc`；没有线上用户端验收或真实人物数据。

## 迁移与回退

运行环境升级顺序为 V2026100301（日历）、V2026100302（新闻）、V2026100303（交易员），但必须由目标环境的审批/DBA 流程在备份后执行；本工单未自动迁移任何线上库。

非破坏性停用顺序：禁用 `/traders` 菜单/新路由入口并撤销本功能写权限；API 下架已发布人物或关闭服务入口；保留迁移表及审计记录。**不要 DROP 四表、删除人物历史、执行全仓 Git reset/checkout。** 本地原文件快照目录：`rollback/traders-20261003`（保留的 `.gitignore`、浏览器验收脚本和测试原稿）；它不包含所有被本工单触及文件的完整逆向补丁，进一步回退须先逐文件审核 Git diff/hash，不可盲目覆盖。
