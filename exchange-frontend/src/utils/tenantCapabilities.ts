export interface TenantCapabilities {
  tenantId: number
  tenantName: string
  status: string
  policyVersion: number
  acceptNewBusiness: boolean
  features: Record<string, boolean>
}
// Only new actions consult this gate. Historical reads and exits do not.
export function allowsNewBusiness(snapshot: TenantCapabilities | null, feature: string) {
  return !!snapshot && Number.isSafeInteger(snapshot.tenantId) && snapshot.tenantId > 0 &&
    snapshot.status === 'ACTIVE' && snapshot.acceptNewBusiness === true && snapshot.features?.[feature] === true
}
export function simulationSessionMatches(session: any, owner: string) {
  return Number.isSafeInteger(session?.tenantId) && session.tenantId > 0 &&
    Number.isSafeInteger(session?.userId) && session.userId > 0 &&
    session.environment === 'DEMO' && `account-mode:${session.tenantId}:${session.userId}` === owner
}
