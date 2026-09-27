# 个人中心资金变动曲线：技术与前端方案

## 结论

在现有个人中心资产区加入「总资产走势」，先做 **USD 口径的资产余额曲线**，不把入金误称为收益。页面沿用现有四栏底部导航与绿色品牌色；采用深石墨资产卡、细绿色面积线、留白和清晰数字层级，不直接复刻参考图的粉色像素效果。

交互原型：[profile-asset-curve-prototype.html](./profile-asset-curve-prototype.html)。原型数据全部为演示数据，未连接账户。

## 现状与必须先统一的口径

- `exchange-frontend/src/views/Profile.vue` 调用 `GET /api/user/{userId}/info`，当前总资产只加三类账户的 `available`。
- `exchange-frontend/src/views/Assets.vue` 调用 `GET /api/user/assets`，总资产加 `available + frozen`；`UserDashboardService` 也加两者。若曲线沿用个人中心现值，会与资产页不一致。
- `asset_account` 只有最新余额与更新时间，没有历史余额序列。入金、出金、划转记录不能独立重建每个时点的总资产，且划转不应使总额跳变。

**推荐统一定义：** `totalUsd = Σ(FUND, CONTRACT, OPTION).(available + frozen)`。由后端同一个估值函数生成卡片现值与历史快照。当前产品的三种逻辑账户均按 USD 余额处理；若以后计入持仓浮盈或多币种，需另定估值、历史汇率与收益口径，不能只换图表文案。

## 数据方案

1. 新增 `asset_valuation_snapshot`：`user_id`、`bucket_at`（UTC）、`fund_usd`、`contract_usd`、`option_usd`、`total_usd`、`created_at`，唯一键 `(user_id, bucket_at)`，金额使用数据库 DECIMAL 与 Java `BigDecimal`。
2. 在所有改变 `asset_account` 的业务成功提交后记录快照：入金审核、出金审核、划转、合约/期权结算、理财收益、后台调账。5 分钟内重复更新同一桶，以最后一次提交后的资产值为准；另设低频对账任务修复漏记。不要在事务未提交时读取快照。
3. `GET /api/user/assets/trend?range=1D|1W|1M|1Y` 从当前认证身份取 `userId`，不接受任意用户 ID；返回 `asOf`、`currency: "USD"`、`total`、`change`、`changePct`、`firstAvailableAt`、`points:[{at,total}]`。总数与最新点取同一估值时刻。返回 `Cache-Control: private, no-store`。
4. 后端按区间降采样，最多约 366 点：1D 为 5 分钟、1W 为 1 小时、1M 为 1 天、1Y 为 1 天。没有交易的桶延续上一时点余额；首个真实快照前返回 `null`，**不补零、不伪造历史**。新功能上线前的历史不可从现有账户余额可靠回推，界面提示「历史数据自启用后开始」。
5. 时间存 UTC，前端按用户本地时区显示。区间变化值是 `末值 − 期初值`；期初为 0 时百分比显示 `—`，不是 100%。文案用「较期初变化」，不叫「收益」。

示例响应（结构示意，数值非真实账户）：

```json
{
  "currency": "USD",
  "asOf": "2026-09-27T08:00:00Z",
  "total": "243541.37",
  "change": "2481.32",
  "changePct": "1.0293",
  "firstAvailableAt": "2026-09-01T00:00:00Z",
  "points": [
    { "at": "2026-09-27T07:55:00Z", "total": "243510.12" },
    { "at": "2026-09-27T08:00:00Z", "total": "243541.37" }
  ]
}
```

## 前端方案

- `Profile.vue` 将顶部资产卡换为「资产总额 + 本期变化 + 曲线 + 周期切换」一体卡；下面仍是入金、出金和原有菜单。划转可提升为第三个快捷入口，但不改变原路由。
- 单序列、最多 366 点，优先原生 SVG；现有 `Sparkline.vue` 是 60×20 的静态小图，不适合触摸提示和区间轴。Vue 组件可用 `AssetTrendChart.vue`，状态只需 `range / points / activeIndex / balanceVisible`。无需给个人中心加载完整 K 线库。
- 1日、1周、1月、1年切换；按住/鼠标悬停看时间与金额；隐藏资产时金额、变化值、提示和曲线一起隐藏；请求中显示骨架，数据不足显示空态，失败显示重试。切换周期时取消或忽略过期请求。
- 触控区不抢页面垂直滚动；按钮有 `aria-label` 与 44px 点击区。图表提供文字摘要、键盘左右键查看采样点；支持系统减少动画设置。避免把红绿作为唯一涨跌信息。
- 视觉令牌：页面 `#F6F7F4`、深卡 `#14201C`、强调色 `#B8EF80`、正文 `#17211B`；圆角 24px；横向边距 20px；金额 40–44px 等宽数字；图表高度约 170px。图线细、渐变浅，不使用霓虹光晕。

## 验收

1. 个人中心、资产页、曲线末点余额一致，冻结金额计入总资产；账户间划转不改变总额。
2. 切换区间后末点仍是同一当前资产；无历史时不绘制虚假曲线；变化百分比分母为零时显示 `—`。
3. 未授权请求被拒绝，不能通过参数读取其他用户走势；金额不走 JS 浮点累计。
4. 375px、390px、430px 宽度和桌面预览无溢出；眼睛按钮隐藏所有金额；触摸/键盘可读点值。

## 开源参考与取舍

- [Apache ECharts 面积图指南](https://apache.github.io/echarts-handbook/en/how-to/chart-types/line/area-line/) 与 [许可说明](https://echarts.apache.org/en/faq.html)：Apache-2.0，适合以后扩展多曲线、缩放等复杂交互。
- [TradingView Lightweight Charts](https://www.tradingview.com/free-charting-libraries/)：Apache-2.0、移动端友好，但公开产品须遵守其[署名要求](https://github.com/tradingview/lightweight-charts/blob/master/website/docs/intro.mdx)。本页只有一条资产曲线，先用 SVG 更轻。
- 版式参考用户提供的 OKX 截图：大额资产数字、周期切换、图表下方快捷操作与资产分布；不复制其图标、品牌或特有图形。
