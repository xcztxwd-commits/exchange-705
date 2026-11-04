import type { AccountMode } from './accountTableData'

export function orderCloseUrl(mode: AccountMode, kind: 'contract' | 'option', id: number): string {
  if (!Number.isSafeInteger(id) || id <= 0 || !['contract', 'option'].includes(kind) || !['REAL', 'DEMO'].includes(mode)) throw new Error('订单或账户类型无效')
  if (mode === 'DEMO') return `/admin/simulation/orders/${kind}/${id}/close`
  return kind === 'contract' ? `/admin/orders/contract/${id}/close` : `/admin/controlled-exits/option/${id}/close`
}
