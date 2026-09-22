# 下单区设计方案

状态：已按 A 方案实现手机和 PC 下单页；B、C 保留为设计对比。图片由内置 image_gen 生成，数值仅作示例；示例忽略手续费，实际每手数量、费率和杠杆上限取品种配置。

- A：顶部倍数按钮，移动端底部弹层。推荐。
- B：手数旁倍数按钮，锚定浮层。适合桌面端。
- C：独立设置行，居中弹窗。入口文字更明确。

## 共同行为

仓位比例表示本次订单拟使用的可用资金比例（保证金和开仓手续费）。0/25/50/75/100% 刻度，连续拖动。手数按品种允许步长向下取整；手动修改手数反算比例。100% 必须计入手续费，不能超过可用余额；不足最小手数时不自动抬高下单量。零手数时禁止下单，预计强平价显示 —。

杠杆入口始终展示当前倍数；通过输入、加减和预设按钮编辑，确认后生效，取消不变。没有杠杆滑条。选择范围来自品种配置。仓位比例驱动时修改杠杆保持比例并重算手数；手动输入手数时修改杠杆保持手数、重算资金占比，避免意外改变用户明确输入的数量。

买入与卖出分别展示预计强平价。当前后端按账户整体权益触发强平，估算必须包含已有持仓、手续费、合约单位和结算汇率；不能套用交易所逐仓公式。多持仓估计需明确其他品种价格保持不变的假设；行情/汇率缺失或无法求出正价格时显示 — 及原因。重算不保证每次操作数值都会变化。

## 参考

- OKX：https://www.okx.com/en-gb/help/how-do-i-adjust-leverage-levels
- Binance：https://www.binance.com/en-AE/support/faq/detail/360034946672

参考的是交易表单附近的倍数入口和确认流程；本方案按需求移除设置内的杠杆滑条，不是官方截图。

## 完整生成提示词

### A

Use case: ui-mockup. Create a polished high fidelity Chinese trading interface UX proposal, landscape 1536x1024. Existing product uses white background, very light gray input fields, charcoal text, lime green #8cc63f main accents and green/red buy sell buttons. Crisp readable Simplified Chinese typography, no device perspective, no decoration, no brand logos. Two side-by-side large mobile order panel states on an off-white presentation canvas: left resting order form and right settings opened. Tiny annotation between them shows exactly which button opens settings. Show BTC/USDT header, 合约交易, 市价 / 限价, available balance 可用 10,000.00 USDT, 手数 with minus plus input 2.00 手, position allocation slider labeled 仓位比例 with selected 50% and tick labels 0% 25% 50% 75% 100%, estimated margin 5,000.00 USDT, paired rows 预计强平价（买入）45,000.00 and 预计强平价（卖出）55,000.00, bottom 买入 / 卖出 buttons, small note 示例数据，仅展示交互. Price example 50,000, 1 lot equals 1 BTC, leverage20x, fee omitted illustrative. The ONLY slider is position allocation. Never show ANY leverage slider anywhere. Leverage settings use large minus / editable 20× / plus, preset chips 1× 5× 10× 20× 50× 100×, selected20x, confirm and cancel. Max leverage comes from symbol config; label 最高 100×（随品种变化）. Keep main order form uncluttered and realistic. Bottom caption 仓位比例与手数双向同步，预计强平价随账户状态重算. This is a proposed adaptation inspired by OKX/Binance leverage entry buttons, not a screenshot or clone. Title A · 顶部倍数按钮. Subtitle 下单区顶部设置，点击后底部弹出. On left resting form a compact rounded outlined button 杠杆 20× ⌄ at top right of 市价 / 限价 order type row. Highlight only this button via subtle lime outline and leader callout 杠杆入口. Right phone shows same form dimmed behind a bottom sheet titled 调整杠杆 with drag handle, X close, big 20× input plus minus, two rows presets, range helper, small preview 当前仓位比例 50%, 预计手数 2.00 手 and green 确认 button. No margin mode selector. Recommend badge 推荐 next to title.

### B

Use case: ui-mockup. Create a polished high fidelity Chinese trading interface UX proposal, landscape 1536x1024. Existing product uses white background, very light gray input fields, charcoal text, lime green #8cc63f main accents and green/red buy sell buttons. Crisp readable Simplified Chinese typography, no device perspective, no decoration, no brand logos. Two side-by-side large mobile order panel states on an off-white presentation canvas: left resting order form and right settings opened. Tiny annotation between them shows exactly which button opens settings. Show BTC/USDT header, 合约交易, 市价 / 限价, available balance 可用 10,000.00 USDT, 手数 with minus plus input 2.00 手, position allocation slider labeled 仓位比例 with selected 50% and tick labels 0% 25% 50% 75% 100%, estimated margin 5,000.00 USDT, paired rows 预计强平价（买入）45,000.00 and 预计强平价（卖出）55,000.00, bottom 买入 / 卖出 buttons, small note 示例数据，仅展示交互. Price example 50,000, 1 lot equals 1 BTC, leverage20x, fee omitted illustrative. The ONLY slider is position allocation. Never show ANY leverage slider anywhere. Leverage settings use large minus / editable 20× / plus, preset chips 1× 5× 10× 20× 50× 100×, selected20x, confirm and cancel. Max leverage comes from symbol config; label 最高 100×（随品种变化）. Keep main order form uncluttered and realistic. Bottom caption 仓位比例与手数双向同步，预计强平价随账户状态重算. This is a proposed adaptation inspired by OKX/Binance leverage entry buttons, not a screenshot or clone. Title B · 手数旁快捷设置. Subtitle 数量与杠杆集中操作，点击展开浮层. On left the outlined 杠杆 20× ⌄ button is aligned right on same label row as 手数 directly above its quantity input, NOT in header. Highlight and annotate 杠杆入口. Right phone shows anchored white floating popover directly below that button over part of order form with a triangle anchor, subtle shadow, title 调整杠杆, compact minus20×plus input, preset chips, range helper and 取消 / 确认 action row. The popover is NOT a bottom sheet nor centered modal. Underlying page is visible without dark full-screen dimming. Position slider remains visible below popover.

### C

Use case: ui-mockup. Create a polished high fidelity Chinese trading interface UX proposal, landscape 1536x1024. Existing product uses white background, very light gray input fields, charcoal text, lime green #8cc63f main accents and green/red buy sell buttons. Crisp readable Simplified Chinese typography, no device perspective, no decoration, no brand logos. Two side-by-side large mobile order panel states on an off-white presentation canvas: left resting order form and right settings opened. Tiny annotation between them shows exactly which button opens settings. Show BTC/USDT header, 合约交易, 市价 / 限价, available balance 可用 10,000.00 USDT, 手数 with minus plus input 2.00 手, position allocation slider labeled 仓位比例 with selected 50% and tick labels 0% 25% 50% 75% 100%, estimated margin 5,000.00 USDT, paired rows 预计强平价（买入）45,000.00 and 预计强平价（卖出）55,000.00, bottom 买入 / 卖出 buttons, small note 示例数据，仅展示交互. Price example 50,000, 1 lot equals 1 BTC, leverage20x, fee omitted illustrative. The ONLY slider is position allocation. Never show ANY leverage slider anywhere. Leverage settings use large minus / editable 20× / plus, preset chips 1× 5× 10× 20× 50× 100×, selected20x, confirm and cancel. Max leverage comes from symbol config; label 最高 100×（随品种变化）. Keep main order form uncluttered and realistic. Bottom caption 仓位比例与手数双向同步，预计强平价随账户状态重算. This is a proposed adaptation inspired by OKX/Binance leverage entry buttons, not a screenshot or clone. Title C · 独立杠杆设置行. Subtitle 信息更明确，点击打开确认弹窗. On left above 手数 input there is a full width soft gray settings row 杠杆设置 on left and 20×  修改 › on right; no leverage slider. Annotate right side 杠杆入口. Right phone has dimmed order form behind vertically centered rounded white dialog 调整杠杆 with close X, minus20×plus input, presets grid, range helper, preview 当前仓位比例 50% / 预计手数 2.00 手, outlined 取消 and solid green 确认 buttons. This is a centered dialog, not bottom sheet. Overall generous whitespace and clear hierarchy.
