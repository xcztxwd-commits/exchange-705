import { shallowRef } from 'vue'
import { allowsNewBusiness, type TenantCapabilities } from './tenantCapabilities'
export const tenantFeatures = shallowRef<TenantCapabilities | null>(null)
export const featuresLoading = shallowRef(false)
export const featuresError = shallowRef(false)
let lastAttempt = 0
let generation = 0
export const canStartBusiness = (feature: string) => allowsNewBusiness(tenantFeatures.value, feature)
export async function refreshTenantFeatures(force = false) {
  if (featuresLoading.value || (!force && Date.now() - lastAttempt < 15000)) return
  const current = ++generation
  lastAttempt = Date.now(); featuresLoading.value = true
  try {
    // Public snapshot always comes from REAL identity. Never send a bearer or use the demo gateway.
    const response = await fetch('/api/tenant/features', { credentials: 'omit', cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(10000) })
    if (!response.ok) throw new Error('Tenant capabilities unavailable')
    const snapshot = await response.json()
    if (!Number.isSafeInteger(snapshot.tenantId) || snapshot.tenantId <= 0 || typeof snapshot.features !== 'object' || !snapshot.features) throw new Error('Invalid tenant capabilities')
    if (current === generation) { tenantFeatures.value = snapshot; featuresError.value = false }
  } catch {
    if (current === generation) { tenantFeatures.value = null; featuresError.value = true }
  } finally { if (current === generation) featuresLoading.value = false }
}
