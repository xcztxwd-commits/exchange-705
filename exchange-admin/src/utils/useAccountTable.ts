import { ref, watch } from 'vue'
import request from './request'
import { tagRows, mergeAccountLists, mergeDepositSummary, addDecimal, type AccountMode } from './accountTableData'

export function useAccountTable() {
  const modes = ref<AccountMode[]>(['REAL'])
  const rowMode = ref<AccountMode>('REAL')
  let epoch = 0
  const sequences = new Map<string, number>()
  watch(modes, () => { epoch++ }, { flush: 'sync', deep: true })
  function selectRow(row: any) { rowMode.value = row.accountMode || 'REAL' }
  async function read(mode: AccountMode, path: string, params: any = {}, method = 'GET'): Promise<any> {
    const result: any = mode === 'REAL'
      ? method === 'POST' ? await request.post(path, params) : await request.get(path, { params })
      : await request.post('/admin/account-query', { method, path: '/api' + path, ...(method === 'POST' ? { body: params } : { params }) })
    if (result?.success === false) throw new Error(result.message || '查询失败')
    return tagRows(result, mode)
  }
  async function query(path: string, params: any = {}, method = 'GET', pageBase = 0): Promise<any> {
    const selected = [...modes.value], signature = selected.join(',')
    const version = epoch, sequence = (sequences.get(path) || 0) + 1
    sequences.set(path, sequence)
    if (!selected.length) return { success: true, list: [], total: 0 }
    const paged = params.page != null && params.size != null
    const size = Number(params.size || 20), offset = (Number(params.page || 0) - pageBase) * size
    const results = await Promise.all(selected.map(async mode => {
      if (selected.length === 1 || !paged) return read(mode, path, params, method)
      const required = offset + size
      if (required > 10000 || offset < 0) throw new Error('合并查询最多浏览前 10000 条，请缩小筛选范围')
      let list: any[] = [], first: any
      for (let start = 0; start < required; start += 100) {
        const batch = await read(mode, path, { ...params, page: start / 100 + pageBase, size: 100 }, method)
        if (!first) first = batch
        list.push(...(batch.list || []))
        if (list.length >= Number(batch.total) || (batch.list || []).length < 100) break
      }
      return { ...first, list }
    }))
    if (version !== epoch || sequence !== sequences.get(path) || signature !== modes.value.join(',')) throw new Error('账户筛选已变更，已丢弃旧查询')
    if (selected.length === 1) return results[0]
    return mergeAccountLists(results, paged ? offset : 0, paged ? size : undefined)
  }
  async function summary(path: string, params: any) {
    const version = epoch
    const results = await Promise.all(modes.value.map(mode => read(mode, path, params)))
    if (version !== epoch) throw new Error('账户筛选已变更')
    return mergeDepositSummary(results)
  }
  async function fundDetails(path: string) {
    const version = epoch
    const results = await Promise.all(modes.value.map(mode => read(mode, path)))
    if (version !== epoch) throw new Error('账户筛选已变更')
    const data: any = {}
    for (const key of ['contractOrders','optionOrders','deposits','withdraws','loans','financialOrders','transfers']) data[key] = mergeAccountLists(results.map(r => ({ list: r.data?.[key] || [] }))).list
    return { success: true, data }
  }
  async function statistics(path: string, params: any = {}) {
    const version = epoch
    const results = await Promise.all(modes.value.map(mode => read(mode, path, params)))
    if (version !== epoch) throw new Error('账户筛选已变更')
    const data: any = { ...(results[0]?.data || {}) }
    for (const key of ['totalUsers','totalDeposit','totalWithdraw','totalTrade','subordinateCount','subordinateTotalDeposit','subordinateTotalWithdraw']) data[key] = results.reduce((sum, r) => addDecimal(sum, r.data?.[key] || '0'), '0')
    const charts = results.map(r => r.data?.chartData).filter(Boolean)
    const dates: string[] = [...new Set<string>(charts.flatMap(c => c.dates || []))].sort()
    data.chartData = { dates }
    for (const key of ['depositAmounts','withdrawAmounts']) data.chartData[key] = dates.map(date => charts.reduce((sum, c) => addDecimal(sum, c[key]?.[c.dates.indexOf(date)] || '0'), '0'))
    data.depositGroupsUsd = {}
    for (const r of results) for (const [key, value] of Object.entries(r.data?.depositGroupsUsd || {})) data.depositGroupsUsd[key] = addDecimal(data.depositGroupsUsd[key] || '0', value as string)
    return { success: true, data }
  }
  async function fixedQuery(path: string, params: any, size: number, array = false) {
    const version = epoch, selected = [...modes.value], offset = Number(params.page || 0) * size
    if (offset > 10000) throw new Error('请缩小筛选范围，合并查询最多浏览前 10000 条')
    const results = await Promise.all(selected.map(async mode => {
      let list: any[] = [], total = 0
      for (let start = 0; start <= offset; start += size) {
        const r = await read(mode, path, { ...params, page: start / size })
        const batch = array ? r : r.content || []
        list.push(...batch); total = array ? list.length : Number(r.totalElements || 0)
        if (batch.length < size || (!array && list.length >= total)) break
      }
      return { list, total }
    }))
    if (version !== epoch) throw new Error('账户筛选已变更')
    const all = results.flatMap(r => r.list).sort((a, b) => String(b.id).localeCompare(String(a.id), 'en', { numeric: true }) || String(a.accountMode).localeCompare(String(b.accountMode)))
    const content = all.slice(offset, offset + size)
    return array ? content : { content, totalElements: results.reduce((n, r) => n + r.total, 0) }
  }
  async function counters(path: string) {
    const version = epoch, results = await Promise.all(modes.value.map(mode => read(mode, path)))
    if (version !== epoch) throw new Error('账户筛选已变更')
    const output: any = {}
    for (const key of ['sent','received','opened','closed','closedWithoutOpening','claimed','available','frozen','granted','consumed','profits']) output[key] = results.reduce((n, r) => addDecimal(n, r[key] || '0'), '0')
    return output
  }
  let detailSequence = 0
  async function detail(path: string, params: any = {}) {
    const mode = rowMode.value, sequence = ++detailSequence
    const result = await read(mode, path, params)
    if (sequence !== detailSequence || mode !== rowMode.value) throw new Error('已丢弃过期详情')
    return result
  }
  return { modes, rowMode, selectRow, query, summary, detail, fundDetails, statistics, fixedQuery, counters }
}
