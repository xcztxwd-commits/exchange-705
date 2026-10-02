<script setup lang="ts">
import { ref } from 'vue'
import { canStartBusiness } from '@/utils/tenantFeatures'
import { simulationSessionMatches } from '../../../exchange-frontend/src/utils/tenantCapabilities'
import { useLocaleStore } from '@/store/locale'
import { accountMode, accountModeKey, demoApiBase, setAccountMode } from '@/utils/accountMode'
withDefaults(defineProps<{ realPath: string; placement?: 'bar' | 'menu' }>(), { placement: 'bar' })
const demo = accountMode() === 'DEMO', busy = ref(false), error = ref('')
const locale = useLocaleStore()
const text = (zh: string, en: string) => locale.text(zh, en)
async function switchAccount() {
  if (busy.value || (!demo && !canStartBusiness('simulation'))) return
  busy.value = true; error.value = ''
  try {
    const owner = accountModeKey(), token = localStorage.getItem('token') || ''
    if (!demo) {
      const response = await fetch(`${demoApiBase()}/simulation/session`, {
        headers: { Authorization: `Bearer ${token}`, 'X-Account-Mode': 'DEMO' },
        cache: 'no-store', signal: AbortSignal.timeout(15000),
      })
      const session = await response.json()
      if (!response.ok || !simulationSessionMatches(session, owner) || accountModeKey() !== owner || localStorage.getItem('token') !== token) throw new Error(text('独立模拟服务暂不可用，当前账户未改变', 'Demo service unavailable. Account unchanged.'))
    }
    setAccountMode(demo ? 'REAL' : 'DEMO')
    // Full reload discards old stores, subscriptions and in-flight responses. Accepted writes stay in their original account.
    const base = import.meta.env.BASE_URL
    const path = window.location.pathname
    const safe = /\/(trade|assets|orders|wallet|deposit|withdraw|financial|credit-loan)\/?$/.test(path)
    window.location.replace(safe ? path : base)
  } catch (e: any) { error.value = e?.message || 'Account switch failed'; busy.value = false }
}
</script>
<template>
  <div v-if="placement === 'menu' && !demo && canStartBusiness('simulation')" class="account-mode-entry">
    <button type="button" class="account-mode-menu" :disabled="busy" @click="switchAccount">
      <span class="entry-indicator" aria-hidden="true"></span>
      <span>{{ busy ? text('正在切换…', 'Switching…') : text('切换模拟账户', 'Switch to demo') }}</span>
      <span class="entry-chevron" aria-hidden="true">›</span>
    </button>
    <p v-if="error" role="alert">{{ error }}</p>
  </div>
  <nav v-else-if="placement === 'bar' && demo" class="account-mode-bar" :title="text('已提交操作继续在原账户执行；未提交表单不会带入另一个账户', 'Accepted operations finish in the original account. Unsubmitted forms are not transferred.')" :class="{ practice: demo }" :aria-label="text('账户模式', 'Account mode')">
    <div><span class="mode-dot" aria-hidden="true"></span><strong>{{ demo ? text('独立模拟账户', 'Independent demo account') : text('真实账户', 'Real account') }}</strong><small>{{ demo ? text('获授权功能 · 无真实出入金', 'Authorized features · Virtual funds only') : text('与模拟资金完全隔离', 'Isolated from demo funds') }}</small></div>
    <section style="display:flex;align-items:center;gap:8px"><button v-if="demo || canStartBusiness('simulation')" type="button" :disabled="busy" @click="switchAccount">{{ busy ? text('正在切换…', 'Switching…') : demo ? text('切换真实账户', 'Switch to real') : text('切换模拟账户', 'Switch to demo') }} ⇄</button></section>
    <p v-if="error" role="alert">{{ error }}</p>
  </nav>
</template>
<style scoped>
.account-mode-entry{width:100%}.account-mode-menu{display:flex;align-items:center;gap:12px;width:100%;padding:16px 20px;border:0;border-radius:12px;background:#f0f0f0;color:#000;font:inherit;font-size:16px;text-align:left;cursor:pointer}.entry-indicator{width:4px;height:20px;border-radius:2px;background:#73b100;flex-shrink:0}.entry-chevron{margin-left:auto;color:#999;font-size:24px;line-height:20px}.account-mode-menu:disabled{opacity:.6;cursor:wait}.account-mode-menu:focus-visible{outline:3px solid #8cc63f;outline-offset:2px}.account-mode-entry p{margin:6px 0;color:#b42318;font-size:12px}

.account-mode-bar{position:sticky;top:0;z-index:4000;min-height:56px;padding:8px 24px;display:flex;align-items:center;justify-content:space-between;gap:10px;background:#f4f6f9;color:#26344b;border-bottom:1px solid #d9e0eb;font:13px/1.4 system-ui,sans-serif}.account-mode-bar.practice{background:#eef2ff;border-bottom-color:#c7d2fe;color:#3730a3}.account-mode-bar>div{display:flex;align-items:center;gap:9px}.mode-dot{width:7px;height:7px;border-radius:50%;background:currentColor}.account-mode-bar small{font-size:11px;color:#65728a}.account-mode-bar button{font:inherit;font-weight:600;min-height:40px;padding:7px 12px;border:1px solid #c2cada;border-radius:8px;background:white;color:inherit;cursor:pointer;white-space:nowrap}.account-mode-bar button:focus-visible{outline:3px solid #818cf8;outline-offset:2px}@media(max-width:600px){.account-mode-bar{padding:6px 12px}.account-mode-bar>div{gap:6px;flex-wrap:wrap}.account-mode-bar small{display:block;width:100%;font-size:10px}}
</style>
