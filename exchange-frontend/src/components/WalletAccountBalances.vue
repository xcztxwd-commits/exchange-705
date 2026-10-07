<script setup lang="ts">
import { useLocaleStore } from '@/store/locale'

defineProps<{
  accounts: Array<{ type: string; name: string; available: number; frozen: number }>
  visible: boolean
  unavailable: boolean
}>()
const locale = useLocaleStore()
const formatMoney = (amount: number) => amount.toLocaleString(locale.locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
</script>

<template>
  <section class="wallet-accounts" :aria-label="locale.text('帳戶餘額', 'Account balances')" :aria-busy="unavailable || undefined">
    <h2>{{ locale.text('帳戶餘額', 'Account balances') }}</h2>
    <div class="account-grid">
      <article v-for="account in accounts" :key="account.type" class="account-card" :class="account.type.toLowerCase()" :aria-label="account.name">
        <header class="account-heading">
          <span class="account-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none">
              <path v-if="account.type === 'FUND'" d="M19 7H6a2 2 0 0 1 0-4h12v4M4 5v14a2 2 0 0 0 2 2h14V7m0 5h-6v5h6m-3-2.5h.01" />
              <path v-else-if="account.type === 'CONTRACT'" d="M6 3v18M3 7h6v9H3V7m15-4v18m-3-12h6v7h-6V9" />
              <path v-else d="M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16m0 4v4l3 2M9 2h6" />
            </svg>
          </span>
          <h3>{{ account.name }}</h3>
          <span class="account-currency">USD</span>
        </header>
        <div class="account-balance">
          <span>{{ locale.t('balance') }}</span>
          <strong><bdi>{{ unavailable ? '—' : visible ? formatMoney(account.available + account.frozen) : '****' }}</bdi></strong>
        </div>
        <dl class="account-details">
          <div>
            <dt>{{ locale.t('availableBalance') }}</dt>
            <dd><bdi>{{ unavailable ? '—' : visible ? formatMoney(account.available) : '****' }}</bdi></dd>
          </div>
          <div>
            <dt>{{ locale.text('凍結金額', 'Frozen balance') }}</dt>
            <dd><bdi>{{ unavailable ? '—' : visible ? formatMoney(account.frozen) : '****' }}</bdi></dd>
          </div>
        </dl>
      </article>
    </div>
  </section>
</template>

<style scoped>
.wallet-accounts { color: #25313b; }
.wallet-accounts h2 { margin: 0 0 12px; font-size: 15px; font-weight: 600; }
.account-grid { display: grid; grid-template-columns: minmax(0, 1fr); gap: 12px; }
.account-card { --account-color: #639300; --account-soft: #f0f6e5; min-width: 0; padding: 16px; border: 1px solid #e9eeE5; border-radius: 12px; background: #fff; }
.account-card.contract { --account-color: #467fbb; --account-soft: #edf4fb; }
.account-card.option { --account-color: #8b6cb3; --account-soft: #f4effa; }
.account-heading { display: flex; align-items: center; gap: 10px; }
.account-icon { display: flex; flex: none; align-items: center; justify-content: center; width: 32px; height: 32px; border-radius: 9px; background: var(--account-soft); color: var(--account-color); }
.account-icon svg { width: 19px; height: 19px; stroke: currentColor; stroke-width: 1.5; stroke-linecap: round; stroke-linejoin: round; }
.account-heading h3 { flex: 1; min-width: 0; margin: 0; font-size: 14px; font-weight: 600; overflow-wrap: anywhere; }
.account-currency { flex: none; color: #879080; font-size: 11px; }
.account-balance { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: 6px 12px; margin: 12px 0; }
.account-balance > span { color: #86907e; font-size: 12px; }
.account-balance strong { min-width: 0; color: #25313b; font-size: 22px; font-weight: 650; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.account-details { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin: 0; padding-top: 12px; border-top: 1px solid #f0f2ed; }
.account-details > div { min-width: 0; }
.account-details > div + div { padding-inline-start: 12px; border-inline-start: 1px solid #f0f2ed; }
.account-details dt { color: #879080; font-size: 11px; line-height: 1.5; overflow-wrap: anywhere; }
.account-details dd { margin: 4px 0 0; color: #3e4938; font-size: 14px; font-weight: 500; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
@media (min-width: 900px) { .account-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
:global(.dark) .account-card { background: #171e2b; border-color: #303b49; }
:global(.dark) .wallet-accounts, :global(.dark) .account-balance strong, :global(.dark) .account-details dd { color: #e5e7eb; }
:global(.dark) .account-details, :global(.dark) .account-details > div + div { border-color: #303b49; }
</style>
