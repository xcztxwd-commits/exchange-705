import assert from 'node:assert/strict'
import fs from 'node:fs'
import { parse } from '@vue/compiler-sfc'
import { baseParse, parserOptions } from '@vue/compiler-dom'

const root = new URL('../src/', import.meta.url)
const accountTables = new Set(['Users.1', 'Users.4', 'AgentManagement.1', 'AgentManagement.2', 'AgentPerformance.1', 'online-users', 'backend-accounts'])
let checked = 0
const attribute = (node, name) => node.props?.find(prop => prop.type === 6 && prop.name === name)?.value?.content
function visit(node, action) {
  action(node)
  for (const child of node.children || []) visit(child, action)
}
for (const file of fs.readdirSync(root, { recursive: true }).filter(file => file.endsWith('.vue'))) {
  const source = fs.readFileSync(new URL(file.replaceAll('\\', '/'), root), 'utf8')
  const template = parse(source).descriptor.template?.content
  if (!template) continue
  visit(baseParse(template, parserOptions), table => {
    if (table.tag !== 'admin-table') return
    const key = attribute(table, 'table-key'), fields = []
    for (const child of table.children || []) visit(child, column => {
      if (column.tag === 'el-table-column') fields.push(attribute(column, 'prop'))
    })
    if (!fields.includes('userId') && !fields.includes('user_id') && !accountTables.has(key)) return
    // DepositOrders declares its email and user remark in the existing column array.
    const email = fields.some(field => ['email', 'userEmail', 'user_email'].includes(field)) || (key === 'DepositOrders.1' && source.includes("['userEmail','用户邮箱']"))
    const remark = fields.some(field => ['remark', 'userRemark', 'user_remark'].includes(field)) || (key === 'DepositOrders.1' && source.includes("['userRemark','用户备注']"))
    assert(email, `${file} ${key}: user ID requires email column`)
    assert(remark, `${file} ${key}: user ID requires user remark column`)
    assert.match(source, /(?:placeholder|label)="(?:用户邮箱|邮箱)|placeholder="[^"\n]*邮箱[^"\n]*"/, `${file}: email search control missing`)
    assert.doesNotMatch(source, /row\.userRemark\s*\|\|\s*row\.remark/, `${file}: business remark is not a user remark`)
    checked++
  })
}
assert(checked >= 19, `expected all 19 user-ID tables, found ${checked}`)
console.log(`PASS ${checked} user-ID tables: email, user remark, email search; no business-remark fallback`)
