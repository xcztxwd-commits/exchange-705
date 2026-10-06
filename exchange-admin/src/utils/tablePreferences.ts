export type ColumnPreference = { id: string; visible: boolean; fixed: '' | 'left' | 'right' }
export type TableColumn = ColumnPreference & { label: string; defaultAfter?: string; legacyIds?: string[] }

// Generate only aliases of a known column in this table; never guess-decode arbitrary saved IDs.
export function legacyColumnIds(id: string): string[] {
  if (!/[^\x00-\x7f]/.test(id)) return [id]
  const bytes = new TextEncoder().encode(id)
  const latin1 = Array.from(bytes, byte => String.fromCharCode(byte)).join('')
  const windows1252 = new TextDecoder('windows-1252').decode(bytes)
  return [...new Set([id, latin1, windows1252, windows1252.replace(/[\u0081\u008d\u008f\u0090\u009d]/g, '\ufffd')])]
}

export function parseColumnPreferences(value: unknown): ColumnPreference[] {
  if (!Array.isArray(value) || value.length > 150) throw new Error('列配置响应无效，请重试')
  const ids = new Set<string>()
  return value.map(item => {
    if (!item || typeof item !== 'object' || Object.keys(item).length !== 3 ||
        typeof item.id !== 'string' || !item.id.length || item.id.length > 200 || ids.has(item.id) ||
        typeof item.visible !== 'boolean' || !['', 'left', 'right'].includes(item.fixed))
      throw new Error('列配置响应无效，请重试')
    ids.add(item.id)
    return { id: item.id, visible: item.visible, fixed: item.fixed }
  })
}

export function assertPreferencesSaved(value: unknown): void {
  if (!value || typeof value !== 'object' || (value as { success?: unknown }).success !== true)
    throw new Error('服务端未确认保存成功，请重试')
}

// Preserve saved choices; new columns may opt into a default neighbor without resetting preferences.
export function mergeColumns(columns: TableColumn[], saved: ColumnPreference[]): TableColumn[] {
  const remaining = new Map(columns.map(column => [column.id, column]))
  const aliases = new Map<string, string | null>()
  for (const column of columns) {
    for (const legacy of [column.id, ...(column.legacyIds || [])]) {
      for (const alias of legacyColumnIds(legacy)) {
        if (remaining.has(alias)) continue // Exact IDs always win, including another column's ID.
        aliases.set(alias, aliases.has(alias) && aliases.get(alias) !== column.id ? null : column.id)
      }
    }
  }
  const exact = new Set(saved.filter(item => remaining.has(item.id)).map(item => item.id))
  const result: TableColumn[] = []
  for (const item of saved) {
    const id = exact.has(item.id) ? item.id : aliases.get(item.id)
    if (!id || (id !== item.id && exact.has(id))) continue
    const column = remaining.get(id)
    if (!column) continue
    result.push({ ...column, visible: item.visible !== false, fixed: ['', 'left', 'right'].includes(item.fixed) ? item.fixed : column.fixed })
    remaining.delete(id)
  }
  for (const column of remaining.values()) {
    const after = column.defaultAfter ? result.findIndex(item => item.id === column.defaultAfter) : -1
    if (after < 0) result.push({ ...column })
    else result.splice(after + 1, 0, { ...column })
  }
  if (result.length && !result.some(column => column.visible)) result[0]!.visible = true
  return result
}

export function moveColumn<T>(columns: T[], from: number, to: number): T[] {
  const result = [...columns]
  if (from < 0 || to < 0 || from >= result.length || to >= result.length) return result
  const [column] = result.splice(from, 1)
  result.splice(to, 0, column!)
  return result
}

// The two entry points inject separate credentials and endpoints. The table never imports Pinia.
export const TABLE_PREFERENCES = Symbol('table-preferences')
export type TablePreferenceClient = {
  identityKey: () => string
  load: (table: string, signal: AbortSignal) => Promise<unknown>
  save: (table: string, columns: ColumnPreference[], signal: AbortSignal) => Promise<unknown>
}
export function preferenceRequests() {
  let current: AbortController | undefined
  return {
    next() {
      current?.abort()
      const controller = new AbortController(); current = controller
      return { signal: controller.signal, active: () => current === controller && !controller.signal.aborted }
    },
    stop() { current?.abort(); current = undefined },
  }
}
