// Decimal-string preview only. The server decides and snapshots the final rate.
export function previewUsd(amount: string, rate: string): string | null {
  const parse = (s: string) => {
    if (!/^\d{1,16}(\.\d{1,16})?$/.test(s)) return null
    const [whole, fraction = ''] = s.split('.')
    return BigInt(whole + fraction.padEnd(16, '0'))
  }
  const a = parse(amount), r = parse(rate)
  if (a === null || r === null || a <= 0n || r <= 0n) return null
  const scale = 10n ** 16n, value = (a * r + scale / 2n) / scale
  return `${value / scale}.${(value % scale).toString().padStart(16, '0')}`.replace(/\.?0+$/, '')
}
export const depositSource: Record<string, string> = { USER_SUBMITTED: '用户提交', ADMIN_MANUAL: '管理员手动', LEGACY_UNKNOWN: '历史来源未记录' }
export const depositStatus: Record<string, string> = { PENDING: '待审核', COMPLETED: '已入账', REJECTED: '已拒绝' }
