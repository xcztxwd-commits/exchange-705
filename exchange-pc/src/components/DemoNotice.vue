<template>
  <aside class="demo-notice" :lang="language" role="note">
    <strong>{{ copy.title }}</strong>
    <span>{{ copy.summary }} {{ copy.details }} {{ copy.funds }}</span>
  </aside>
  <slot />
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

type Language = 'zh' | 'zh-TW' | 'en' | 'ja'
const browserLanguage = navigator.language.toLowerCase()
const language = ref<Language>(browserLanguage.startsWith('ja') ? 'ja'
  : /zh-(tw|hk|mo|hant)/.test(browserLanguage) ? 'zh-TW'
  : browserLanguage.startsWith('zh') ? 'zh' : 'en')
const messages = {
  zh: {
    title: '模拟演示提示',
    summary: '本站仅用于模拟测试与功能演示，不提供真实交易，也不会向真实市场执行订单。',
    details: '价格、订单、余额及盈亏可能是模拟数据，或由管理员设定、调整，不能作为真实交易或投资回报的依据。',
    funds: '请勿向本站或页面展示的账户、地址转入真实资金或加密货币，也不要提交真实身份证件。充值、提现及身份认证功能仅供演示，请只使用测试数据。',
  },
  'zh-TW': {
    title: '模擬演示提示',
    summary: '本站僅用於模擬測試與功能演示，不提供真實交易，也不會向真實市場執行訂單。',
    details: '價格、訂單、餘額及盈虧可能是模擬資料，或由管理員設定、調整，不能作為真實交易或投資報酬的依據。',
    funds: '請勿向本站或頁面顯示的帳戶、地址轉入真實資金或加密貨幣，也不要提交真實身分證件。儲值、提領及身分驗證功能僅供演示，請只使用測試資料。',
  },
  en: {
    title: 'Simulation demo notice',
    summary: 'This site is for simulation testing and feature demonstrations only. It does not provide real trading or execute orders in real markets.',
    details: 'Prices, orders, balances, and profits or losses may be simulated or set and adjusted by administrators. They do not represent real trades or investment returns.',
    funds: 'Do not send real money or cryptocurrency to this site or any account or address shown here. Do not submit real identity documents. Deposit, withdrawal, and identity verification features are demonstrations; use test data only.',
  },
  ja: {
    title: 'シミュレーションのご案内',
    summary: '本サイトはシミュレーションテストと機能のデモ専用です。実際の取引を提供せず、実際の市場に注文を発注することもありません。',
    details: '価格、注文、残高、損益は模擬データ、または管理者が設定・変更した値の場合があります。実際の取引や投資収益を示すものではありません。',
    funds: '本サイトや表示された口座・アドレスに実際の資金や暗号資産を送らないでください。実際の本人確認書類も提出しないでください。入出金・本人確認機能はデモ専用です。テストデータのみを使用してください。',
  },
}
const copy = computed(() => messages[language.value])
</script>

<style scoped>
.demo-notice {
  box-sizing: border-box;
  width: 100%;
  padding: 10px 16px;
  border-bottom: 1px solid #e9cb78;
  background: #fff3cd;
  color: #4f3500;
  font: 13px/1.5 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
}
.demo-notice strong { margin-right: 8px; }
</style>
