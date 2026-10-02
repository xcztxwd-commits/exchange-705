import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parse } from '@vue/compiler-sfc'
import { parse as parseTemplate } from '@vue/compiler-dom'

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