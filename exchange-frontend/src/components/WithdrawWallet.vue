<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue'
import AppSelect from '@/components/AppSelect.vue'
import request from '@/utils/request'
import { useLocaleStore } from '@/store/locale'
import { formatWalletBalance, formatTransferAmount } from '../utils/withdrawalWallet'
import type { WalletAccount, WalletBalances } from '../utils/withdrawalWallet'

type TransferInput = { fromAccount: WalletAccount; toAccount: WalletAccount; amount: number }
const props = defineProps<{
  modelValue: WalletAccount
  balances: WalletBalances
  ready: boolean
  disabled?: boolean
  sendTransfer?: (body: TransferInput) => Promise<any>
}>()
const emit = defineEmits<{
  (event: 'update:modelValue', value: WalletAccount): void
  (event: 'transferred'): void
}>()
const locale = useLocaleStore()
const id = useId()
const dialog = ref<HTMLDialogElement>()
const fromAccount = ref<WalletAccount>('CONTRACT')
const toAccount = ref<WalletAccount>('FUND')
const amount = ref<number | string | null>(null)
const transferring = ref(false)
const message = ref('')
const status = ref('')
let disposed = false
const quickTransfer = computed(() => locale.text('快捷劃轉', 'Quick transfer'))
const formatBalance = (value: number) => formatWalletBalance(value, locale.locale)
const accounts = computed(() => [
  { value: 'FUND' as const, label: locale.t('fundAccountTitle') },
  { value: 'CONTRACT' as const, label: locale.t('contractAccount').replace(/[:：]\s*$/, '') },
  { value: 'OPTION' as const, label: locale.t('optionAccount').replace(/[:：]\s*$/, '') },
].map(account => ({ ...account, description: `${locale.t('availableBalance')} · ${props.ready ? formatBalance(props.balances[account.value]) + ' USD' : '—'}` })))
const fromOptions = computed(() => accounts.value.filter(account => account.value !== toAccount.value))
const toOptions = computed(() => accounts.value.filter(account => account.value !== fromAccount.value))
const canTransfer = computed(() => props.ready && !props.disabled && !transferring.value
  && fromAccount.value !== toAccount.value && Number.isFinite(Number(amount.value))
  && Number(amount.value) > 0 && Number(amount.value) <= props.balances[fromAccount.value])

function openTransfer() {
  if (!props.ready || props.disabled) return
  toAccount.value = props.modelValue
  const candidates = accounts.value.filter(account => account.value !== toAccount.value)
  fromAccount.value = candidates.find(account => props.balances[account.value] > 0)?.value ?? candidates[0]!.value
  amount.value = null
  message.value = ''
  status.value = ''
  dialog.value?.showModal()
}

function closeTransfer() {
  if (!transferring.value) dialog.value?.close()
}

function swapAccounts() {
  const previous = fromAccount.value
  fromAccount.value = toAccount.value
  toAccount.value = previous
}

watch([fromAccount, toAccount], () => { amount.value = null; message.value = '' })

function normalizeAmount() {
  if (amount.value !== null && amount.value !== '') amount.value = formatTransferAmount(Number(amount.value))
}

async function submitTransfer() {
  normalizeAmount()
  if (!canTransfer.value) return
  transferring.value = true
  message.value = ''
  try {
    const body = { fromAccount: fromAccount.value, toAccount: toAccount.value, amount: Number(amount.value) }
    const result = await (props.sendTransfer ? props.sendTransfer(body) : request.post('/transfer/submit', body))
    if (disposed) return
    if (!result || result.success === false) throw new Error(result?.message || locale.t('transferFailed'))
    status.value = locale.t('transferSuccess')
    dialog.value?.close()
    emit('transferred')
  } catch (error: any) {
    if (!disposed) message.value = error.message || locale.t('transferFailed')
  } finally {
    transferring.value = false
  }
}

onBeforeUnmount(() => { disposed = true; dialog.value?.close() })
</script>

<template>
  <div class="withdraw-wallet">
    <div class="wallet-heading">
      <span>{{ locale.t('wallet') }}</span>
      <button type="button" class="quick-transfer" :disabled="!ready || disabled || transferring" @click="openTransfer">
        <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M3 6h13m-3-3 3 3-3 3M17 14H4m3-3-3 3 3 3" /></svg>
        {{ quickTransfer }}
      </button>
    </div>
    <div class="wallet-row">
      <AppSelect :model-value="modelValue" :options="accounts" :label="locale.t('wallet')" :disabled="disabled || transferring" @update:model-value="emit('update:modelValue', $event as WalletAccount)" />
    </div>
    <p v-if="status" class="transfer-success" role="status">{{ status }}</p>
    <Teleport to="body">
      <dialog ref="dialog" class="transfer-dialog" :aria-labelledby="`${id}-title`" @cancel="transferring && $event.preventDefault()" @click="($event.target === dialog) && closeTransfer()">
        <div class="transfer-content">
          <div class="transfer-heading">
            <h2 :id="`${id}-title`">{{ quickTransfer }}</h2>
            <button type="button" class="close-transfer" :aria-label="locale.text('關閉', 'Close')" :disabled="transferring" @click="closeTransfer">×</button>
          </div>
          <form @submit.prevent="submitTransfer">
            <div class="transfer-route">
              <div class="transfer-field">
                <label>{{ locale.t('transferFrom') }}</label>
                <AppSelect v-model="fromAccount" :options="fromOptions" :label="locale.t('transferFrom')" :disabled="transferring" />
              </div>
              <button type="button" class="swap-accounts" :aria-label="locale.text('交換帳戶', 'Swap accounts')" :disabled="transferring" @click="swapAccounts">
                <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M3 6h13m-3-3 3 3-3 3M17 14H4m3-3-3 3 3 3" /></svg>
              </button>
              <div class="transfer-field">
                <label>{{ locale.t('transferTo') }}</label>
                <AppSelect v-model="toAccount" :options="toOptions" :label="locale.t('transferTo')" :disabled="transferring" />
              </div>
            </div>
            <div class="transfer-field">
              <label :for="`${id}-amount`">{{ locale.t('amountText') }}</label>
              <div class="transfer-amount">
                <input :id="`${id}-amount`" v-model="amount" type="number" inputmode="decimal" step="0.01" min="0" :placeholder="locale.t('enterAmount')" :disabled="transferring" @blur="normalizeAmount" />
                <span>USD</span>
                <button type="button" :disabled="!ready || transferring" @click="amount = formatTransferAmount(balances[fromAccount]); message = ''">{{ locale.t('all') }}</button>
              </div>
              <p class="transfer-available">{{ locale.t('availableBalance') }}: <bdi>{{ ready ? formatBalance(balances[fromAccount]) + ' USD' : '—' }}</bdi></p>
            </div>
            <p v-if="Number(amount) > balances[fromAccount]" class="transfer-error" role="alert">{{ locale.t('insufficientBalance') }}</p>
            <p v-if="message" class="transfer-error" role="alert">{{ message }}</p>
            <button type="submit" class="confirm-transfer" :disabled="!canTransfer">{{ transferring ? locale.t('submitting') : locale.t('submitTransfer') }}</button>
          </form>
        </div>
      </dialog>
    </Teleport>
  </div>
</template>

<style scoped>
.withdraw-wallet { margin-bottom: 12px; color: #25313b; font-size: 14px; }
.wallet-heading, .wallet-row, .transfer-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.wallet-heading { flex-wrap: wrap; margin-bottom: 4px; color: #666; }
.quick-transfer { display: inline-flex; align-items: center; gap: 5px; padding: 4px 0; border: 0; background: transparent; color: #609500; font: inherit; cursor: pointer; }
svg { width: 18px; height: 18px; flex: none; fill: none; stroke: currentColor; stroke-width: 1.5; stroke-linecap: round; stroke-linejoin: round; }
.wallet-row { padding: 0 12px; border: 1px solid #e2ead9; border-radius: 10px; background: #fafcf6; }
.wallet-row .app-select { flex: 1; }
.wallet-row :deep(.app-select__trigger) { padding-inline: 0; border: 0; background: transparent; box-shadow: none; text-align: start; }
.wallet-row :deep(.app-select__value) { white-space: normal; overflow-wrap: anywhere; }
.transfer-success { margin: 8px 0 0; color: #609500; font-size: 12px; }
.transfer-dialog { box-sizing: border-box; width: min(440px, calc(100% - 32px)); max-width: none; max-height: calc(100dvh - 32px); margin: auto; padding: 0; border: 0; border-radius: 18px; background: #fff; color: #25313b; box-shadow: 0 20px 80px #17231b33; font: inherit; }
.transfer-dialog::backdrop { background: #17231b66; }
.transfer-content { padding: 24px; }
.transfer-heading { margin-bottom: 22px; }
.transfer-heading h2 { margin: 0; font-size: 18px; font-weight: 600; overflow-wrap: anywhere; }
.close-transfer { flex: none; width: 32px; height: 32px; padding: 0; border: 0; border-radius: 50%; background: #f3f5f0; color: #6e7767; font-size: 24px; cursor: pointer; }
.transfer-route { position: relative; display: grid; gap: 16px; margin-bottom: 20px; padding: 16px; border-radius: 12px; background: #f7f9f3; }
.transfer-field { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.transfer-field > label { color: #747c6d; font-size: 13px; line-height: 20px; }
.transfer-field :deep(.app-select__trigger) { text-align: start; }
/* Keep the destination label beside the switch without expanding the selector gap. */
.transfer-route > .transfer-field:last-child { position: relative; }
.transfer-route > .transfer-field:last-child > label { position: absolute; inset-inline-start: 0; bottom: calc(100% + 8px); max-width: calc(50% - 24px); line-height: 18px; overflow-wrap: anywhere; }
.swap-accounts { display: flex; align-items: center; justify-content: center; justify-self: center; width: 32px; height: 32px; margin: 0; border: 1px solid #e2ead9; border-radius: 50%; background: #fff; color: #609500; cursor: pointer; }
.swap-accounts svg { transform: rotate(90deg); }
.transfer-amount { display: flex; align-items: center; gap: 10px; padding: 12px; border: 1px solid #dce4df; border-radius: 10px; }
.transfer-amount input { width: 100%; min-width: 0; padding: 0; border: 0; outline: none; background: transparent; color: inherit; font: inherit; }
.transfer-amount span { color: #7d8575; font-size: 13px; }
.transfer-amount button { flex: none; padding: 0; border: 0; background: transparent; color: #609500; font: inherit; cursor: pointer; }
.transfer-amount:focus-within { border-color: #85bd00; outline: 2px solid #85bd0026; }
.transfer-available { margin: 0; color: #7d8575; font-size: 12px; overflow-wrap: anywhere; }
.transfer-error { margin: 12px 0 0; color: #c64343; font-size: 13px; overflow-wrap: anywhere; }
.confirm-transfer { width: 100%; margin-top: 24px; padding: 14px; border: 0; border-radius: 10px; background: #73b100; color: #fff; font: inherit; font-weight: 600; cursor: pointer; overflow-wrap: anywhere; }
button:disabled { opacity: .5; cursor: not-allowed; }
button:focus-visible { outline: 2px solid #73b100; outline-offset: 3px; }
@media (max-width: 480px) {
  .transfer-dialog { width: 100%; max-height: calc(100dvh - 24px); margin: auto 0 0; border-radius: 18px 18px 0 0; }
  .transfer-content { padding: 22px 20px calc(24px + env(safe-area-inset-bottom)); }
}
</style>
