import { access, can, loadAccess, clearAccess } from './access'
export async function loadUserPermissions() { await loadAccess(); return new Map(Object.entries(access.actions)) }
export async function hasPermission(menu: string, action: string) { await loadAccess(); return can(`${menu}:${action}`) }
export const clearPermissionsCache = clearAccess
