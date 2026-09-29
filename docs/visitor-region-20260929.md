# 交易页精简与访问者地区默认值

## 变更

- 移动交易页移除报价状态行、紧凑图表诊断页脚、市价说明、底部重复报价提示。报价失效仍禁止下单，图表加载失败仍提供重试按钮；未移除交易校验。
- 报价时间与价格下方的小字同排，显示为 `JST 2:53:56 pm` 等格式。美洲时区缩写随时间戳自动处理夏令时。
- 手机端和 PC 客户端启动时共享一次访问者 IP 查询结果。全站日期工具、订单详情、分享图、图表及资产时间显示使用该时区。后台记账、结算时区和服务器时间不改。
- 根据 IP 国家选择项目已有语言；多语言国家使用默认语言，不支持的语言回退英语（现有中文资源为繁体）。已有手动语言选择和图表时区选择优先，自动识别结果不写入手动偏好。
- IP 查询失败、被拦截或超时（2.5 秒）时，回退设备时区和浏览器支持语言，不阻止启动。

## 外部服务

通过浏览器直接调用 [IPWhois 的 HTTPS 接口](https://ipwhois.io/documentation)，避免将代理/服务器 IP 当作访客 IP。仅请求国家和时区，不附带账户、令牌、Cookie 或 Referer。服务提供方会看到发起请求的公网 IP。免费服务有配额及可用性限制；超限走上述回退逻辑。生产高流量需评估配额或替换为自有地区服务。

## 验证

```powershell
node --test exchange-frontend/tests/visitorRegion.test.mjs exchange-frontend/tests/assetPixelScrub.test.mjs exchange-frontend/tests/assetPixelWindow.test.mjs exchange-frontend/tests/orderView.test.mjs exchange-pc/tests/chartPreferences.test.mjs
node exchange-frontend/tests/visitorRegion.browser.cjs
npm --prefix exchange-frontend run build
npm --prefix exchange-pc run build
```

浏览器用例通过拦截地区接口确定性验证手机/PC 的 IP 默认值、手动覆盖、离线回退共六种场景；模拟东京 IP、巴黎设备时区及冲突的后台时区，以确认优先级。手机同时检查 320/390/666px 无横向溢出、时间同排、红框内容移除及过期报价仍禁止下单。截图和结果位于 `reports/visitor-region-20260929/`，并非真实行情证据。

本次改写前文件备份在 `rollback/visitor-region-20260929/`，保留任务开始时已有的未提交修改。只需恢复对应文件；新增地区工具和测试按需移除。未执行部署。
