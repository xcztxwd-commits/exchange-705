# 管理员交易员展示：用户端 Figma 交付

日期：2026-10-03（Asia/Singapore）。Figma file key：`v4V9L597ML6EhPMLi3kW8O`。

## 页面

| 终端 | 首页 | 探索列表 | 详情 |
|---|---|---|---|
| 手机 | [摘要卡](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=34-428) | [交易员列表](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=37-2611) | [交易员详情](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=37-2723) |
| PC | [摘要卡](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=48-380) | [交易员列表](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=55-3070) | [交易员详情](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=55-3207) |

## 数据与状态规格

- [手机状态板 390px](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=228-7473)，同版宽度变体：[320px](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=230-7473)、[375px](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=230-7528)、[430px](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=230-7583)。
- [PC 状态板 1440px](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=228-10602)，适配变体：[1280px](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=233-3950)、[1920px](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=233-4007)。Figma 插件结构检查确认七个状态板节点尺寸、核心状态文案及所有嵌套图层未超出画板边界；本记录不声称通过像素级截图/视觉对比。
- 资料来源明确 `ADMIN_CURATED` / `DEMO` / 不可选 `VERIFIED_PLATFORM`；曲线区覆盖 `NONE`、`INSUFFICIENT`、`ZERO_BASE`、`GAPPED`、`AVAILABLE`；公开状态覆盖 `DRAFT`、`PUBLISHED`、`OFFLINE`、`DISABLED`。缺值留空、断档不插值、不把净资产写成策略 ROI；历史仅闭仓、按 `closedAt` UTC 展示。
- 手机/PC 首页摘要、列表及详情携带同一 `traderId=e04cd90a-f3a2-4879-a4c8-6db1574ace52` 和完整 UTC 期间 2026-09-01 至 2026-09-30；PC 用户样例显式标 `TEST_ONLY`，手机页面保留“界面样本·非实盘数据”标记。状态板上的同一 fixture 也标为 **TEST_ONLY**，来自隔离 H2/浏览器验收；不是生产人物、真实客户资料或业绩。
- 对首页/列表/详情按祖先可见性检查：旧示例 ROI、旧 UUID、示例曲线及两条样例历史记录没有在 trader 列表/详情有效可见；改为 `—` 与空状态。该检查是节点/文本和可见性检查，不代替用户端代码或像素截图。
- 保留原 V2 导航与页面结构，没有编辑移动/PC 底部导航组件（移动 `32:509`、`32:388`、`93:2290`；PC `47:418`、`47:420`、`47:412`）。

## 用户端边界

本次只交付 Figma 和 [`insights-integration-contract.md`](insights-integration-contract.md)。`exchange-frontend`、`exchange-pc` 未改、未构建、未声称已接 API；上线用户端需另开代码工单并按同一 `traderId/statisticPeriodId` 对接。
