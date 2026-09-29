const {chromium}=require(process.env.PLAYWRIGHT_PATH || 'C:/Users/徐乾妖/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright')
const assert=require('node:assert/strict'),fs=require('node:fs')
fs.mkdirSync('reports/share-redesign-20260928',{recursive:true})
;(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true})
 try{
 const page=await browser.newPage({viewport:{width:1500,height:1100}});let saved=[],config='gold,light';const errors=[]
 page.on('pageerror',e=>errors.push(e.message))
 await page.route('**/__qa',r=>r.fulfill({contentType:'text/html',body:'<!doctype html><body><div id="app"></div>'}))
 await page.route('**/api/admin/config/get?*',r=>r.fulfill({json:{value:config}}))
 await page.route('**/api/admin/config/save',r=>{const data=r.request().postDataJSON();saved.push(data);config=data.value;return r.fulfill({json:{success:true}})})
 await page.goto('http://127.0.0.1:5194/__qa')
 const mount=async()=>page.evaluate(async()=>{
  const source=await(await fetch('/src/components/ShareTemplateSettings.vue')).text(),auth=await(await fetch('/src/store/auth.ts')).text()
  const dep=(s,n)=>s.match(new RegExp('from ["\']([^"\']*'+n+'\\.js[^"\']*)["\']'))[1]
  const {createApp}=await import(dep(source,'vue')),element=await import(dep(source,'element-plus')),{createPinia}=await import(dep(auth,'pinia')),{default:Settings}=await import('/src/components/ShareTemplateSettings.vue')
  await import('/node_modules/element-plus/dist/index.css')
  window.app=createApp(Settings).use(createPinia()).use(element.default);window.app.mount('#app')
 })
 await mount();await page.locator('.el-table__row').first().waitFor()
 await page.waitForFunction(()=>document.querySelectorAll('.preview-thumbnail img').length===16)
 assert.ok(await page.getByRole('radio',{name:'盈亏为重心',exact:true}).isChecked())
 const originalPreview=await page.locator('.preview-thumbnail img').first().getAttribute('src')
 await page.getByText('收益率为重心',{exact:true}).click()
 await page.waitForFunction(original=>document.querySelector('.preview-thumbnail img')?.src!==original,originalPreview)

 const first=page.locator('.el-table__row').nth(0)
 await first.locator('label.el-checkbox').click()
 await page.getByRole('button',{name:'保存模板配置'}).click();assert.equal(saved.length,0)
 await first.locator('.el-select').click();await page.locator('.el-select-dropdown__item').filter({hasText:'日语'}).last().click();await page.locator('p').first().click()
 await page.getByRole('button',{name:'保存模板配置'}).click();await page.waitForTimeout(400)
 assert.equal(saved.length,1);assert.deepEqual(JSON.parse(saved[0].value),{version:2,focus:'rate',templates:[{id:'gold',languages:['ja']},{id:'light',languages:['*']}]})
 await page.locator('.el-select').first().click();await page.locator('.el-select-dropdown__item:visible').filter({hasText:'日语'}).click();await page.locator('p').first().click();assert.ok((await page.locator('p').filter({hasText:'默认：'}).innerText()).includes('默认：黑金'));await page.waitForTimeout(3200)
 await page.screenshot({path:'reports/share-redesign-20260928/admin-language-settings.png',fullPage:true})
 await page.locator('.el-table__row').nth(1).locator('.el-switch').click();await page.getByRole('button',{name:'保存模板配置'}).click();assert.equal(saved.length,1)
 await page.evaluate(()=>{window.app.unmount();document.querySelector('#app').innerHTML=''})
 await mount();await page.locator('.el-table__row').first().waitFor()
 assert.ok(await page.getByRole('radio',{name:'收益率为重心',exact:true}).isChecked());
 assert.equal(await page.locator('.el-table__row').first().getByLabel('全语言通用').isChecked(),false)
 assert.ok((await page.locator('.el-table__row').first().innerText()).includes('日语'))
 assert.deepEqual(errors,[]);fs.writeFileSync('reports/share-redesign-20260928/admin-results.json',JSON.stringify({focusPreview:true,focusSaveAndReload:true,legacyLoad:true,languageSelection:true,universal:true,saveAndReload:true,coverageValidation:true,errors},null,2));console.log('Admin language settings passed')
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exit(1)})
