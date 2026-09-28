# 手动合约订单分钟选择器

- 使用现有 Element Plus 日期选择器和弹窗，不新增 UI 依赖。
- 日期、小时、分钟属于草稿；只有“确定时间”才写入订单表单。取消、关闭不会修改订单时间。
- 选中分钟即显示该分钟 K 线 open；确认后，时间右侧保留该价格。开仓和平仓都采用分钟 open。
- 仅提供数据源返回的有效分钟，剔除空值、非正价格、非整分钟、未来及所选日期之外的数据。不用最近价格填空。
- 这不是完整交易所日历：日期仍可浏览，无有效行情的日期不能确认分钟；接口失败与休市不混为一谈。上游历史保留限制仍然存在。
- 按所选显示时区确定日期边界，UTC 时间戳作为选项标识。夏令时重复分钟显示不同偏移，确认时自动携带偏移。
- 新增受原超级管理员权限与功能开关保护的 GET `/api/admin/orders/contract/manual/minutes`，参数为 symbol/date/timezone。
- 每个本地日读取相交的 UTC 半日窗口（每窗最多 720 条），与订单预览复用历史行情缓存。不改真实交易执行逻辑。
- 加载时每两秒重查，最多重试 15 次；换日期、品种、时区或关闭弹窗使旧响应失效。停止等待后允许手动重试。
- 价格选择不代表订单预览一定成功：历史换算率、余额、时间顺序等仍由服务端独立校验。

## 参考

- https://element-plus.org/zh-CN/component/datetime-picker
- https://element-plus.org/en-US/component/date-picker
- https://ant.design/components/date-picker/

Element Plus 原生 change 事件也可能因点击外部触发，因此增加独立确认层，而不是把 change 当作用户确定。

## 验证与回滚

- 管理端：`npm run build`。
- 后端：`mvn -q -Dtest=ManualOrderMinutesTest,ManualOrderCalculationTest test`。
- 新测试覆盖有效分钟过滤、秒/毫秒时间戳、未来时间、空数据、时区跨日、夏令时重复分钟与偏移往返。
- 原文件备份目录：`C:/workspace/fx/705/rollback/manual-minute-picker-20260928`。其中 `ManualContractOrder.concurrent.vue` 保留开发期间外部写入的余额标签及杠杆修改；回滚时按差异恢复，不覆盖其他人的改动。
- 尚未进行真实行情接口端到端和浏览器交互验收，未部署。
