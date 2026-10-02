# 持仓分享模板多语言修复（2026-09-27）

## 检查结论

共检查 16 款模板：9 款使用 Canvas 绘制背景，7 款使用 PNG。逐张查看了 7 张 PNG，并检查了全部渲染分支。

| 模板 | 背景检查 | 修复 |
| --- | --- | --- |
| light / dark / chart | Canvas 图形，无烘焙文字 | 统一使用当前页面语言文案 |
| gold / globe / architecture / city | Canvas 山峦、地球、建筑、城市，无烘焙文字 | 同上 |
| referenceGold | PNG 无文字；代码固定日文标签及英文标语 | 标题、方向、数量单位、价格、费用、订单时间、页脚全部本地化 |
| referenceWhite | PNG 建筑墙面含 TRADE SMARTER LIVE BRIGHTER；代码含日英双语 | 新增无文字背景；墙面文字通过 Canvas 按当前语言绘制；移除重复外语副标题 |
| referenceTerminal | PNG 无文字；代码固定日文和英文 | 方向、单位、资金、费用、订单、标语本地化 |
| launch / aurora / racing / voyage | 4 张 PNG 无文字；代码固定英文大标题 | 大标题、小标题、装饰文案、页脚跟随语言 |
| receipt / journal | Canvas 背景；代码固定英文标题 | 标题及其他文字全部使用当前语言字典 |

手机端和 PC 端保持相同的渲染器、文案和背景文件。支持页面现有 19 种语言；中文跟随现有页面的 `zh-TW`。区域语言码按页面规则归一，如 `ja-JP` → `ja`、`zh-CN` → `zh-TW`。品牌名、交易品种、币种代码、行情来源、时区和数值不翻译。

## 后台设计与已实现规则

1. 每款模板可启用/停用，并保留全局排序。
2. 勾选“全语言通用”时，所有页面语言都能使用该模板；**不是固定使用同一语言的文字**。
3. 取消勾选后，可选择一种或多种适用语言。例如白色原版仅日语、黑金仅中文和韩语、清爽白全语言。
4. 前端先按当前页面语言筛选，再使用全局排序；第一款匹配模板为默认模板。移动通用模板会影响全部适用语言，后台明确提示。
5. 后台有语言预览：显示当前语言的默认模板及可用顺序。
6. 每种页面语言必须至少有一款模板；前后端同时校验，防止错误配置让某种语言无法分享。可以只用分语言模板覆盖全部语言，不强制必须有通用模板。
7. 选择记忆按语言隔离。弹窗内切换页面语言会重新获取配置、清空旧预览、重新生成图片，过期异步结果不会覆盖新语言。
8. 原 `share.templates` CSV 配置继续可读，语义为全语言通用。后台再次保存时升级为版本 2 JSON；不需要改表或迁移历史数据。

接口：`GET /api/user/share-templates?locale=ja`，保持原来的模板 ID 数组响应。未传语言沿用英语默认。

配置示例：

```json
{
  "version": 2,
  "templates": [
    { "id": "referenceWhite", "languages": ["ja"] },
    { "id": "gold", "languages": ["zh-TW", "ko"] },
    { "id": "light", "languages": ["*"] }
  ]
}
```

## 背景资产

使用内置 image_gen 编辑原白色背景，未使用 CLI；仅清除建筑上的英文，保留建筑、绿光和背景构图。原 PNG 保留，新文件名避免旧缓存继续显示英文。

- `C:/workspace/fx/705/exchange-frontend/public/share-templates/reference-white-neutral.png`
- `C:/workspace/fx/705/exchange-pc/public/share-templates/reference-white-neutral.png`

最终提示词：

> Edit this existing website trade poster background. Remove ONLY ALL grey English lettering on the architectural building (TRADE SMARTER LIVE BRIGHTER). Inpaint with the matching concrete wall texture, shading and perspective. Keep the entire image composition, dimensions/aspect ratio, architecture shapes and positions, green diagonal light stripe, white paper background, lighting and colors unchanged. Absolutely no letters, words, numbers, logos or replacement text anywhere. This must be a reusable language-neutral background; localized text will be drawn separately in Canvas.

## 验证

- 渲染回归：19 种语言 × 16 款模板 × 4 种显示模式，共 1216 组；校验绘制文字来自当前语言字典，无固定日文泄漏，并保留金额/收益率隐私测试。
- 后端：默认配置、旧 CSV、语言匹配、区域码、排序、重复项、未知语言、空范围、语言覆盖校验。
- Chromium：手机和 PC 各生成 304 张真实 Canvas 图片，验证全部模板、语言切换、按语言筛选及 PNG 下载；共 608 次渲染，无未捕获页面异常。
- 后台浏览器：旧配置加载、取消全语言勾选、选择日语、语言默认模板预览、保存重载、缺失覆盖阻止保存。
- 三端生产构建通过；保留原有大包警告。
- 所有浏览器测试使用模拟订单和模拟配置接口；没有修改实际数据库或用户订单。未部署或重启运行中的服务。

验证日志、语言预览图和浏览器检查脚本在 `C:/workspace/fx/705/reports/share-localization/`。修改前文件备份在 `C:/workspace/fx/705/rollback/share-locale-20260927/`。

复测命令（项目根目录）：

```powershell
node --experimental-strip-types exchange-frontend/src/utils/orderShare.test.mjs
mvn -f exchange-backend/pom.xml -Dtest=ShareTemplateConfigTest test
npm --prefix exchange-frontend run build
npm --prefix exchange-pc run build
npm --prefix exchange-admin run build
```

## 发布及回滚

发布时同时更新后台、后端、手机端和 PC 端。先保留现有配置，再在后台设置需要的语言范围；旧配置无需人工修改即可继续使用。

回滚代码时使用本次备份，避免覆盖工作区其他任务的修改。若已保存版本 2 配置，旧后端不能读取 JSON，应先恢复发布前的 CSV 配置，再回滚后端；本次测试未写入真实配置。新增无文字 PNG 可以保留，不影响旧代码。
