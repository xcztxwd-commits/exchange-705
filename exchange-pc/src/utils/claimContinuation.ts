export const CLAIM_TTL = 15 * 60 * 1000
type StorageLike = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
export type PendingClaim = { tenant: number; campaign: number; actionId: string; created: number; deadline: number; user?: number; state: 'waiting' | 'sending' | 'failed' | 'done'; public: boolean }
export const claimKey = (tenant: number, campaign: number) => `trial-claim:${tenant}:${campaign}`
export const pendingPointer = (tenant: number) => `trial-claim-pending:${tenant}`
export const steadyWall = () => performance.timeOrigin + performance.now()
export const newActionId = () => crypto.randomUUID().replace(/-/g, '')
export function readClaim(storage: StorageLike, tenant: number, campaign: number, now: number): PendingClaim | null {
  try {
    const item = JSON.parse(storage.getItem(claimKey(tenant, campaign)) || 'null')
    if (item?.tenant !== tenant || item?.campaign !== campaign || !/^[A-Za-z0-9_-]{16,64}$/.test(item?.actionId || '') || !Number.isFinite(item.created) || item.deadline !== item.created + CLAIM_TTL || now < item.created || now >= item.deadline || !['waiting','sending','failed','done'].includes(item.state)) return null
    return item
  } catch { return null }
}
export function saveClaim(storage: StorageLike, item: PendingClaim) { storage.setItem(claimKey(item.tenant, item.campaign), JSON.stringify(item)) }
export function beginClaim(storage: StorageLike, tenant: number, campaign: number, now: number, isPublic: boolean, repeat = false, actionId = newActionId()): PendingClaim {
  const existing = readClaim(storage, tenant, campaign, now)
  if (existing && !(repeat && existing.state === 'done')) return existing
  const item: PendingClaim = { tenant, campaign, actionId, created: now, deadline: now + CLAIM_TTL, state: 'waiting', public: isPublic }
  saveClaim(storage, item)
  if (isPublic) storage.setItem(pendingPointer(tenant), String(campaign))
  return item
}
export function resumableClaim(storage: StorageLike, tenant: number, user: number, mode: string, now: number): PendingClaim | null {
  const campaign = Number(storage.getItem(pendingPointer(tenant))), item = readClaim(storage, tenant, campaign, now)
  if (mode !== 'REAL' || !item?.public || item.state === 'done' || (item.user != null && item.user !== user)) return null
  return item
}
export function cancelClaim(storage: StorageLike, tenant: number, campaign: number) {
  storage.removeItem(claimKey(tenant, campaign))
  if (storage.getItem(pendingPointer(tenant)) === String(campaign)) storage.removeItem(pendingPointer(tenant))
}
// Endpoint/key pairs remain stable across transport failure, refresh and concurrent tabs.
export function claimEndpoint(item: PendingClaim, delivery?: number) {
  if (item.public) return `/activity/campaigns/${item.campaign}/claim`
  if (!Number.isSafeInteger(delivery) || Number(delivery) <= 0) throw Error('Invalid delivery')
  return `/activity/messages/${delivery}/claim`
}
