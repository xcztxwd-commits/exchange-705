import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'
import { compileScript, parse } from '@vue/compiler-sfc'

const root = fileURLToPath(new URL('../', import.meta.url))
let fixtureSequence = 0
const fixtureSource = `
import { reactive, ref } from 'vue'
export const fixture = {
  catalog: [{symbol:'BTCUSD',category:'Crypto',pricePrecision:2},{symbol:'ETHUSD',category:'Crypto',pricePrecision:2}],
  gets: [], posts: [], subscriptions: [], releases: [],
  catalogResponse: null, orderResponse: null, ensure: async () => true,
  subscribe: async () => {},
}
export const auth = reactive({ token:'isolated-fixture', user:{id:1,tenantId:1} })
export const locale = {text: zh => zh,t: key => key,loadLocale(){},locale:'en-US',categoryLabel:key=>key}
export const wallet = reactive({state:{eligible:false,available:0},snapshot:{},ready:true,tick:0,refresh:async()=>{}})
export const market = {
  quoteStatusMap:{}, getPrice:symbol=>symbol==='ETHUSD'?200:100,
  getQuoteStatus:()=> 'available',getChange24h:()=>({changePct:0}),getConversionRate:()=>1,getMarginBaseRate:()=>1,
  subscribeSymbols: async (items,owner) => { fixture.subscriptions.push({symbols:items.map(item=>item.symbol),owner}); await fixture.subscribe(items,owner) },
}
export const request = {
  get: async url => { fixture.gets.push(url); return url==='/market/all' ? fixture.catalogResponse || {list:fixture.catalog} : [] },
  post: async (url,payload) => { fixture.posts.push({url,payload}); return fixture.orderResponse || {success:true} },
}
export const kyc = () => ({verified:ref(true),checking:ref(false),promptOpen:ref(false),promptMessage:ref(''),ensure:()=>fixture.ensure(),handleError:()=>false})
export const sizing = options => { options.available.value=1000000; return {allocationPercent:ref(0),setAllocation(){},canAllocate:ref(true),orderReady:ref(true),liquidation:ref({buy:null,sell:null}),refreshAccount:async()=>{}} }
`

async function mountView(name, query = {}, prepare = () => {}) {
  const aliases = {
    '@/utils/tenantFeatures': 'export const canStartBusiness=()=>true',
    '@/utils/visitorRegion': 'export const formatQuoteTime=()=>"fixture"',
    '@/utils/useTradeKyc': 'export { kyc as useTradeKyc } from "route-fixture"',
    '@/utils/useOrderSizing': 'export { sizing as useOrderSizing } from "route-fixture"',
    '@/utils/useTrialWallet': 'import {wallet} from "route-fixture";export const useTrialWallet=()=>wallet',
    '@/utils/accountMode': 'export const accountMode=()=>"REAL"',
    '@/utils/trialLifecycle': 'export const reconcileFunding=choice=>choice;export const selectedAvailable=()=>1000000',
    '@/store/auth': 'import {auth} from "route-fixture";export const useAuthStore=()=>auth',
    '@/store/market': 'import {market} from "route-fixture";export const useMarketStore=()=>market',
    '@/store/locale': 'import {locale} from "route-fixture";export const useLocaleStore=()=>locale',
    '@/utils/displaySymbol': 'export const displaySymbol=s=>s.symbol||s',
    '@/utils/request': 'export {request as default} from "route-fixture"',
    '@/utils/marketWebSocket': 'import {fixture} from "route-fixture";export default {release:owner=>fixture.releases.push(owner)}',
  }
  const result = await build({
    stdin: { contents: `export {default as Component} from './src/advanced/views/${name}.vue';export {createRenderer,nextTick} from 'vue';export {createRouter,createMemoryHistory} from 'vue-router';export {fixture} from 'route-fixture';`, resolveDir: root },
    bundle: true, write: false, format: 'esm', platform: 'node', define: { 'process.env.NODE_ENV': '"production"', '__VUE_PROD_DEVTOOLS__': 'false', '__VUE_OPTIONS_API__': 'true', '__VUE_PROD_HYDRATION_MISMATCH_DETAILS__': 'false' },
    plugins: [{ name: 'isolated-view', setup(b) {
      b.onResolve({ filter: /^route-fixture$/ }, () => ({ path: 'route-fixture', namespace: 'fixture' }))
      b.onResolve({ filter: /^@\// }, args => args.path.endsWith('.vue') || aliases[args.path]
        ? { path: args.path, namespace: 'fixture' } : { path: fileURLToPath(new URL(`../src/${args.path.slice(2)}.ts`, import.meta.url)) })
      b.onLoad({ filter: /.*/, namespace: 'fixture' }, args => ({
        contents: args.path === 'route-fixture' ? fixtureSource : aliases[args.path] || 'export default {render(){return null}}', resolveDir: root,
      }))
      b.onLoad({ filter: /\.vue$/ }, args => ({
        contents: compileScript(parse(readFileSync(args.path, 'utf8')).descriptor, { id: `route-${name}` }).content,
        loader: 'ts', resolveDir: root,
      }))
    } }],
  })
  const module = await import('data:text/javascript;base64,' + Buffer.from(result.outputFiles[0].text + `\n//# sourceURL=advanced-route-${name}-${++fixtureSequence}.fixture.mjs`).toString('base64'))
  prepare(module.fixture)
  const savedWindow = globalThis.window, savedDocument = globalThis.document
  const renderer = module.createRenderer({
    createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
    insert(){}, remove(){}, setText(){}, setElementText(){}, patchProp(){}, parentNode:()=>null, nextSibling:()=>null,
  })
  const path = name === 'Orders' ? '/orders' : '/trade'
  const router = module.createRouter({ history: module.createMemoryHistory(), routes: ['/orders','/trade','/home'].map(path=>({path,component:{render(){return null}}})) })
  await router.push({ path, query })
  let state
  const setup = module.Component.setup
  module.Component.setup = (props, context) => (state = setup(props, context))
  module.Component.render = () => null
  const app = renderer.createApp(module.Component)
  app.use(router)
  globalThis.window = {}; globalThis.document = { visibilityState: 'visible' }
  app.mount({})
  const flush = async () => { for (let i=0;i<8;i++) { await Promise.resolve(); await module.nextTick() } }
  return {
    state, fixture: module.fixture, router, flush,
    async visit(query) { await router.push({path,query}); await flush() },
    async history(method) { await new Promise(resolve=>{const stop=router.afterEach(()=>{stop();resolve()});router[method]()});await flush() },
    dispose() { app.unmount(); if(savedWindow===undefined) delete globalThis.window; else globalThis.window=savedWindow; if(savedDocument===undefined) delete globalThis.document; else globalThis.document=savedDocument },
  }
}

test('advanced orders restore query filters through same-path navigation and history without remounting', async () => {
  const view = await mountView('Orders', {tab:'term',status:'closed'})
  try {
    const s=view.state
    await view.flush()
    assert.equal(s.mainTab.value,'term'); assert.equal(s.termTab.value,'history')
    s.showProducts.value=true
    await view.visit({status:'pending'})
    assert.equal(s.mainTab.value,'contract'); assert.equal(s.contractTab.value,'pending'); assert.equal(s.showProducts.value,false)
    await view.history('back')
    assert.equal(s.mainTab.value,'term'); assert.equal(s.termTab.value,'history')
    await view.history('forward')
    assert.equal(s.contractTab.value,'pending')
    s.contractTab.value='history'
    await view.visit({status:'pending',unrelated:'preserve-local-tab'})
    assert.equal(s.contractTab.value,'history')
    await view.visit({tab:'invalid',status:'invalid'})
    assert.equal(s.mainTab.value,'contract'); assert.equal(s.contractTab.value,'positions'); assert.equal(s.termTab.value,'active')
    assert.deepEqual(view.fixture.gets,['/market/all']); assert.equal(view.fixture.posts.length,0)
  } finally { view.dispose() }
})

test('advanced trade uses latest query while catalog loads, then restores instruments through history', async () => {
  let resolveCatalog
  const view = await mountView('Trade', {symbol:'BTCUSD'}, fixture => { fixture.catalogResponse=new Promise(resolve=>{resolveCatalog=resolve}) })
  try {
    const s=view.state
    await view.visit({symbol:'ETHUSD'})
    resolveCatalog({list:view.fixture.catalog}); await view.flush()
    assert.equal(s.currentSymbol.value,'ETHUSD')
    s.takeProfit.value=250; s.stopLoss.value=150; s.limitPrice.value=199; s.showConfirm.value=true
    await view.history('back')
    assert.equal(s.currentSymbol.value,'BTCUSD'); assert.equal(s.takeProfit.value,''); assert.equal(s.stopLoss.value,''); assert.equal(s.limitPrice.value,0); assert.equal(s.showConfirm.value,false)
    await view.history('forward')
    assert.equal(s.currentSymbol.value,'ETHUSD')
    s.takeProfit.value=250
    await view.visit({symbol:'ETHUSD',unrelated:'keep-draft'})
    assert.equal(s.takeProfit.value,250)
    await view.visit({symbol:'not-in-catalog'})
    assert.equal(s.currentSymbol.value,'BTCUSD')
    await view.visit({symbol:'ETHUSD',tab:'term'}); assert.equal(s.activeTab.value,'term')
    await view.visit({}); assert.equal(s.currentSymbol.value,'BTCUSD'); assert.equal(s.activeTab.value,'contract')
    assert.equal(view.fixture.gets.filter(url=>url==='/market/all').length,1)
    assert.deepEqual(view.fixture.subscriptions.at(-1),{symbols:['BTCUSD'],owner:'advanced-trade-selected'})
    assert.equal(view.fixture.posts.length,0)
  } finally { view.dispose() }
  assert.deepEqual(view.fixture.releases,['advanced-trade','advanced-trade-selected'])
})

test('route symbol change invalidates an awaiting KYC confirmation without sending an order', async () => {
  const view=await mountView('Trade',{symbol:'BTCUSD'})
  try {
    await view.flush()
    let resolveKyc
    view.fixture.ensure=()=>new Promise(resolve=>{resolveKyc=resolve})
    const opening=view.state.openContract('BUY')
    await view.visit({symbol:'ETHUSD'})
    resolveKyc(true); await opening; await view.flush()
    assert.equal(view.state.showConfirm.value,false); assert.equal(view.fixture.posts.length,0)
  } finally { view.dispose() }
})

test('same-path navigation never replays an in-flight accepted order or applies its old view response', async () => {
  const view=await mountView('Trade',{symbol:'BTCUSD'})
  try {
    await view.flush()
    let resolveOrder
    view.fixture.orderResponse=new Promise(resolve=>{resolveOrder=resolve})
    view.state.showConfirm.value=true
    const submitting=view.state.submit(); await view.flush()
    assert.equal(view.fixture.posts.length,1); assert.equal(view.fixture.posts[0].payload.symbol,'BTCUSD')
    await view.visit({symbol:'ETHUSD'})
    assert.equal(view.state.currentSymbol.value,'ETHUSD'); assert.equal(view.state.showConfirm.value,false)
    resolveOrder({success:true}); await submitting; await view.flush()
    assert.equal(view.fixture.posts.length,1); assert.equal(view.state.revision.value,0); assert.equal(view.state.contractTab.value,'entry'); assert.equal(view.state.busy.value,false)
  } finally { view.dispose() }
})

test('older instrument subscriptions cannot overwrite the current draft or error', async () => {
  const view=await mountView('Trade',{symbol:'BTCUSD'})
  try {
    await view.flush()
    view.state.orderType.value='limit'; await view.flush()
    let rejectSubscription
    view.fixture.subscribe=(items,owner)=>owner==='advanced-trade-selected' && items[0].symbol==='ETHUSD'
      ? new Promise((_resolve,reject)=>{rejectSubscription=reject}) : Promise.resolve()
    await view.visit({symbol:'ETHUSD'})
    assert.equal(view.state.limitPrice.value,0)
    await view.visit({symbol:'BTCUSD'})
    assert.equal(view.state.limitPrice.value,100)
    rejectSubscription(new Error('stale ETH subscription')); await view.flush()
    assert.equal(view.state.limitPrice.value,100); assert.notEqual(view.state.message.value,'stale ETH subscription')
    const subscriptions=view.fixture.subscriptions.length
    await view.router.push('/home'); await view.flush()
    assert.equal(view.fixture.subscriptions.length,subscriptions); assert.equal(view.fixture.posts.length,0)
  } finally { view.dispose() }
})