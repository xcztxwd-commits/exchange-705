import { useLocaleStore } from '@/store/locale'
import { useAuthStore } from '@/store/auth'

let redirecting = false
// Shared by preflight and server denials: never replay the rejected transaction.
export async function redirectTradeKyc() {
  if (redirecting) return
  redirecting = true
  const token = useAuthStore().token
  try {
    const { default: router } = await import('@/router')
    if (!token || token !== useAuthStore().token) return
    await router.push('/verification')
    if (token !== useAuthStore().token) return
    const locale = useLocaleStore()
    window.alert(locale.locale === 'ja' ? '本人確認を完了してください。審査承認後に取引できます。' : locale.text('請先完成實名認證，審核通過後才可交易。', 'Please complete identity verification. Trading is available after approval.'))
  } finally { setTimeout(() => { redirecting = false }, 300) }
}
