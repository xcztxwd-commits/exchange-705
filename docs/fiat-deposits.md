# 多币种充值与资产展示

支持 USD、EUR、JPY、GBP、AUD、CAD、SGD、CNY，默认 USD。资产账户始终以 USD 保存；显示金额为 USD 余额除以所选币种的 `quoteToUsdRate`。可用余额、冻结金额和总资产使用同一汇率。

用户充值通过 `/api/deposit/submit` 传入 `currency` 和原币金额 `amount`。服务端读取现有 Redis 结算汇率，保存原币种、原金额、汇率快照及折算后的 USD 金额。审核继续使用已保存的 USD 金额，不再次换汇。缺省币种及历史记录维持原来的 USD 语义。汇率缺失、过期或 Redis 不可用时，外币充值被拒绝；USD 无须外汇汇率。

后台用户余额弹窗增加“充值”模式，选择账户、币种和充值金额后，将折算的 USD 累加至所选账户。原“设置余额（USD）”仍用于直接修改最终余额。`/api/admin/users/updateBalance` 新增的充值参数为 `account`（FUND/CONTRACT/OPTION）、`currency`、`amount`；省略 `amount` 保持原设置余额行为。

`/api/market/currencies` 提供汇率及到期时间。界面预估仅供参考，入账以服务端提交时的缓存汇率为准。资产显示切换不写入账户。后台充值和用户充值使用同一汇率服务。

数据库新增 `deposit_record.currency`、`original_amount`、`exchange_rate` 三个可空字段。当前 `ddl-auto=update` 会自动创建；关闭自动建表的部署需执行 `exchange-backend/src/main/resources/db/migration/add_deposit_currency.sql`，已添加字段后不要重复执行。

验证：使用 JDK 8 执行 `mvn -Dtest=FiatDepositTest,QuoteCurrencyConversionTest test`；三个前端分别执行 `npm run build`。隔离浏览器模拟接口验证桌面端、移动端和后台的八币种选择、金额换算、充值提交及不可用汇率拦截，未操作真实用户资金。

## 银行卡提现

银行卡提现支持相同的八种输入币种，默认 USD。输入币种与绑定银行卡独立选择。`/api/withdraw/submit`、`/api/withdraw/calculate` 的 `currency` 表示输入币种；缺省仍按 USD 输入，`network` 保留收款账户信息。

提交时按 Redis 汇率将 `amount` 折算 USD，用 USD 校验余额、计算手续费和冻结资金。提现记录中的 `amount`、`fee`、新记录的 `actualAmount` 均为 USD；原币种、原金额和汇率分别存入 `currency`、`original_amount`、`exchange_rate`。审核完成扣除已冻结 USD，驳回按相同 USD 金额解冻，不重新换汇。旧记录不改变其原有到账金额及币种解释。

已移除银行卡提现对 apiforex.cn 的调用及汇率失败回退 1:1 的行为。生产关闭自动建表时，需执行 `add_withdraw_currency.sql` 添加三个可空字段。`FiatWithdrawTest` 验证预估、冻结、完成、退款、防重复处理、余额不足和汇率失效。
