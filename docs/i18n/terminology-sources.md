# 多语言术语依据与选择记录

核对日期：2026-09-27。范围：PC 与移动端前台源码；不是对交易资质、产品合法性或收益的认可。

## 证据等级

1. **机构术语参考**：交易所、监管机构或当地交易服务商的本国语言资料。证明该机构在该产品场景中使用该词，不证明所有机构均使用同一译法。
2. **业务语义核对**：本项目字段、计算公式、订单生命周期与该术语相符。
3. **编辑翻译**：按钮、错误提示、规则说明依本项目语义翻译，不是摘抄或声称“官方认证译文”。
4. **待确认**：产品定位、法律文本、动态内容或母语质量尚无充分证据。保留为待办，不能用“词典键齐全”代替审核通过。

不能穷尽全网，也不存在覆盖全部交易所、所有语言及自定义产品的唯一官方译文。本站 UI 不是任何参考机构的官方系统。

## 日语：按功能选择，不按中文字面替换

|项目含义|采用用语|不采用/区别|依据|
|---|---|---|---|
|不指定成交价格的订单类型|成行 / 成行注文|市場価格仅表示市场价格，不是订单类型|[JPX 注文类型](https://www.jpx.co.jp/derivatives/rules/order-types/01.html)、[股票成交制度](https://www.jpx.co.jp/equities/trading/domestic/04.html)|
|买入不高于指定价、卖出不低于指定价|指値 / 指値注文|価格制限不是订单类型；也不能混同逆指値|[JPX 指値注文](https://www.jpx.co.jp/glossary/sa/174.html)、[外為どっとコム 指値](https://www.gaitame.com/seminar/glossary/priceorder.html)|
|尚未成交的委托|未約定注文|不等于已有建玉|[JPX 用语集](https://www.jpx.co.jp/glossary/all/)、[SBI 客户端手册](https://www.sbifxt.co.jp/manual/pdf/smtnext.pdf)|
|已成交且未平仓的头寸|保有ポジション / 建玉|“契約”通常是合同，不适合作为整个保证金交易模块标题|[JPX 建玉](https://www.jpx.co.jp/glossary/ta/284.html)|
|头寸开仓价格、开仓时间|新規約定価格、新規約定日時|始値属于一个行情周期的第一笔价格；開館時間是场馆开门时间|[JPX 用语集](https://www.jpx.co.jp/glossary/all/)、[SBI 客户端手册](https://www.sbifxt.co.jp/manual/pdf/smtnext.pdf)|
|平仓、平仓时间|決済、決済日時|閉店時間是商店关门时间|同上；订单生命周期与 ContractOrderService 对照|
|K 线 OHLC|始値、高値、安値、終値|不能因为修复开仓价而把图表始値一并替换|[乐天证券图表基础](https://www.rakuten-sec.co.jp/web/fx/movie/chart/pdf/basic-movie.pdf)|
|权益 / 使用保证金 × 100|証拠金維持率|不是風險率；也不同于保证金比例或杠杆倍数|[SBI FX 计算公式](https://faq.sbifxt.co.jp/answer/684b8112690d550ab8ad35e2)、[OANDA 说明](https://www.oanda.jp/lab-education/dictionary/margin-maintenance-ratio/)|
|未实现损益 / 已实现损益|評価損益 / 確定損益|不擅自加入本系统未计算的 swap 收益|[SBI 客户端手册](https://www.sbifxt.co.jp/manual/pdf/smtnext.pdf)|
|止盈、止损设置|利確、損切り；价格加「価格」|不把用户自行设置的止损与强制ロスカット混为一谈|[外為どっとコム交易手册](https://www.gaitame.com/service/assets/fx/guide/gfx/03_trade.pdf)|
|法币入账、提取、账户间转移|入金、出金、振替|退会是注销账户，不能表示出金|[GMO Coin 入出金/振替](https://coin.z.com/jp/corp/guide/deposit-withdrawal/)、[bitFlyer 入出金帮助](https://bitflyer.com/ja-jp/FAQ/deposit)|
|加密资产|暗号資産|避免同一界面交替使用デジタル通貨、仮想通貨造成不必要差异|[Coincheck API](https://coincheck.com/ja/documents/exchange/api)、GMO Coin 同上|
|接收/发送链上资产|暗号資産の入金/出金（本站界面约定）|部分机构使用預入/送付；均须与网络、地址和数量场景对应，不是银行振込|GMO Coin 同上；本项目 Deposit/Withdraw 的网络字段|
|银行汇款资料（银行名、SWIFT、收款账户）|銀行口座、口座番号、口座名義|不是信用卡号；后台 UserBankCard 实际保存 recipientAccount|[bitFlyer 入出金帮助](https://bitflyer.com/ja-jp/FAQ/deposit)；本项目实体字段|
|日收益金额 / 日收益百分比|予想日次収益額 / 予想日次収益率|不能把两种量纲都叫“日产”，更不能翻成汽车品牌 Nissan|DesktopTrade 的 dailyYield 与 dailyYieldRate 字段核对，编辑翻译|
|利息、借款天数|利息、日|興味是兴趣；神様不是时间单位|语义纠错；借款合同整体仍待法务和母语复核|
|有限期限内预测涨跌的本站产品|期限取引、上昇を予想、下落を予想|**描述性临时名称，不声称日本交易所官方产品名**；不把买跌写成证券卖出/做空|本项目 OptionOrderService；与 [GMO 外為オプション规则](https://www.click-sec.com/corp/guide/fxop/rule/)对比后，确认规则不同，不能照搬|
|FX 利差与加密永续资金费用|分别核对，不互换|スワップポイント与 funding 机制不同，本站未确认字段不能擅改为资金费率|[SBI FX 入门](https://www.sbifxt.co.jp/beginner/step02.html)、[Binance 官方 funding 说明](https://www.binance.com/en/support/faq/detail/360033525031)|

### 技术指标

参考 [乐天证券技术图表](https://www.rakuten-sec.co.jp/MarketSpeed/onLineHelp/msman1_7_3.html) 与 [图表操作说明](https://www.rakuten-sec.co.jp/MarketSpeed/onLineHelp/msman1_10_5.html)：移動平均線、指数平滑移動平均線、ボリンジャーバンド、出来高、RSI、MACD 等。

本项目 KLineCharts 的 `SMA` 参数为 `[12, 2]`，源目录将其定义为 **Smoothed moving average**。不能看到缩写就译成通常表示 Simple Moving Average 的「単純移動平均」。使用「平滑移動平均線」，保留指标代码。BBI、BRAR、CR 等未找到足以证明日本本土交易平台统一采用某一日语全称的资料，现保留缩写与英文描述，不自行创造日语名称。TRIX、PVT 及 SMA 的后续显示说明见 [术语保留复核](ja-terminology-restraint.md)。

## 其他语言：已找到的机构资料

以下资料是**术语参考**，不代表该语言所有 995 个字段已得到逐项官方背书。各类账户、条件单、杠杆与保证金制度必须按本项目规则使用。

|语言|参考|当前证据边界|
|---|---|---|
|繁体中文|[TWSE 逐笔交易资料](https://www.twse.com.tw/staticFiles/product/broker/ff80808163907aaf0163b451c5db00ab.pdf)|市价、限价及委托语义；不用于证明本站为交易所|
|英语|[Binance 官方永续资金费用](https://www.binance.com/en/support/faq/detail/360033525031)、[YSX 交易业务手册](https://ysx-mm.com/wp-content/uploads/2016/03/om01_en_032016_01.pdf)|不同产品机制分开|
|法语|[IG ordre limite](https://www.ig.com/fr/glossaire-trading/ordre-limite-definition)|市价与限价订单|
|德语|[IG Market-/Limit-Order](https://www.ig.com/de/trading-strategien/was-ist-der-unterschied-zwischen-market-und-limit-order--230821)、[Margin Calls](https://www.ig.com/de/margin-calls)|订单与保证金；UI 可使用常见英文借词|
|俄语|[Binance 限价订单](https://www.binance.com/ru/academy/articles/what-is-a-limit-order)|交易所官方教育资料，不采用 Square 用户帖子|
|西班牙语|[OANDA 保证金](https://www.oanda.com/bvi-es/cfds/spreads-margin/)|地区与产品差异仍需核对|
|葡萄牙语|[OANDA 保证金](https://www.oanda.com/bvi-pt/cfds/spreads-margin/)|已修复西/葡混译；当前项目只有 pt，没有区分 pt-PT/pt-BR|
|意大利语|[Trading.com 订单执行政策](https://www.trading.com/eu/pubapi/marketing/v1/cms/media/order-execution-policy?brandName=EU&languageCode=it)|订单执行术语；未取得完整 UI 词库|
|阿拉伯语|[沙特金融学院教材](https://cdn.fa.gov.sa/facdn-container/FAST/production/Docs/ar/services/Documents/CME3_ar.pdf)|金融术语参考；还需 RTL 逐页面视觉审核|
|土耳其语|[Binance TR 官方说明](https://www.binance.tr/tr/blog/Yasal%20Bildirimler/f13e8e6343924208b9b52c0b56451672)|Limit Emri / Piyasa Emri|
|印尼语|[Panin 证券移动端操作手册](https://pans.co.id/upload/document/Manual-Book-POST-mobile-4-Android.pdf)|券商本国语言界面；与 FX/加密产品边界区分|
|缅甸语|[YSX 官方规则（英语）](https://ysx-mm.com/wp-content/uploads/2018/03/ysxr01_en_30032018_02.pdf)|**未找到完整、可靠的缅甸语专业对照资料。缅甸语修改为编辑译稿，必须母语复核**|
|印地语|[NSE 印地语金属产品资料](https://nsearchives.nseindia.com/web/sites/default/files/inline-files/Hindi-Base%20Metal%20Brochure.pdf)|不覆盖全部 FX/加密及界面句子，剩余需复核|
|捷克语|[XTB 订单执行规则](https://www.xtb.com/cz/CZ_Pravidla_provadeni_pokynu.pdf)|交易服务商术语|
|波兰语|[XTB 待执行订单](https://www.xtb.com/pl/centrum-pomocy/realizacja-zlecen/rodzaje-zlecen-oczekujacych-w-xtb)|区分 limit、stop 与市价，不复制产品触发规则|
|韩语|[Kiwoom 证券交易指南](https://ocrw.kiwoom.com/pdf/guide/Kiwoom_Securities_Alternative_Exchange_Guide_Booklet_Double-Sided.pdf)|시장가、지정가、미체결；不声称覆盖所有币圈术语|
|泰语|[SET 订单类型](https://www.set.or.th/en/market/information/trading-procedure/order-types)|本次保留的是官方英语页；泰语逐词官方出处不足，需补证|
|越南语|[TCBS 保证金支持](https://help.tcbs.com.vn/vay-ky-quy-margin/)|ký quỹ 的本土语境；尚非完整交易 UI 词典|

## 不应擅自“标准化”的内容

- 自定义期限交易的名称、同价判负和报价缺失延迟结算：按当前代码翻译；必须由产品负责人确认产品定义，不假装成标准期货/期权。
- 借款合同含“出金前须偿还”“不能直接从账户扣款”，而提前还款提示又说明自动扣款。存在原文业务矛盾；翻译无法替代产品/法务判断。本次未自行改动权利义务。
- 动态公告、后台商品名与商品说明没有全语种内容时，不能凭前端字典声称已完成本地化。不能改写客户姓名、钱包地址、交易代码或用户输入。
- 术语参考不是母语用户可用性测试。全部语言“毫无歧义”的承诺无法由自动检查给出。
