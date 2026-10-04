# 管理员维护的交易员展示合同

日期：2026-10-03（Asia/Singapore）。本合同先于实现保存；验收结果另见 traders-acceptance.md。

## 范围与身份

不是外部交易所榜单，不关联用户、账户、订单或私人 asset-history，不提供跟单。
每个展示人物有不可变 UUID `traderId`。存储、审计、头像、查询均限制当前租户和服务器 REAL/DEMO 环境。
`ADMIN_CURATED` 是人工资料；`DEMO` 只允许 DEMO 环境。当前不具备授权私有记录关联与核验链，拒绝 `VERIFIED_PLATFORM`，不能勾选实盘。

状态 `DRAFT/PUBLISHED/OFFLINE/DISABLED`。新建为草稿；资料、曲线、历史修改使已发布人物回到草稿，需要重新预览、发布。发布、下架、禁用与推荐排序分别鉴权。推荐排序可直接更新，不叫业绩排行。
读取不设业务缓存，HTTP `Cache-Control: no-store`；发布/下架、禁用、排序及子记录更新下一次读取立即生效，不存在待失效缓存。

## 数据与口径

资料：name、avatarUrl、bio、strategyTags、statisticStart/End（UTC，最多3660日）、currency、sourceNote、metricBasisNote、riskNote、authorizationNote、pointIntervalHours、manualRoi/manualWinRate/manualMaxDrawdown。
头像只能引用当前租户管理员经已有 `/api/upload/image` 保存的图片。authorizationNote 是内部展示授权记录，公开接口不返回；不是实盘核验。
缺失指标为 null；人工指标始终带 `metricSource=MANUAL`、统计期及口径，不推断、计算或承诺收益。
净资产曲线点：pointId、pointAt、netAsset、cashFlow（未知为 null）、currency、sourceNote。净资产不等于策略收益；当前不计算 TWR、ROI、回撤或现金流调整收益，不开放业绩排序。
记录闭仓历史：recordId、closedAt、symbol、direction（LONG/SHORT）、leverage、quantity、quantityUnit、pnl、pnlBasis（BEFORE_FEES/AFTER_FEES/NOT_PROVIDED）、fees、currency、sourceNote。只存/展示平仓时间；费用与盈亏不可推断。内部 evidenceNote 不公开，`verificationStatus=UNVERIFIED`。
金额和百分比以十进制字符串返回，数据库 DECIMAL(38,18)；ID 为字符串，版本为整数。净资产非负、数量及杠杆提供时必须为正，胜率与回撤为0至100。
公开说明固定为“平台展示·人工维护”“净资产曲线·人工维护，非策略收益率”“历史记录·人工维护”。DEMO 额外标注演示。

## HTTP

成功直接 JSON（沿用日历/新闻域），失败同时检查 HTTP 状态与 success/message。401 未登录，403 无权限，404 无可见对象，409 旧版本，400 校验/不支持的排序，429 本租户读取预算耗尽。
所有分页 page 从0开始、size 1至100、page最多10000；稳定排序最后追加 UUID。不允许查询参数切换租户或环境。

公开 GET：
- `/api/insights/traders`：q、currency、sourceType、recommended、sort=RECOMMENDED/UPDATED/NAME、page、size。仅已发布可公开对象；推荐次序 sortOrder 降序、updatedAt 降序、traderId 升序。
- `/api/insights/traders/{id}`：资料、明确的人工口径与统计期。
- `/api/insights/traders/{id}/equity`：period=ALL/7D/30D/90D、page、size。窗口截止 statisticEnd，受完整统计期限制。返回 curveStatus、gapCount、coverageStart/End、现金流未知标志，不填补缺点；跨缺点不可连线。零基数、无曲线、不足两点分别明确。
- `/api/insights/traders/{id}/history`：同周期分页，closedAt 降序、recordId 升序。指标仍属于完整统计期，不因切换曲线窗口变成对应窗口收益。

管理前缀 `/api/admin/insights/traders`：
- GET 列表（另可 status 筛选）、`/{id}`、`/{id}/preview`、`/{id}/equity`、`/{id}/history`、`/audit`，需 traders:view。
- POST 新建、PUT `/{id}`，需 traders:edit；保存只接资料字段，不接受发布状态或核验标识。
- PUT `/{id}/publication`：rowVersion、status、reason，需 traders:publish；status仅PUBLISHED/OFFLINE/DRAFT。发布要求来源、口径、风险及展示授权完整。
- PUT `/{id}/disable`：rowVersion、disabled、reason，需 traders:disable。重新启用回草稿，不自动恢复公开。
- PUT `/{id}/recommendation`：rowVersion、recommended、sortOrder、reason，需 traders:sort。
- POST `/{id}/equity`、PUT/DELETE `/{id}/equity/{pointId}`，需 traders:equity。
- POST `/{id}/history`、PUT/DELETE `/{id}/history/{recordId}`，需 traders:history。
- GET `/{id}/import-template?kind=equity|history`；POST `/{id}/equity/import`、`/{id}/history/import`，需对应子记录权限。

所有写操作需最新人物 rowVersion 与修改依据 reason。子记录写串行锁定人物并更新父版本，防止旧预览发布及并发覆盖。审计含操作者、UTC、修改前后完整值和依据。
JSON 批量导入最多500条，追加模式；严格日期/单位/金额与重复校验，整批先验证再写事务，任何错误整批不写。返回带行号的 errors，不能静默跳过。模板无真实用户或虚构业绩默认数据。

## 用户端对接边界

手机/PC 只 Figma 设计，不修改或构建 exchange-frontend / exchange-pc。主页推荐复用列表 `recommended=true&sort=RECOMMENDED&size=3`，详情、曲线、历史携带同一 traderId 和 statisticPeriodId；资料更新时间变化即重新读取，不缓存旧发布状态。
四域已有独立接口及缓存，不新增 home-summary 采集或私有数据聚合；统一入口映射在 insights-integration-contract.md。

## 迁移与启动

增量版本 V2026100303，顺序在 V2026100301/02 后；仅新增 trader_profile/equity/history/audit 四表。不自动填充人物、角色授权或示例资料。生产迁移需人工审批；回滚关闭新入口/撤销权限，保留四表及审计，不删除真实记录。
