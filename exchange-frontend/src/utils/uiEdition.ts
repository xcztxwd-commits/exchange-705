export type UiEdition = 'classic' | 'advanced'

// Public referral links select presentation only; never persist another user's preference.
export function routeUiEdition(edition: UiEdition, path: string, requested: unknown, signedIn: boolean): UiEdition {
  return !signedIn && path === '/register' && requested === 'advanced' ? 'advanced' : edition
}

// Presentation preference only. Never stores a token, API gateway or REAL/DEMO mode.
export function uiEditionKey(user: { tenantId?: unknown; id?: unknown } | null, origin: string) {
  const valid = (value: unknown) => (typeof value === 'string' && value.trim() !== '') || (typeof value === 'number' && Number.isSafeInteger(value) && value > 0)
  return valid(user?.tenantId) && valid(user?.id)
    ? `exchange:ui-edition:v1:${encodeURIComponent(origin)}:${encodeURIComponent(String(user!.tenantId))}:${encodeURIComponent(String(user!.id))}`
    : ''
}

export function readUiEdition(storage: Pick<Storage, 'getItem'>, key: string): UiEdition {
  try { return key && storage.getItem(key) === 'advanced' ? 'advanced' : 'classic' } catch { return 'classic' }
}

export function writeUiEdition(storage: Pick<Storage, 'setItem'>, key: string, value: UiEdition) {
  if (!key || !['classic', 'advanced'].includes(value)) return false
  try { storage.setItem(key, value); return true } catch { return false }
}
