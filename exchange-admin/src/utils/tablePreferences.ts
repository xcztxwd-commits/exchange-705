export type ColumnPreference = { id: string; visible: boolean; fixed: '' | 'left' | 'right' }
export type TableColumn = ColumnPreference & { label: string }

// Ignore removed/unauthorized columns; append new columns with their original defaults.
export function mergeColumns(columns: TableColumn[], saved: ColumnPreference[]): TableColumn[] {
  const remaining = new Map(columns.map(column => [column.id, column]))
  const result: TableColumn[] = []
  for (const item of saved) {
    const column = remaining.get(item.id)
    if (!column) continue
    result.push({ ...column, visible: item.visible !== false, fixed: ['', 'left', 'right'].includes(item.fixed) ? item.fixed : column.fixed })
    remaining.delete(item.id)
  }
  result.push(...remaining.values())
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
