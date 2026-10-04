import type { AdminSession } from './adminSession'

export type CommandState = 'ACCEPTED' | 'PREPARING' | 'READY' | 'RUNNING' | 'FAILED' | 'CANCELLED'
export type CommandReceipt = { commandId: string | number; requestKey: string; state: CommandState; symbolId: number; errorCode?: string | null; message?: string | null }
export type PendingCommand = { symbolId: number; action: 'start' | 'restore'; requestKey: string; payload: Record<string, unknown>; receipt?: CommandReceipt }
export const COMMAND_STORAGE_PREFIX = 'ai-control-pending:'
const states = new Set(['ACCEPTED', 'PREPARING', 'READY', 'RUNNING', 'FAILED', 'CANCELLED'])
const requestKeyPattern = /^[A-Za-z0-9_-]{16,64}$/

// Callers provide the validated tab session, never the legacy shared token.
export function commandScope(session: AdminSession | null): string | null {
  if (!session || !session.token || !Number.isSafeInteger(session.user?.tenantId) || session.user.tenantId <= 0 || !Number.isSafeInteger(session.user?.id) || session.user.id === 0) return null
  if (session.mode !== 'admin' && session.mode !== 'control') return null
  if (session.mode === 'control' && (!session.accessSession?.id || session.accessSession.tenantId !== session.user.tenantId || session.accessSession.expiresAt <= Date.now())) return null
  if (session.mode === 'admin' && (typeof session.loginSessionId !== 'string' || !/^[0-9a-f-]{36}$/.test(session.loginSessionId))) return null
  return encodeURIComponent(JSON.stringify([session.user.tenantId, session.user.id, session.mode, session.mode === 'control' ? session.accessSession!.id : `admin:${session.loginSessionId}`]))
}
const storageKey = (scope: string, symbolId: number) => `${COMMAND_STORAGE_PREFIX}${scope}:${symbolId}`
export function commandBlocksStart(pending: PendingCommand | null): boolean {
  return !!pending && (!pending.receipt || !['RUNNING', 'FAILED', 'CANCELLED'].includes(pending.receipt.state))
}
export function readCommandReceipt(value: any, pending: Pick<PendingCommand, 'symbolId' | 'requestKey'>): CommandReceipt {
  const validId = typeof value?.commandId === 'string' ? value.commandId.length > 0 && value.commandId.length <= 128 : Number.isSafeInteger(value?.commandId) && value.commandId > 0
  if (!validId || value.requestKey !== pending.requestKey || value.symbolId !== pending.symbolId || !states.has(value.state) || (value.errorCode != null && typeof value.errorCode !== 'string') || (value.message != null && typeof value.message !== 'string')) throw new Error('启动命令回执不匹配；保留原请求，仅查询结果')
  return value as CommandReceipt
}
function validPending(value: any, symbolId: number): value is PendingCommand {
  return value?.symbolId === symbolId && Number.isSafeInteger(symbolId) && symbolId > 0 && ['start', 'restore'].includes(value.action) && typeof value.requestKey === 'string' && requestKeyPattern.test(value.requestKey) && !!value.payload && typeof value.payload === 'object' && !Array.isArray(value.payload)
}
function legacyAdminScope(scope: string): string | null {
  const identity = JSON.parse(decodeURIComponent(scope))
  return identity[2] === 'admin' && identity[3] !== 'admin' ? encodeURIComponent(JSON.stringify([...identity.slice(0, 3), 'admin'])) : null
}
function rejectUnknownLegacy(storage: Storage, scope: string, symbolId?: number) {
  const legacy = legacyAdminScope(scope)
  if (!legacy) return
  const prefix = `${COMMAND_STORAGE_PREFIX}${legacy}:`
  const ids = symbolId == null ? Object.keys(storage).filter(key => key.startsWith(prefix)).map(key => Number(key.slice(prefix.length))) : [symbolId]
  // The old scope has no login identity: keep its bytes, never adopt or silently restart it.
  if (ids.some(id => commandBlocksStart(readPendingCommand(storage, legacy, id)))) throw new Error('旧登录会话有待确认启动请求，请人工核对；不会另建命令')
}

export function readPendingCommand(storage: Storage, scope: string, symbolId: number): PendingCommand | null {
  const raw = storage.getItem(storageKey(scope, symbolId))
  if (raw == null) { rejectUnknownLegacy(storage, scope, symbolId); return null }
  // Corrupt saved requests fail closed: deleting them could issue a second command.
  if (raw.length > 32768) throw new Error('本地启动请求无效，请人工核对原请求；不会另建命令')
  const value = JSON.parse(raw)
  if (!validPending(value, symbolId)) throw new Error('本地启动请求无效，请人工核对原请求；不会另建命令')
  if (value.receipt) readCommandReceipt(value.receipt, value)
  return value
}
export function writePendingCommand(storage: Storage, scope: string, value: PendingCommand) {
  if (!validPending(value, value.symbolId)) throw new Error('启动请求参数无效')
  if (value.receipt) readCommandReceipt(value.receipt, value)
  const raw = JSON.stringify(value)
  if (raw.length > 32768) throw new Error('启动请求超过本地保存上限')
  storage.setItem(storageKey(scope, value.symbolId), raw)
}
export function removePendingCommand(storage: Storage, scope: string, symbolId: number) { storage.removeItem(storageKey(scope, symbolId)) }
export function savedCommandSymbol(storage: Storage, scope: string): number | undefined {
  rejectUnknownLegacy(storage, scope)
  const prefix = `${COMMAND_STORAGE_PREFIX}${scope}:`
  const saved = Object.keys(storage).filter(key => key.startsWith(prefix)).map(key => readPendingCommand(storage, scope, Number(key.slice(prefix.length)))).filter((value): value is PendingCommand => !!value)
  return (saved.find(commandBlocksStart) || saved[0])?.symbolId
}
export function commandNotice(pending: PendingCommand | null): string {
  const receipt = pending?.receipt
  if (!receipt) return pending ? '启动结果待确认；保留原请求，仅查询，不会重复启动' : ''
  const labels: Record<CommandState, string> = { ACCEPTED: '启动命令已受理，尚未开始运行', PREPARING: '启动命令准备中，尚未开始运行', READY: '启动命令已准备完成，等待引擎启动', RUNNING: '启动命令已运行', FAILED: '启动命令失败', CANCELLED: '启动命令已取消' }
  return [labels[receipt.state], receipt.errorCode, receipt.message].filter(Boolean).join('：')
}
