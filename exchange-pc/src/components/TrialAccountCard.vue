<script setup lang="ts">
import { useTrialWallet } from '@/utils/useTrialWallet'
import { useLocaleStore } from '@/store/locale'
import { useActivityCopy, money } from '@/utils/activity'
const props=withDefaults(defineProps<{ visible?: boolean }>(),{visible:true})
const wallet=useTrialWallet(), locale=useLocaleStore(), t=useActivityCopy()
function inbox(){window.dispatchEvent(new Event('activity-inbox'))}
</script>
<template>
 <section v-if="wallet.state.eligible" class="trial-account-card" :aria-label="t(6)" data-testid="trial-card">
  <header><span class="trial-symbol" aria-hidden="true">✦</span><div><h3>{{ t(6) }}</h3><small>{{ locale.text('体验金', 'Trial credit') }} · U</small></div><button @click="inbox">{{ t(20) }} <span aria-hidden="true">›</span></button></header>
  <div class="trial-balances"><div><small>{{ t(7) }}</small><strong>{{ props.visible ? money(wallet.state.available) : '****' }} <em>U</em></strong></div><div><small>{{ t(8) }}</small><b>{{ props.visible ? money(wallet.state.frozen) : '****' }} U</b></div></div>
  <p v-if="wallet.state.expiresAt != null" class="trial-countdown" data-testid="trial-countdown">{{ locale.text('剩余有效时间', 'Time remaining') }} <time>{{ wallet.remaining }}</time></p>
  <p>{{ t(12) }}</p>
 </section>
</template>
<style scoped>
.trial-countdown{font-variant-numeric:tabular-nums;margin-bottom:12px!important;color:#638c3d!important;font-size:12px!important}.trial-account-card{margin:16px 0;padding:20px;border:1px solid #dfe9d3;border-radius:14px;background:linear-gradient(120deg,#f9fcf4,#fff);color:#354629}.trial-account-card header{display:flex;align-items:center;gap:10px}.trial-symbol{background:#eaf3da;color:#739835;padding:4px 10px;border-radius:9px;font-size:22px}.trial-account-card h3{font-size:15px;margin:0;font-weight:650}.trial-account-card small{font-size:10px;color:#859377}.trial-account-card button{margin-left:auto;font-size:11px;border:0;background:none;color:#648936;cursor:pointer;min-height:36px}.trial-balances{display:flex;justify-content:space-between;align-items:center;margin:20px 0 12px;gap:15px}.trial-balances small,.trial-balances strong,.trial-balances b{display:block}.trial-balances strong{font-size:29px;letter-spacing:-1px;margin-top:2px}.trial-balances em{font-size:12px;font-style:normal;font-weight:400}.trial-balances b{font-size:14px;margin-top:8px}.trial-account-card p{margin:0;font-size:10px;line-height:1.7;color:#87917e}.trial-earned{margin-bottom:12px!important}.trial-earned b{color:#638c3d;margin-left:8px}
</style>
