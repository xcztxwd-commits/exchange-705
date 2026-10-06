export type ColumnPreference = { id: string; visible: boolean; fixed: '' | 'left' | 'right' }
export type TableColumn = ColumnPreference & { label: string; defaultAfter?: string }

// Preserve saved choices; new columns may opt into a default neighbor without resetting preferences.
export function mergeColumns(columns: TableColumn[], saved: ColumnPreference[]): TableColumn[] {
  const remaining = new Map(columns.map(column => [column.id, column]))
  const result: TableColumn[] = []
  for (const item of saved) {
    const column = remaining.get(item.id)
    if (!column) continue
    result.push({ ...column, visible: item.visible !== false, fixed: ['', 'left', 'right'].includes(item.fixed) ? item.fixed : column.fixed })
    remaining.delete(item.id)
  }
  for (const column of remaining.values()) {
    const after = column.defaultAfter ? result.findIndex(item => item.id === column.defaultAfter) : -1
    if (after < 0) result.push(column)
    else result.splice(after + 1, 0, column)
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
