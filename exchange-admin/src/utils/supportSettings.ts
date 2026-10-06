import request from './request'

export type SupportChannelConfig = { mode: 'off' | 'external' | 'internal'; inboxEnabled: boolean; capacity: number }

export async function saveSupportSettings(changes: Record<string, unknown>) {
  // Merge only this module's fields into the latest saved configuration, never another module's draft.
  const current = await request.get('/admin/support/settings') as unknown as Record<string, unknown>
  await request.post('/admin/support/settings', { ...current, ...changes })
}
