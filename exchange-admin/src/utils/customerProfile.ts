import { addDecimal } from './accountTableData'

export const customerProfileFields = [
  'email', 'countryCode', 'phone', 'nickname', 'avatarUrl', 'annualIncome', 'annualIncomeCurrency',
  'remark', 'myInviteCode', 'parentUserId', 'status', 'userType', 'lastLoginIp', 'lastLoginRegion',
  'lastLoginDomain', 'lastLoginAt',
] as const

export function totalCustomerBalance(user: Record<string, any>): string {
  if (user.totalBalance != null) return String(user.totalBalance)
  return ['fundBalance', 'contractBalance', 'optionBalance']
    .reduce((total, key) => addDecimal(total, user[key] ?? '0'), '0')
}

export function customerProfilePatch(original: Record<string, any>, draft: Record<string, any>) {
  return Object.fromEntries(customerProfileFields.filter(key => (original[key] ?? '') !== (draft[key] ?? ''))
    .map(key => [key, (draft[key] == null || draft[key] === '') && ['parentUserId', 'annualIncome', 'lastLoginAt'].includes(key) ? null : draft[key]]))
}

export function balancesChanged(original: Record<string, any>, draft: Record<string, any>) {
  const normalize = (value: string | number) => addDecimal(value, '0').replace(/(\.\d*?[1-9])0+$|\.0+$/, '$1')
  return ['FUND', 'CONTRACT', 'OPTION'].some(key => normalize(original[key] ?? '0') !== normalize(draft[key] ?? '0'))
}
