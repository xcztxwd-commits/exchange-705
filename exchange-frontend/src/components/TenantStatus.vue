<script setup lang="ts">
import { computed } from 'vue'
import { useLocaleStore } from '@/store/locale'
import { tenantFeatures, featuresLoading, featuresError, refreshTenantFeatures, canStartBusiness } from '@/utils/tenantFeatures'
const locale = useLocaleStore()
const text = (zh: string, en: string) => locale.text(zh, en)
const labels: [string, string, string][] = [ ['registration','注册','Registration'], ['contract','合约开仓','New contracts'], ['option','期权下单','New options'], ['financial','理财申购','New investments'], ['loan','贷款申请','New loans'], ['deposit','入金申请','Deposits'], ['simulation','进入模拟账户','Enter demo'] ]
const unavailable = computed(() => labels.filter(([key]) => !canStartBusiness(key)).map(([,zh,en]) => text(zh,en)))
</script>
<template>
  <aside class="tenant-status" :class="{ restricted: !tenantFeatures?.acceptNewBusiness }" aria-live="polite">
    <strong>{{ tenantFeatures?.tenantName || text('租户状态', 'Tenant status') }}</strong>
    <span>{{ featuresLoading ? text('正在核验功能…', 'Checking capabilities…') : featuresError ? text('暂不能核验功能，新增业务入口已关闭', 'Capabilities unavailable. New actions are hidden.') : tenantFeatures?.status }}</span>
    <details v-if="unavailable.length && !featuresLoading"><summary>{{ text('新增业务限制', 'New business restrictions') }}</summary><p>{{ unavailable.join(' / ') }}</p><p>{{ text('历史、平仓、撤单、赎回、还款及资金退出入口保留；最终以服务端权限为准。', 'History, close, cancel, redeem, repay and funds exits remain available; server permissions apply.') }}</p></details>
    <button type="button" :disabled="featuresLoading" @click="refreshTenantFeatures(true)">{{ text('刷新', 'Refresh') }}</button>
  </aside>
</template>
<style scoped>
.tenant-status{position:relative;z-index:4001;display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding:6px 14px;background:#edf5ef;color:#244630;font:12px/1.5 system-ui,sans-serif;border-bottom:1px solid #c7d8cb}.tenant-status.restricted{background:#fff4e0;color:#704400}.tenant-status button{margin-left:auto;padding:4px 9px;border:1px solid currentColor;border-radius:4px;background:transparent;color:inherit;cursor:pointer}.tenant-status button:focus-visible{outline:2px solid currentColor;outline-offset:2px}.tenant-status summary{cursor:pointer}.tenant-status p{margin:4px 0}
</style>
