// Local-only regression page. Uses the real shared component and synthetic identities/data.
// Start: node tests/tablePreferences.fixture.mjs; no production API or credentials involved.
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'
import { parseColumnPreferences } from '../src/utils/tablePreferences.ts'

const root = fileURLToPath(new URL('..', import.meta.url))
const saved = new Map([['admin/1/Users.1', [
  { id: 'id', visible: true, fixed: '' },
  { id: 'ç”¨æˆ·ç±»åž‹', visible: true, fixed: 'left' },
  { id: 'æ‰‹æœºå�·', visible: true, fixed: '' },
  { id: 'å¹´æ”¶å…¥', visible: false, fixed: '' },
  { id: 'createdAt', visible: true, fixed: '' },
  { id: 'ç™»å½•IP / åœ°åŒº', visible: true, fixed: '' },
  { id: 'æ“�ä½œ', visible: true, fixed: 'right' },
]]])
const html = `<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><title>列设置修复回归</title>
<style>body{font:15px system-ui;padding:24px;max-width:1050px;margin:auto;color:#243348}header{display:flex;gap:16px;flex-wrap:wrap;margin-bottom:20px}select,button{padding:6px}pre{white-space:pre-wrap;word-break:break-all;background:#f4f7fa;padding:12px;font-size:12px}</style></head>
<body><h1>列设置修复回归</h1><p>本地测试；真实 AdminTable；仅模拟数据；不连接线上后台。</p><div id="app"></div>
<script type="module">
import { createApp, h, ref } from 'vue'
import ElementPlus, { ElTableColumn } from 'element-plus'
import 'element-plus/dist/index.css'
import AdminTable from '/src/components/AdminTable.ts'
import { TABLE_PREFERENCES } from '/src/utils/tablePreferences.ts'
const query = new URL(location.href).searchParams
const scope = ref(query.get('scope') || 'admin'), actor = ref(query.get('actor') || '1')
const table = ref(query.get('table') || 'Users.1'), mode = ref('normal'), revision = ref(0), payload = ref('尚未保存')
function endpoint(key) { return '/__preferences/' + scope.value + '/' + actor.value + '/' + key + '?mode=' + mode.value }
const client = {
  identityKey: () => scope.value + ':' + actor.value,
  async load(key, signal) {
    // Slow mode intentionally ignores abort, to exercise the component's stale-response guard.
    const response = await fetch(endpoint(key), mode.value === 'slow' ? {} : { signal })
    if (!response.ok) throw Error('模拟加载失败')
    return response.json()
  },
  async save(key, columns, signal) {
    const response = await fetch(endpoint(key), { method:'PUT',signal,headers:{'Content-Type':'application/json;charset=UTF-8'},body:JSON.stringify(columns) })
    if (!response.ok) throw Error('模拟保存失败')
    const result = await response.json()
    payload.value = JSON.stringify({ success:result.success, columns }, null, 2)
    return result
  },
}
function selector(label, value, choices) {
  return h('label', [label + ' ', h('select', { 'aria-label':label,value:value.value,onChange:e=>{
    value.value=e.target.value
    history.replaceState(null,'','?scope='+scope.value+'&actor='+actor.value+'&table='+table.value)
  }}, choices.map(([v,text])=>h('option',{value:v},text)))])
}
const app = createApp({ setup() { return () => h('main', [
  h('header', [
    selector('身份入口',scope,[['admin','普通后台'],['control','独立总控']]),
    selector('测试账号',actor,[['1','账号 1'],['2','账号 2']]),
    selector('测试表格',table,[['Users.1','用户表'],['Orders.1','订单表']]),
    selector('接口测试模式',mode,[['normal','正常'],['slow','延迟读取'],['invalid','无效读取响应'],['saveFalse','保存返回失败'],['saveError','保存 HTTP 失败']]),
    h('button',{onClick:()=>revision.value++},'重新加载列设置'),
  ]),
  h('p',{'data-testid':'identity'},'当前测试身份：'+scope.value+' / '+actor.value+' / '+table.value),
  h(AdminTable,{key:revision.value,tableKey:table.value,data:[{id:101,createdAt:'2026-10-07',phone:'测试号码',income:'测试收入',userType:'测试类型',location:'测试地区'}],border:true},{default:()=>[
    h(ElTableColumn,{prop:'id',label:'ID',width:80}),
    h(ElTableColumn,{prop:'createdAt',label:'注册时间',width:130}),
    h(ElTableColumn,{'column-key':'phone',label:'手机号',width:140},{default:()=> '测试号码'}),
    h(ElTableColumn,{'column-key':'annualIncome',label:'年收入',width:140},{default:()=> '测试收入'}),
    h(ElTableColumn,{'column-key':'loginLocation',label:'登录IP / 地区',width:180},{default:()=> '测试地区'}),
    h(ElTableColumn,{'column-key':'userType',label:'用户类型',width:140},{default:()=> '测试类型'}),
    h(ElTableColumn,{'column-key':'actions',label:'操作',fixed:'right',width:100},{default:()=> '测试操作'}),
  ]}),
  h('h3','最近保存数据（应只有英文 ID）'),h('pre',{'data-testid':'saved-payload'},payload.value),
]) } })
app.provide(TABLE_PREFERENCES,client).use(ElementPlus).mount('#app')
</script></body></html>`

const server = await createServer({
  configFile: false, root, cacheDir: 'node_modules/.vite-column-fixture', plugins: [vue(), {
    name: 'table-preference-regression',
    configureServer(server) {
      server.middlewares.use(async (request, response, next) => {
        const url = new URL(request.url, 'http://127.0.0.1')
        if (url.pathname === '/') {
          response.setHeader('Content-Type', 'text/html;charset=UTF-8')
          response.end(await server.transformIndexHtml(url.pathname, html)); return
        }
        if (!url.pathname.startsWith('/__preferences/')) { next(); return }
        response.setHeader('Content-Type', 'application/json;charset=UTF-8')
        response.setHeader('Cache-Control', 'no-store')
        const key = url.pathname.slice('/__preferences/'.length), mode = url.searchParams.get('mode')
        try {
          if (request.method === 'GET') {
            const result = mode === 'invalid' ? { success: false } : saved.get(key) || []
            if (mode === 'slow') await new Promise(resolve => setTimeout(resolve, 10000))
            response.end(JSON.stringify(result)); return
          }
          if (request.method === 'PUT') {
            let body = ''; for await (const chunk of request) body += chunk
            const columns = parseColumnPreferences(JSON.parse(body))
            if (mode === 'saveError') { response.statusCode = 503; response.end('{}'); return }
            if (mode === 'saveFalse') { response.end(JSON.stringify({ success: false })); return }
            saved.set(key, columns); response.end(JSON.stringify({ success: true })); return
          }
          response.statusCode = 405; response.end('{}')
        } catch { response.statusCode = 400; response.end('{}') }
      })
    },
  }],
  resolve: { alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) } },
  server: { host: '127.0.0.1', port: Number(process.env.COLUMN_QA_PORT || 5197), strictPort: true },
})
await server.listen()
console.log('Local synthetic column regression: ' + server.resolvedUrls.local[0])
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, async () => { await server.close(); process.exit(0) })
