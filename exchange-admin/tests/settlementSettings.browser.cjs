// Browser plugin skill not available; isolated Playwright fixture test.
const { chromium } = require(process.env.PLAYWRIGHT_PATH)
const assert = require('node:assert/strict')
const path = require('node:path'), os = require('node:os')
;(async () => {
  const browser = await chromium.launch({headless:true, executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'})
  try {
    const page = await browser.newPage({viewport:{width:1360,height:900}}), errors=[]
    let saved = null, hours = null, currencies = null
    page.on('pageerror',error=>errors.push(error.message))
    await page.addInitScript(()=>{localStorage.setItem('admin_token','isolated-test');localStorage.setItem('admin_user',JSON.stringify({id:1,role:'super_admin',isSuperAdmin:true}))})
    await page.route('**/api/**',async route=>{
      const request=route.request(), url=new URL(request.url()); let body={list:[],total:0}
      if(url.pathname.endsWith('/config/list')) body=hours===null?[]:[{configKey:'market.conversion.cache-hours',configValue:String(hours)},{configKey:'market.conversion.currencies',configValue:currencies}]
      if(url.pathname.endsWith('/config/saveBatch')) {saved=request.postDataJSON();hours=Number(saved.find(item=>item.key==='market.conversion.cache-hours').value);currencies=saved.find(item=>item.key==='market.conversion.currencies').value;body={message:'配置保存成功'}}
      await route.fulfill({json:body})
    })
    await page.goto('http://127.0.0.1:17051/#/settings')
    await page.getByRole('tab',{name:'结算汇率',exact:true}).click()
    const input=page.getByRole('spinbutton',{name:'汇率更新间隔（小时）',exact:true})
    assert.equal(await input.inputValue(),'8')
    const currencySelect=page.getByRole('combobox',{name:'缓存币种（兑美元）',exact:true})
    await currencySelect.click()
    await page.getByRole('option',{name:'NOK · 挪威克朗',exact:true}).click()
    await currencySelect.press('Escape')
    await input.fill('12');await input.press('Tab')
    await page.getByRole('button',{name:/保存/}).click()
    await page.getByText('配置保存成功',{exact:true}).waitFor()
    assert.equal(hours,12)
    assert.equal(currencies.split(',').length,11)
    for(const code of ['USD','CNY','SGD','NOK']) assert.ok(currencies.split(',').includes(code))
    await page.reload();await page.getByRole('tab',{name:'结算汇率',exact:true}).click()
    assert.equal(await input.inputValue(),'12')
    assert.ok(await page.getByText('NOK · 挪威克朗',{exact:true}).first().isVisible())
    await page.getByRole('button',{name:'恢复默认币种',exact:true}).click()
    await Promise.all([page.waitForResponse(response=>response.url().endsWith('/config/saveBatch')),page.getByRole('button',{name:/保存配置/}).click()])
    assert.equal(currencies.split(',').length,10)
    assert.ok(!currencies.split(',').includes('NOK'))
    assert.equal(await input.getAttribute('aria-valuemin'),'1')
    assert.equal(await input.getAttribute('aria-valuemax'),'168')
    assert.match(await page.title(),/GTCFX/)
    assert.match(page.url(),/#\/settings$/)
    assert.deepEqual(errors,[])
    const screenshot=path.join(os.tmpdir(),'705-settlement-settings.png')
    await page.screenshot({path:screenshot,fullPage:false})
    console.log(JSON.stringify({result:'PASS',checks:'default, edit, save, reload, bounds, no page errors',screenshot}))
  } finally {await browser.close()}
})().catch(error=>{console.error(error);process.exit(1)})
