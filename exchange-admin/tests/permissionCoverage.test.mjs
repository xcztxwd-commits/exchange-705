import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parse } from '@vue/compiler-sfc'
import { parse as parseTemplate } from '@vue/compiler-dom'
import { stripTypeScriptTypes } from 'node:module'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const catalog = JSON.parse(fs.readFileSync(path.join(root, 'exchange-backend/src/main/resources/admin-permissions.json'), 'utf8'))
const known = new Map(catalog.menus.map(menu => [menu.code, new Set(['view', ...Object.keys(menu.actions)])]))
known.set('session', new Set(['self', 'close']))
// Separately maintained feature catalogs may extend the shared permission infrastructure.
const extensions = fs.readdirSync(path.join(root, 'exchange-backend/src/main/java/com/gtcfesk/exchange/support')).filter(f => f.endsWith('PermissionCatalog.java'))
for (const file of extensions) {
  const source = fs.readFileSync(path.join(root, 'exchange-backend/src/main/java/com/gtcfesk/exchange/support', file), 'utf8')
  for (const match of source.matchAll(/register\(group,\s*"([^"]+)"[\s\S]*?new String\[\]\[\]\{([\s\S]*?)\}\);/g)) {
    known.set(match[1], new Set(['view', ...[...match[2].matchAll(/\{"([^"]+)"/g)].map(m => m[1])]))
  }
}
const files = ['views', 'components'].flatMap(dir => fs.readdirSync(path.join(root, 'exchange-admin/src', dir)).filter(f => f.endsWith('.vue') && f !== 'Login.vue').map(f => path.join(root, 'exchange-admin/src', dir, f)))
let controls = 0
test('every business button, upload and switch declares a registered permission', () => {
  const missing = []
  for (const file of files) {
    const { descriptor } = parse(fs.readFileSync(file, 'utf8'))
    if (!descriptor.template) continue
    function visit(node) {
      if (node.type === 1) {
        const permission = node.props.find(p => p.type === 7 && p.name === 'permission')
        if (['el-button', 'el-dropdown-item', 'button', 'el-link', 'el-upload', 'el-switch'].includes(node.tag)) {
          controls++
          if (!permission) missing.push(`${path.basename(file)}:${node.loc.start.line} ${node.tag}`)
        }
        if (permission) {
          const codes = [...permission.exp.content.matchAll(/'([a-z_]+):([a-z_]+)'/g)]
          assert.ok(codes.length, `${file}: permission must reference explicit capabilities`)
          for (const [, menu, action] of codes) assert.ok(known.get(menu)?.has(action), `${file}: unregistered ${menu}:${action}`)
        }
      }
      for (const child of node.children || []) visit(child)
    }
    visit(parseTemplate(descriptor.template.content))
  }
  assert.deepEqual(missing, [])
  assert.ok(controls >= 220, `Audited ${controls} controls`)
  console.log(`Permission coverage: ${controls} controls; ${catalog.menus.length} base menus; ${catalog.menus.reduce((n,m) => n+Object.keys(m.actions).length, 0)} base actions`)
})

test('catalog codes are unique and every page belongs to a named first-level group', () => {
  const groups = new Set(catalog.groups.map(g => g.code)), codes = new Set()
  for (const menu of catalog.menus) {
    assert.ok(groups.has(menu.group)); assert.ok(menu.path.startsWith('/')); assert.ok(!codes.has(menu.code)); codes.add(menu.code)
    for (const action of Object.keys(menu.actions)) assert.ok(`${menu.code}:${action}`.length <= 150)
  }
})

test('all declared backend action guards reference the same catalog', () => {
  const dir = path.join(root, 'exchange-backend/src/main/java/com/gtcfesk/exchange/admin')
  let count = 0
  for (const file of fs.readdirSync(dir).filter(f => f.endsWith('Controller.java'))) {
    const source = fs.readFileSync(path.join(dir,file),'utf8')
    for (const [, menu, action] of source.matchAll(/AdminPermission\(menu\s*=\s*"([^"]+)",\s*action\s*=\s*"([^"]*)"\)/g)) {
      assert.ok(known.get(menu)?.has(action || 'view'), `${file}: ${menu}:${action}`); count++
    }
  }
  assert.ok(count >= 85)
  console.log(`Explicit backend endpoint guards: ${count}`)
})

test('shared control widgets keep total-control and tenant grants distinct', () => {
  const accounts = fs.readFileSync(path.join(root, 'exchange-admin/src/components/BackendAccounts.vue'), 'utf8')
  const online = fs.readFileSync(path.join(root, 'exchange-admin/src/components/OnlineUsers.vue'), 'utf8')
  const manager = fs.readFileSync(path.join(root, 'exchange-admin/src/control/TenantManager.vue'), 'utf8')
  assert.match(accounts, /v-permission="control \? 'session:self' : 'admin_list:create'"/)
  assert.match(accounts, /v-permission="control \? 'session:self' : 'admin_list:view'"/)
  assert.match(online, /v-permission="control \? 'session:self' : 'users:view'"/)
  assert.match(manager, /<BackendAccounts :control="true"/)
  assert.match(manager, /<OnlineUsers :control="true"/)
})
test('backend administrator create and reset forms require twelve-character passwords', () => {
  const source = fs.readFileSync(path.join(root, 'exchange-admin/src/views/AdminList.vue'), 'utf8')
  assert.equal([...source.matchAll(/value\.length < 12/g)].length, 2)
  assert.doesNotMatch(source, /value\.length < 6/)
  assert.equal([...source.matchAll(/密码长度至少12个字符/g)].length, 2)
})

test('actual insight mutation handlers enforce current grants, including Enter and confirmation revocation', async () => {
  function setup(file, grants, confirm = async () => {}) {
    const { descriptor } = parse(fs.readFileSync(path.join(root, 'exchange-admin/src', file), 'utf8'))
    const script = stripTypeScriptTypes(descriptor.scriptSetup.content.replace(/^import .*$/gm, ''))
    const calls = [], request = Object.fromEntries(['get', 'post', 'put', 'delete'].map(method => [method, async (url) => { calls.push({method, url}); throw new Error('stop after observing request') }]))
    const bindings = { ref: value => ({value}), computed: getter => ({get value() {return getter()}}), onMounted: () => {}, onUnmounted: () => {}, can: code => grants.has(code), request, defineProps: () => ({canEdit:true}), ElMessage: {error:()=>{},success:()=>{},info:()=>{},warning:()=>{}}, ElMessageBox: {confirm} }
    const names = file.includes('Calendar') ? 'sync,importData,verified,material,content' : file.includes('News') ? 'saveArticle,saveSource,sync,importData,editing,sourceEditing,reason' : file.includes('Trader') ? 'save,change,upload,saveChild,remove,importRows,editing,reason,childKind,childData,importKind,importReason,importText' : 'toggle,health'
    const state = new Function(...Object.keys(bindings), `${script}\nreturn {${names}}`)(...Object.values(bindings))
    if (state.editing) state.editing.value = {traderId:'trader',articleId:'article',rowVersion:1,data:{strategyTags:[]}}
    if (state.sourceEditing) state.sourceEditing.value = {sourceId:'source'}
    if (state.reason) state.reason.value = 'verified reason'
    if (state.importReason) {state.importReason.value = 'verified import reason';state.importText.value = '{"rows":[]}' }
    if (state.verified) {state.verified.value=true;state.material.value='material';state.content.value='content'}
    if (state.health) state.health.value = {globalEnabled:true,enabled:true}
    return {state,calls}
  }
  const cases = [
    ['views/CalendarManagement.vue','calendar:sync',s=>s.sync({sourceId:'source'})], ['views/CalendarManagement.vue','calendar:import',s=>s.importData()],
    ['views/NewsManagement.vue','news:edit',s=>s.saveArticle()], ['views/NewsManagement.vue','news:source',s=>s.saveSource()], ['views/NewsManagement.vue','news:sync',s=>s.sync({sourceId:'source'})], ['views/NewsManagement.vue','news:import',s=>s.importData()],
    ['views/TraderManagement.vue','traders:edit',s=>s.save()], ['views/TraderManagement.vue','traders:edit',s=>s.upload({target:{files:[new Blob(['fixture'])],value:'file'}})],
    ...[['publication','publish'],['disable','disable'],['recommendation','sort']].map(([endpoint,action])=>['views/TraderManagement.vue',`traders:${action}`,s=>s.change(endpoint,{})]),
    ...['equity','history'].flatMap(kind=>[
      ['views/TraderManagement.vue',`traders:${kind}`,s=>{s.childKind.value=kind;return s.saveChild()}],
      ['views/TraderManagement.vue',`traders:${kind}`,s=>s.remove(kind,{pointId:'point',recordId:'record'})],
      ['views/TraderManagement.vue',`traders:${kind}`,s=>{s.importKind.value=kind;return s.importRows()}],
    ]), ['components/MarketDepthHealth.vue','settings:save',s=>s.toggle()],
  ]
  for (const [file, capability, invoke] of cases) {
    const denied = setup(file,new Set(['traders:view','news:view','calendar:view','settings:view']))
    await invoke(denied.state); assert.deepEqual(denied.calls,[],`${file}: ${capability} denied handler sent a request`)
    const allowed = setup(file,new Set([capability]))
    await invoke(allowed.state); assert.equal(allowed.calls.length,1,`${file}: ${capability} allowed handler never reached real request layer`)
  }
  const grants = new Set(['traders:equity'])
  const revoked = setup('views/TraderManagement.vue',grants,async()=>grants.clear())
  await revoked.state.remove('equity',{pointId:'point'});assert.deepEqual(revoked.calls,[],'revocation while confirmation is open must prevent DELETE')
  const unknown = setup('views/TraderManagement.vue',new Set(['traders:publish','traders:equity','traders:history']))
  await unknown.state.change('unknown',{});unknown.state.childKind.value='unknown';await unknown.state.saveChild();unknown.state.importKind.value='unknown';await unknown.state.importRows()
  assert.deepEqual(unknown.calls,[],'unrecognized mutation kind must fail closed')
})
