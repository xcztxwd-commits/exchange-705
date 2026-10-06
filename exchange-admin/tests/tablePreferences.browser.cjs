// Real shared AdminTable, local Vite synthetic preferences only; never connects to production.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const base = process.env.COLUMN_QA_URL
if (!base || !['127.0.0.1','localhost'].includes(new URL(base).hostname)) throw Error('COLUMN_QA_URL must be an isolated loopback fixture')
const output = process.env.ADMIN_QA_OUTPUT || path.resolve(__dirname, '../../reports/column-browser')
;(async () => {
  fs.mkdirSync(output, {recursive:true})
  const browser = await chromium.launch({headless:true, executablePath:process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'})
  const page = await browser.newPage(), errors=[], checks=[]
  page.on('pageerror', error=>{errors.push(error.message);console.error('BROWSER_PAGE_ERROR',error.message)})
  page.on('console', message=>{if(message.type()==='error')console.error('BROWSER_CONSOLE_ERROR',message.text())})
  const dialog=page.getByRole('dialog',{name:'表格列设置',exact:true})
  const rows = () => dialog.locator('.admin-table-column').evaluateAll(nodes=>nodes.map(node=>({label:node.querySelector('label').textContent.trim(), visible:node.querySelector('input').checked, fixed:node.querySelector('select').value})))
  const open = async()=>{await page.getByRole('button',{name:'列设置',exact:true}).click();await dialog.waitFor()}
  const cancel = ()=>dialog.getByRole('button',{name:'取消',exact:true}).click()
  const reload = async()=>{await page.getByRole('button',{name:'重新加载列设置',exact:true}).click();await page.getByRole('button',{name:'列设置',exact:true}).waitFor()}
  const choose = async(label,value)=>{const ready=page.waitForResponse(r=>r.request().method()==='GET' && r.url().includes('/__preferences/'));await page.getByLabel(label,{exact:true}).selectOption(value);await ready;await page.getByRole('button',{name:'列设置',exact:true}).waitFor()}
  try {
    await page.goto(base);await open();const original=await rows()
    assert.equal(original.find(row=>row.label==='年收入').visible,false)
    assert.equal(original.find(row=>row.label==='用户类型').fixed,'left')
    assert.equal(original.findIndex(row=>row.label==='手机号'),2)
    checks.push('captured mojibake restores visibility/order/fixed')
    await dialog.getByLabel('手机号固定位置',{exact:true}).selectOption('left')
    await dialog.getByRole('button',{name:'手机号前移',exact:true}).click()
    await dialog.getByText('登录IP / 地区',{exact:true}).click()
    const expected=await rows(); await dialog.getByRole('button',{name:'保存',exact:true}).click();await dialog.waitFor({state:'hidden'})
    const payload=JSON.parse(await page.getByTestId('saved-payload').textContent())
    assert(payload.success);assert(payload.columns.every(row=>/^[A-Za-z0-9_.-]+$/.test(row.id)))
    await page.reload();await open();assert.deepEqual(await rows(),expected);await cancel()
    checks.push('English IDs saved, refresh keeps complete visibility/order/fixed')
    await open();await dialog.getByRole('button',{name:'恢复默认',exact:true}).click();await cancel();await reload();await open();assert.deepEqual(await rows(),expected);await cancel()
    checks.push('cancel and unsaved reset never replace persistence')
    await choose('测试账号','2');await open();assert((await rows()).every(row=>row.visible));await cancel();await choose('测试账号','1');await open();assert.deepEqual(await rows(),expected);await cancel()
    await choose('测试表格','Orders.1');await open();assert((await rows()).every(row=>row.visible));await cancel();await choose('测试表格','Users.1');await open();assert.deepEqual(await rows(),expected);await cancel()
    checks.push('account and table preferences remain isolated')
    await choose('身份入口','control');await open();assert((await rows()).every(row=>row.visible));await dialog.getByText('手机号',{exact:true}).click();const control=await rows();await dialog.getByRole('button',{name:'保存',exact:true}).click();await dialog.waitFor({state:'hidden'});await page.reload();await open();assert.deepEqual(await rows(),control);await cancel();await choose('身份入口','admin');await open();assert.deepEqual(await rows(),expected);await cancel()
    checks.push('independent control preferences save/refresh without ordinary identity leakage')
    for (const mode of ['saveFalse','saveError']) {
      await page.getByLabel('接口测试模式',{exact:true}).selectOption(mode);await open();await dialog.getByText('年收入',{exact:true}).click();const draft=await rows();await dialog.getByRole('button',{name:'保存',exact:true}).click()
      await page.getByText(mode==='saveFalse'?'服务端未确认保存成功，请重试':'模拟保存失败',{exact:true}).waitFor()
      assert(await dialog.isVisible());assert.deepEqual(await rows(),draft);await cancel();await page.getByLabel('接口测试模式',{exact:true}).selectOption('normal');await reload();await open();assert.deepEqual(await rows(),expected);await cancel()
    }
    checks.push('success:false and HTTP503 preserve draft, never report save success or replace stored columns')
    await open();for(const input of await dialog.locator('input[type=checkbox]').all()) await input.uncheck();await dialog.getByRole('button',{name:'保存',exact:true}).click();await page.getByText('至少保留一列',{exact:true}).waitFor();assert(await dialog.isVisible());await cancel()
    checks.push('all hidden columns cannot be saved')
    await page.getByLabel('接口测试模式',{exact:true}).selectOption('invalid');await page.getByRole('button',{name:'重新加载列设置',exact:true}).click();await page.getByRole('alert').filter({hasText:'列配置响应无效，请重试'}).waitFor();assert.equal(await page.locator('.el-table').count(),0)
    await page.getByLabel('接口测试模式',{exact:true}).selectOption('normal');await page.getByRole('button',{name:'重试',exact:true}).click();await open();assert.deepEqual(await rows(),expected);await cancel()
    checks.push('malformed read prevents default flash/overwrite; retry restores saved columns')
    await page.getByLabel('接口测试模式',{exact:true}).selectOption('slow');const oldResponse=page.waitForResponse(r=>r.url().includes('/__preferences/admin/1/Users.1?mode=slow'));await page.getByRole('button',{name:'重新加载列设置',exact:true}).click();await page.getByText('正在恢复列设置…',{exact:true}).waitFor();assert.equal(await page.locator('.el-table').count(),0)
    await page.getByLabel('接口测试模式',{exact:true}).selectOption('normal');await choose('测试账号','2');await oldResponse;await open();assert((await rows()).every(row=>row.visible));await cancel();await choose('测试账号','1');await open();assert.deepEqual(await rows(),expected);await cancel()
    checks.push('slow read shows loading only; confirmed late old-identity HTTP200 cannot overwrite new account')
    const fresh=await browser.newPage();await fresh.goto(base+'?scope=admin&actor=1&table=Users.1');await fresh.getByRole('button',{name:'列设置',exact:true}).click();const freshRows=await fresh.getByRole('dialog',{name:'表格列设置'}).locator('.admin-table-column').evaluateAll(nodes=>nodes.map(node=>({label:node.querySelector('label').textContent.trim(),visible:node.querySelector('input').checked,fixed:node.querySelector('select').value})));assert.deepEqual(freshRows,expected);await fresh.close()
    checks.push('new tab reads server persistence, not component memory')
    assert.deepEqual(errors,[]);await page.screenshot({path:path.join(output,'columns.png')});fs.writeFileSync(path.join(output,'result.json'),JSON.stringify({pass:true,checks,errors,scope:'real Chrome/AdminTable with local synthetic API; no production or backend/MySQL acceptance'},null,2));console.log('PASS '+checks.length+' grouped column-browser checks: '+checks.join('; '))
  } catch(error) {await page.screenshot({path:path.join(output,'failure.png')}).catch(()=>{});throw error}
  finally {await browser.close()}
})().catch(error=>{console.error(error);process.exitCode=1})
