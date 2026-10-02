export const activityPositions = [
  { value: 'ANONYMOUS_HOME', label: '未登录主页' }, { value: 'AUTH_HOME', label: '登录后主页' },
  { value: 'AUTH_TRADE', label: '登录后交易页面' }, { value: 'AUTH_PROFILE', label: '登录后的个人页面' },
  { value: 'SUPPORT', label: '客服页面' },
]
export const activityTriggers = [
  { value: 'LOGIN', label: '成功登录' }, { value: 'API_CONTRACT_ORDER', label: '使用合约交易功能（成功提交订单）' },
  { value: 'API_OPTION_ORDER', label: '使用期权交易功能（成功提交订单）' },
  { value: 'PAGE_HOME', label: '访问首页' }, { value: 'PAGE_TRADE', label: '访问交易页' },
  { value: 'PAGE_PROFILE', label: '访问个人页' }, { value: 'PAGE_SUPPORT', label: '访问客服页' },
]
export function activitySettings(row: any) {
  return { ...structuredClone(row), startsAt: row.startsAt ?? null, endsAt: row.endsAt ?? null, autoSendEnabled: row.autoSendEnabled === true, claimValidityDays: row.claimValidityDays === undefined ? 3 : row.claimValidityDays, allowRepeatClaim: row.allowRepeatClaim === true, allowRepeatSend: row.allowRepeatSend === true, recentLoginDays: row.recentLoginDays ?? 3, autoPopup: row.autoPopup ?? true, repeatUnread: row.repeatUnread ?? false, animation: row.animation ?? 'GIFT', positions: row.positions ?? ['AUTH_HOME'], triggerConditions: row.triggerConditions ?? [] }
}
export function validateActivitySettings(value: any) {
  if (value.claimValidityDays !== null && (!Number.isSafeInteger(value.claimValidityDays) || value.claimValidityDays < 1 || value.claimValidityDays > 3650)) throw new Error('领取有效期须为1至3650天')
  if (!Array.isArray(value.positions) || !value.positions.length || value.positions.some((p: string) => !activityPositions.some(o => o.value === p))) throw new Error('请选择允许的展现位置')
  if (!Array.isArray(value.triggerConditions) || value.triggerConditions.some((p: string) => !activityTriggers.some(o => o.value === p))) throw new Error('自动发送条件无效')
  if (value.autoSendEnabled && (!value.startsAt || !value.endsAt || !value.triggerConditions.length)) throw new Error('自动发送须配置开始、结束时间及至少一个触发条件')
  for (const date of [value.startsAt, value.endsAt]) if (date && !Number.isFinite(Date.parse(date + (/Z$|[+-]\d\d:\d\d$/.test(date) ? '' : 'Z')))) throw new Error('活动时间格式无效')
  if (value.startsAt && value.endsAt && value.startsAt >= value.endsAt) throw new Error('结束时间必须晚于开始时间')
  if (!Number.isFinite(Number(value.amount)) || Number(value.amount) < 0.01 || Number(value.amount) > 1000000 || !/^\d+(\.\d{1,2})?$/.test(String(value.amount))) throw new Error('体验金金额无效')
  if (!Number.isSafeInteger(value.recentLoginDays) || value.recentLoginDays < 0 || value.recentLoginDays > 365) throw new Error('最近登录天数无效')
  if (!Number.isSafeInteger(value.maxClaims) || value.maxClaims < Math.max(1, value.claimCount || 0) || value.maxClaims > 1000000) throw new Error('领取名额无效')
  if (!Number.isFinite(Number(value.budget)) || Number(value.budget) < Math.max(Number(value.amount), Number(value.granted || 0)) || Number(value.budget) > 1000000000 || !/^\d+(\.\d{1,2})?$/.test(String(value.budget))) throw new Error('预算无效或低于已发放金额')
}

// A successful HTTP response must not silently ignore settings moved out of the content editor.
export function assertActivitySettingsSaved(expected: Record<string, any>, actual: any) {
  const normalize = (key: string, value: any) => {
    if (value == null) return null
    if (Array.isArray(value)) return JSON.stringify([...value].sort())
    if (['amount', 'budget'].includes(key)) return Number(value)
    if (['startsAt', 'endsAt'].includes(key)) return Date.parse(value + (/Z$|[+-]\d\d:\d\d$/.test(value) ? '' : 'Z'))
    return value
  }
  const missing = Object.keys(expected).filter(key => normalize(key, expected[key]) !== normalize(key, actual?.[key]))
  if (missing.length) throw new Error('发送设置返回与请求不一致：' + missing.join(', ') + '；请刷新核对，未确认保存成功')
}
