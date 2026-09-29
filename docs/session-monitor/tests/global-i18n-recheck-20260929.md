# 全局 i18n 复检与修复

日期：2026-09-29（Asia/Singapore）。本轮完整执行现有翻译检查，最终退出码 0。

## 范围与结果

仅修改两份 UI 翻译字典与两个翻译检查脚本。未修改交易、资金、贷款还款、合同定义、后端、客服或权限逻辑。未部署、未提交、未操作 Git 索引。保留原有未提交改动。

扫描双端所有 src 下 .vue/.ts，依现有检查器的单引号 text(中文, 英文) 静态调用规则，去重发现 10 个缺失键；全部有明确调用语义，双端各补齐 10 项日语翻译。逐项移除本轮新增行后，与备份字典字节文本完全一致。

| 英文键 | 新增日语 |
|---|---|
| 1W | 1週間 |
| 1MO | 1か月 |
| Quantity | 数量 |
| Open / close each | 新規建て・決済それぞれ |
| Round-trip commission reserved; settled on close, refunded on cancellation | 往復手数料を確保し、決済時に精算します。注文取消時には返還します |
| Reserved round-trip fee | 確保済み往復手数料 |
| Per input unit | 入力単位あたり |
| Round-trip reserved; settled on close, refunded on cancellation | 往復手数料を確保し、決済時に精算します。注文取消時には返還します |
| Your approved identity is reused. Add loan contact details and a photo holding your ID. | 承認済みの本人確認情報を再利用します。借入の連絡先情報と証明書を持った写真を追加してください。 |
| Previous details are incomplete or do not match your identity. Please resubmit. | 以前の資料に不足があるか、本人確認情報と一致していません。再提出してください。 |

周期来源为移动端 Trade.vue 和 PC DesktopTrade.vue 的 1w/1M 图表周期；1MO 表示一个月，不是分钟或一月月份名称。Quantity 保持中性的“数量”，没有定义为手数。Per input unit 对应界面已有单位换算标签，没有更改倍率。

手数及往返佣金提示按调用处中英原文翻译，仅表达原文已规定的预留、平仓结算、撤单退还，没有新增费用规则。两条身份资料提示仅涉及已审核身份复用、资料补充与重新提交，没有改贷款合同或还款语义。本轮无必须新增业务决定的词条；这些翻译不构成对产品计算规则或合同的审定。

## 检查器修复

1. 缺项检查改为先汇总双端全部缺失静态词条，再一次断言，不再在第一项处停止扫描。
2. 补齐词条后暴露原检查器的运行环境依赖：Node 24 继承主机中文 navigator.language，非法持久化语言回退得到 zh-TW，原测试却无浏览器夹具地断言 en。固定测试 navigator.language=en-US，保留原英文断言；另增加 zh-CN 回退 zh-TW 的断言。没有修改生产语言选择逻辑。
3. 定向测试新增十个词条的实际 locale store 日语解析与英文回退断言，双端执行，连同前轮数量错误提示合计 44 个翻译值断言。

## 命令、退出码与证据

工作目录 C:/workspace/fx/705。备份与独立日志目录：C:/Users/徐乾妖/AppData/Local/Temp/global-i18n-20260929-102559。

| 命令/阶段 | 退出码 | 结果与日志 |
|---|---:|---|
| node scripts/check-i18n.cjs（修改前） | 1 | 1W 缺项；global-before.log |
| node scripts/check-i18n.cjs（补齐十项后） | 1 | 主机语言依赖 zh-TW !== en；global-after-keys.log |
| node scripts/check-i18n.cjs（最终） | 0 | 双端 19 语言，18905/18924 字典查询，各 424 日语 UI 项；global-final.log |
| node --test scripts/check-trade-i18n.cjs | 0 | 3/3 通过，0 失败，0 跳过；focused-final.log |
| node reports/full-simulation-20260929/runtime-compat/check-simulation-i18n.cjs | 0 | 模拟账户日语键 26/26；simulation-final.log |
| node --test exchange-frontend/tests/visitorRegion.test.mjs | 0 | 双端地域与语言默认值测试 2/2；visitor-region.log |
| node exchange-frontend/node_modules/typescript/bin/tsc --noEmit --skipLibCheck --target ES2022 --module ESNext exchange-frontend/src/store/uiMessages.ts | 0 | exchange-frontend-types.log |
| node exchange-pc/node_modules/typescript/bin/tsc --noEmit --skipLibCheck --target ES2022 --module ESNext exchange-pc/src/store/uiMessages.ts | 0 | exchange-pc-types.log |

使用 noEmit 类型检查，不产生共享 target/dist，不重复全量构建。git diff --no-index 仅用于比较备份与修改后检查器，退出码 1 表示存在预期差异，不是测试失败。

## 版本 SHA-256

| 文件 | 修改前 | 修改后 |
|---|---|---|
| C:/workspace/fx/705/exchange-frontend/src/store/uiMessages.ts | 29353EF46D97B579AA1BACB6EFBE805E63195B3B9187C1A33CEB4A2BF7FF3B20 | F00631B1570B5B666F79818C3E9F5B452C0B2029D559B2A9DDFAA8A099B2970C |
| C:/workspace/fx/705/exchange-pc/src/store/uiMessages.ts | 29353EF46D97B579AA1BACB6EFBE805E63195B3B9187C1A33CEB4A2BF7FF3B20 | F00631B1570B5B666F79818C3E9F5B452C0B2029D559B2A9DDFAA8A099B2970C |
| C:/workspace/fx/705/scripts/check-i18n.cjs | 55AA36D6889D403791393BC447895B8D29057D6DD031541E18124FCE964A4F8C | DA328C1243B38FA166E13498482A153914F32453D8B915FA11C2B45331365A49 |
| C:/workspace/fx/705/scripts/check-trade-i18n.cjs | 2BD93CA99440DF7D0F92D47D58DBACAAD5F1467D393F96B9DB2F82ADC09EE88E | 1057E7A1CC1652461DD408A7CD4A745D805FED0D785FD845ADEC1D0817719E26 |

修改前备份全部四个文件，写入时核对原哈希并使用临时文件替换。恢复必须先核对是否有其他任务后续改动，不应整文件覆盖并发修改。

## 未验证范围

完整检查通过指现有脚本覆盖范围：静态单引号调用、字典结构、插值、别名、日语解析、语言切换及已有双端一致性断言。不是全部 19 语言人工语言质量认证，也不覆盖动态拼接键、双引号或模板字符串的所有调用形式。本轮未做浏览器视觉验证、完整应用构建、交易后端/生产回归。当前无该检查器剩余失败；未来新代码新增词条需重新运行。
