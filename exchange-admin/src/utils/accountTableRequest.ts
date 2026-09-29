import base from './request'
import type { useAccountTable } from './useAccountTable'
import { realmCsv } from './accountTableData'
const lists = new Set(['/admin/users', '/admin/users/query','/admin/orders/contract/query','/admin/orders/option/query','/admin/deposit/review/list','/admin/withdraw/list','/admin/loan/review/list','/admin/loan/personal-info/list','/admin/kyc/list','/admin/financial/orders','/admin/deposit/orders/list'])
export function accountTableRequest(table: ReturnType<typeof useAccountTable>, extraReadOnly: () => boolean = () => false) {
  function write(method: 'post'|'put'|'delete'|'patch', url: string, data?: any, config?: any): any {
    if (lists.has(url)) return table.query(url, data, 'POST')
    if (extraReadOnly() || table.modes.value.includes('DEMO') || !table.modes.value.length || table.rowMode.value === 'DEMO') return Promise.reject(new Error('包含模拟账户的筛选仅供查看，请切回真实账户后操作'))
    return (base[method] as any)(url, data, config)
  }
  return {
    get(url: string, config?: any): any {
      if (url === '/admin/support/inbox') return table.fixedQuery(url, config?.params || {}, 30, true)
      if (url === '/admin/activities' || /^\/admin\/activities\/\d+\/recipients$/.test(url) || /^\/admin\/activities\/users\/\d+\/ledger$/.test(url)) return table.fixedQuery(url, config?.params || {}, 50)
      if (/^\/admin\/activities\/\d+\/stats$/.test(url) || /^\/admin\/activities\/users\/\d+\/account$/.test(url)) return table.counters(url)
      if (lists.has(url)) return table.query(url, config?.params, 'GET', ['/admin/deposit/orders/list','/admin/users'].includes(url) ? 1 : 0)
      if (url === '/admin/statistics' || /^\/admin\/agents\/\d+\/performance$/.test(url)) return table.statistics(url, config?.params)
      if (url === '/admin/deposit/orders/summary') return table.summary(url, config?.params || {})
      if (/^\/admin\/(users\/\d+|wallet\/\d+|financial\/yield\/order\/\d+|deposit\/orders\/\d+)/.test(url)) return table.detail(url, config?.params)
      if (url === '/admin/deposit/orders/export') return (async () => {
        const modes = [...table.modes.value]
        if (!modes.length) return new Blob(['\uFEFF账户类型\r\n'], { type: 'text/csv;charset=utf-8' })
        const files = await Promise.all(modes.map(mode => mode === 'REAL' ? base.get(url, config) : base.post('/admin/account-query', { method: 'GET', path: '/api' + url, params: config?.params || {} }, { responseType: 'blob' })))
        if (modes.join(',') !== table.modes.value.join(',')) throw new Error('筛选已变更，请重新导出')
        const texts = await Promise.all(files.map(file => (file as unknown as Blob).text()))
        return new Blob(['\uFEFF', ...texts.map((text, i) => realmCsv(text, modes[i]!, i === 0))], { type: 'text/csv;charset=utf-8' })
      })()
      return base.get(url, config)
    },
    post: (url: string, data?: any, config?: any) => write('post',url,data,config),
    put: (url: string, data?: any, config?: any) => write('put',url,data,config),
    delete: (url: string, config?: any) => write('delete',url,config),
    patch: (url: string, data?: any, config?: any) => write('patch',url,data,config),
  }
}

export function accountTableRawRequest(table: ReturnType<typeof useAccountTable>) {
  const client = accountTableRequest(table)
  const path = (url: string) => { const parsed = new URL(url, location.origin); return parsed.pathname.replace(/^\/api/, "") + parsed.search }
  return Object.fromEntries(["get","post","put","delete","patch"].map(method => [method, async (url: string, ...args: any[]) => ({data: await (client as any)[method](path(url), ...args)})])) as Record<string, any>
}
