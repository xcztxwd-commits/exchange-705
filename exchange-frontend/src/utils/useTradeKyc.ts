import { computed, onMounted, onScopeDispose, ref, watch } from 'vue'
import request from '@/utils/request'
import { redirectTradeKyc } from './tradeKycRedirect'
import { useAuthStore } from '@/store/auth'
import { useLocaleStore } from '@/store/locale'

// Only new-order entry points call ensure(). Closing/cancelling/settling is unaffected.
export function useTradeKyc(login: () => void, notify: (message: string) => void) {
  const auth = useAuthStore(), locale = useLocaleStore()
  const verified = ref(false), checking = ref(false), promptOpen = ref(false), status = ref('NOT_VERIFIED')
  let lastError = false, disposed = false, pending: Promise<boolean> | null = null
  const copy = (zh: string, en: string, ja: string) => locale.locale === 'ja' ? ja : locale.text(zh, en)
  const promptMessage = computed(() => status.value === 'PENDING'
    ? copy('實名認證審核中，通過後才可交易。', 'Identity verification is under review. Trading is available after approval.', '本人確認は審査中です。承認後に取引できます。')
    : status.value === 'REJECTED'
      ? copy('實名認證未通過，請修改資料後重新提交。', 'Identity verification was rejected. Update your details and resubmit.', '本人確認が承認されませんでした。資料を修正し再提出してください。')
      : copy('請先實名認證，審核通過後才可交易。', 'Please complete identity verification. Trading is available after approval.', 'まず本人確認を完了してください。審査承認後に取引できます。'))
  async function refresh(): Promise<boolean> {
    if (pending) return pending
    const token = auth.token
    if (!token) { verified.value = false; return false }
    checking.value = true
    pending = (async () => {
      try {
        const result: any = await request.get('/kyc/status')
        if (disposed || token !== auth.token) return false
        if (result?.success === false || !['VERIFIED', 'NOT_VERIFIED'].includes(result?.kycStatus)) throw new Error('Invalid KYC status')
        status.value = result.latestRecord?.status || 'NOT_VERIFIED'
        verified.value = result.kycStatus === 'VERIFIED' && status.value === 'APPROVED'
        lastError = false
        return verified.value || result.canTrade === true
      } catch {
        if (!disposed && token === auth.token) { verified.value = false; lastError = true }
        return false
      } finally { checking.value = false; pending = null }
    })()
    return pending
  }
  async function ensure(): Promise<boolean> {
    if (promptOpen.value || checking.value) return false
    if (!auth.token) { login(); return false }
    const token = auth.token
    const allowed = await refresh()
    if (disposed || token !== auth.token) return false
    if (allowed) return true
    if (lastError) notify(copy('實名認證狀態檢查失敗，請稍後重試。', 'Unable to check identity verification. Please try again.', '本人確認状態を確認できませんでした。再試行してください。'))
    else void redirectTradeKyc()
    return false
  }
  function handleError(error: any): boolean {
    if (error?.errorCode !== 'KYC_REQUIRED') return false
    verified.value = false
    status.value = error.kycStatus || 'NOT_VERIFIED'
    promptOpen.value = true
    return true
  }
  const refreshVisible = () => { if (document.visibilityState === 'visible') void refresh() }
  watch(() => auth.token, () => {
    verified.value = false; promptOpen.value = false; status.value = 'NOT_VERIFIED'
    // Do not let an old account's in-flight response authorize the new account.
    if (pending) void pending.then(() => { if (!disposed) void refresh() })
    else void refresh()
  })
  onMounted(() => { void refresh(); window.addEventListener('focus', refreshVisible); document.addEventListener('visibilitychange', refreshVisible) })
  onScopeDispose(() => { disposed = true; window.removeEventListener('focus', refreshVisible); document.removeEventListener('visibilitychange', refreshVisible) })
  return { verified, checking, promptOpen, promptMessage, ensure, handleError, refresh }
}
