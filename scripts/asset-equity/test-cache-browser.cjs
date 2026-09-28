// Actual SFC mounted in Chromium; every API response comes from the isolated production controller.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict')
const {pathToFileURL}=require('node:url')
const root=path.resolve(__dirname,'../../exchange-frontend'),report=process.env.CACHE_TEST_REPORT
const api=process.env.CACHE_TEST_API
assert.match(api,/^http:\/\/127\.0\.0\.1:\d+$/)
const {chromium}=require(process.env.PLAYWRIGHT_PATH || 'playwright')
;(async()=>{
  const {createServer}=await import(pathToFileURL(path.join(root,'node_modules/vite/dist/node/index.js')))
  const vue=(await import(pathToFileURL(path.join(root,'node_modules/@vitejs/plugin-vue/dist/index.mjs')))).default
  const harness=`<script setup>
import {ref} from 'vue';import Chart from '${root.replaceAll('\\','/')}/src/components/AssetPixelChart.vue';import {useLocaleStore} from '${root.replaceAll('\\','/')}/src/store/locale';
const mounted=ref(true),visible=ref(true),total=ref(null),locale=useLocaleStore();
</script><template><button id="navigate" @click="mounted=!mounted">navigate</button><button id="privacy" @click="visible=!visible">privacy</button><button id="language" @click="locale.locale=locale.locale==='en'?'ja':'en'">language</button><output id="total">{{total}}</output><Chart v-if="mounted" :visible="visible" @total="total=$event"/></template>`
  const virtual=root.replaceAll('\\','/')+'/__cache-'
  const server=await createServer({root,configFile:false,envDir:path.join(report,'empty-env'),cacheDir:path.join(report,'vite-cache'),define:{'import.meta.env.VITE_API_BASE_URL':JSON.stringify('/api'),'import.meta.env.VITE_IMAGE_API_BASE_URL':JSON.stringify('/api')},plugins:[{
    name:'cache-acceptance',
    resolveId(id){if(id==='cache-harness.vue')return virtual+'harness.vue';if(id==='cache-entry')return virtual+'entry.js'},
    load(id){if(id===virtual+'harness.vue')return harness;if(id===virtual+'entry.js')return "import {createApp} from 'vue';import {createPinia} from 'pinia';import App from 'cache-harness.vue';createApp(App).use(createPinia()).mount('#app')"},
    configureServer(s){s.middlewares.use('/__cache_test',(_q,r)=>{r.setHeader('Content-Type','text/html');r.end('<html><body><div id="app"></div><script type="module" src="/@id/cache-entry"></script></body></html>')})}
  },vue()],resolve:{alias:{'@':path.join(root,'src')}},optimizeDeps:{include:['vue','pinia','axios']},server:{host:'127.0.0.1',port:0,proxy:{'/api':{target:api,changeOrigin:true}}}})
  let browser
  try{
    await server.listen();const port=server.httpServer.address().port
    browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'})
    const page=await browser.newPage({viewport:{width:420,height:700}}),errors=[],calls=[],checks=[]
    await page.route('**/*',route=>{const u=new URL(route.request().url());if(u.hostname!=='127.0.0.1')return route.abort('blockedbyclient');return route.continue()})
    page.on('pageerror',e=>{errors.push(e.message);console.error('PAGE',e.message)})
    page.on('console',m=>{if(m.type()==='error')console.error('CONSOLE',m.text())})
    page.on('requestfailed',r=>console.error('REQUEST',r.url(),r.failure()?.errorText))
    page.on('request',r=>{if(r.url().includes('/api/user/asset-history'))calls.push(new URL(r.url()).searchParams.get('range'))})
    const login=await fetch(api+'/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({account:'cache@fixture.invalid',password:'cache-fixture-only'})});assert.equal(login.status,200);const signed=await login.json()
    await page.addInitScript(({token,user})=>{localStorage.setItem('token',token);localStorage.setItem('user',JSON.stringify(user));localStorage.setItem('locale','en')},signed)
    const loaded=()=>page.waitForFunction(()=>document.querySelector('.refresh-button')&&!document.querySelector('.refresh-button').disabled&&document.querySelector('#total')?.textContent==='1000').catch(async e=>{console.error(JSON.stringify({calls,errors,body:await page.locator('body').innerText()}));throw e})
    const count=(n,step)=>{assert.equal(calls.length,n,step);checks.push({step,count:n})}
    await page.goto(`http://127.0.0.1:${port}/__cache_test`);await loaded();assert.deepEqual(calls,['1W']);count(1,'enter defaults 1W')
    const begin=Date.now();await page.waitForTimeout(60100);count(1,'idle '+(Date.now()-begin)+' ms actual wall time')
    await page.evaluate(()=>{window.dispatchEvent(new Event('focus'));document.dispatchEvent(new Event('visibilitychange'))})
    await page.setViewportSize({width:480,height:760});await page.locator('#privacy').click();await page.locator('#privacy').click();await page.locator('#language').click();await page.locator('#language').click()
    const canvas=page.locator('canvas');await canvas.click();const box=await canvas.boundingBox();await page.mouse.move(box.x+30,box.y+80);await page.mouse.down();await page.mouse.move(box.x+200,box.y+80);await page.mouse.up()
    const touch=await page.context().newCDPSession(page)
    await touch.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:box.x+50,y:box.y+50}]});await page.waitForTimeout(300)
    await touch.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:box.x+150,y:box.y+50}]});await touch.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await touch.detach()
    for(const key of ['ArrowLeft','ArrowRight','Home','End','Escape','Enter'])await canvas.press(key)
    await page.waitForTimeout(250);count(1,'focus visibility resize privacy locale click drag long-press keyboard')
    await page.locator('.periods button').nth(1).click();count(1,'same period')
    await page.locator('.periods button').nth(2).click();await loaded();count(2,'1M switch');assert.equal(calls.at(-1),'1M')
    let release,arrived;const gate=new Promise(r=>release=r),captured=new Promise(r=>arrived=r)
    await page.route('**/api/user/asset-history?range=1M',async route=>{const response=await route.fetch();arrived();await gate;await route.fulfill({response})})
    await page.locator('.refresh-button').click();await captured;await page.locator('.refresh-button').evaluate(e=>{e.click();e.click()});count(3,'refresh inflight duplicates blocked');release();await loaded();await page.unroute('**/api/user/asset-history?range=1M')
    await page.locator('#navigate').click();assert.equal(await canvas.count(),0)
    let releaseOld,oldArrived;const oldGate=new Promise(r=>releaseOld=r),oldCaptured=new Promise(r=>oldArrived=r)
    await page.route('**/api/user/asset-history?range=1W',async route=>{const response=await route.fetch();oldArrived();await oldGate;await route.fulfill({response})})
    await page.locator('#navigate').click();await oldCaptured;count(4,'reenter once 1W')
    await page.waitForTimeout(1100);await page.locator('.periods button').nth(2).click();await loaded();count(5,'switch while old response pending');const status=await page.locator('.status').allTextContents()
    releaseOld();await page.waitForTimeout(400);assert.deepEqual(await page.locator('.status').allTextContents(),status);count(5,'late old response cannot replace latest snapshot')
    await page.screenshot({path:path.join(report,'browser-chart.png')});assert.deepEqual(errors,[])
    fs.writeFileSync(path.join(report,'browser-results.json'),JSON.stringify({result:'PASS',browser:await browser.version(),calls,checks,errors,network:'real isolated login, JWT and asset-history endpoint; responses delayed but not mocked'},null,2))
  }finally{if(browser)await browser.close();await server.close()}
})().catch(e=>{console.error(e);process.exitCode=1})
