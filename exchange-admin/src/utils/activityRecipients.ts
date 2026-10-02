export type ActivityRecipient = { id: number; email: string; createdAt?: string; lastLoginAt?: string }
export type ActivityRecipientFilter = { query: string; createdFrom: string | null; createdTo: string | null; lastLoginFrom: string | null; lastLoginTo: string | null; claimedCampaignIds: number[]; claimedMode: 'INCLUDE' | 'EXCLUDE' }
export const emptyRecipientFilter = (): ActivityRecipientFilter => ({ query: '', createdFrom: null, createdTo: null, lastLoginFrom: null, lastLoginTo: null, claimedCampaignIds: [], claimedMode: 'INCLUDE' })
export function recipientIds(ids: number[]) {
  if (ids.some(id => !Number.isSafeInteger(id) || id <= 0)) throw new Error('用户或活动 ID 无效')
  return [...new Set(ids)]
}
export function mergeActivityRecipients(existing: ActivityRecipient[], incoming: ActivityRecipient[]) {
  const all = new Map<number, ActivityRecipient>()
  for (const row of [...existing, ...incoming]) { recipientIds([row.id]); all.set(row.id, { ...row }) }
  return [...all.values()]
}
export function recipientFilter(value: ActivityRecipientFilter) {
  const result = { ...value, query: value.query.trim(), claimedCampaignIds: recipientIds(value.claimedCampaignIds) }
  if (result.query.length > 128) throw new Error('搜索内容不能超过128字符')
  if (!['INCLUDE', 'EXCLUDE'].includes(result.claimedMode)) throw new Error('请选择包含或排除已领取活动')
  for (const [from, to] of [[result.createdFrom, result.createdTo], [result.lastLoginFrom, result.lastLoginTo]]) {
    for (const date of [from, to]) if (date && (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z?$/.test(date) || !Number.isFinite(Date.parse(date.endsWith('Z') ? date : date + 'Z')))) throw new Error('时间格式无效，请使用服务器时间')
    if (from && to && from >= to) throw new Error('筛选结束时间必须晚于开始时间')
  }
  return result
}
export function recipientPage(data: any): { content: ActivityRecipient[]; totalElements: number } {
  if (!Array.isArray(data?.content) || !Number.isSafeInteger(data.totalElements) || data.totalElements < 0) throw new Error('收件人分页契约未就绪，不能将单页当作全部结果')
  const content = mergeActivityRecipients([], data.content.map((row: any) => ({ ...row, id: row.userId ?? row.id })))
  if (content.length > data.totalElements) throw new Error('收件人总数无效')
  return { content, totalElements: data.totalElements }
}

export function activitySelection(data: any) {
  if (!Number.isSafeInteger(data?.selectionId) || data.selectionId <= 0 || !Number.isSafeInteger(data.selected) || data.selected < 0) throw new Error('可编辑全量选择契约未就绪，不能启用发送')
  return { ...data, totalElements: data.selected }
}
