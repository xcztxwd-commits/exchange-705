// PC/mobile Vite servers: 5217 / 5218. All API/WebSocket traffic is mocked; no real orders.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path')
const output = path.resolve(__dirname, '../reports/ja-pages')
const languages = ['zh-TW', 'en', 'fr', 'de', 'ru', 'es', 'pt', 'it', 'ar', 'tr', 'id', 'my', 'hi', 'cs', 'pl', 'ja', 'ko', 'th', 'vi']
;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  const results = []
  try {
    for (const app of ['pc', 'mobile']) {
      const port = app === 'pc' ? 5217 : 5218
      const page = await browser.newPage({ viewport: app === 'pc' ? { width: 1500, height: 1000 } : { width: 390, height: 844 }, isMobile: app === 'mobile', hasTouch: app === 'mobile' })
      const errors = [], businessWrites = []
      page.on('pageerror', error => errors.push(error.message))
      await page.addInitScript(() => {
        localStorage.setItem('token', 'i18n-local-test-fixture')
        localStorage.setItem('user', JSON.stringify({ id: 1, email: 'fixture@example.invalid' }))
        if (!localStorage.getItem('locale')) localStorage.setItem('locale', 'ja')
      })
      const quote = () => ({ XAUUSD: { price: 100, quoteToUsdRate: 1, conversionAvailable: true, conversionExpiresAt: Date.now() + 60000, timestamp: Date.now(), fetchedAt: Date.now(), expiresAt: Date.now() + 60000, status: 'available', sourceAvailable: true } })
      await page.routeWebSocket('**/api/ws/market', socket => socket.onMessage(raw => {
        const message = JSON.parse(raw)
        if (message.action === 'subscribe') socket.send(JSON.stringify({ type: 'price', data: quote() }))
        if (message.action === 'ping') socket.send(JSON.stringify({ type: 'pong' }))
      }))
      const product = { id: 1, name: '検証用商品', description: '検証用の商品説明です。', currency: 'USD', dailyYieldRate: .2, termDays: 30, minPurchase: 10, maxPurchase: 10000, rentalFee: 1 };
      const loan = { id: 1, amount: 1000, realName: '検証 太郎', address: '検証用住所', phone: '+810000000000', days: 30, dailyRate: .18, overdueRate: .25, totalInterest: 54, totalRepayment: 1054, contractSigned: false, status: 'APPROVED', createdAt: '2026-09-20T12:00:00Z' };
      await page.route('**/*', async route => {
        const request = route.request(), url = new URL(request.url())
        if (!url.pathname.startsWith('/api/')) return url.hostname === '127.0.0.1' ? route.continue() : route.abort()
        if (request.method() !== 'GET' && !url.pathname.endsWith('/heartbeat') && !url.pathname.endsWith('/price/batch') && !/\/market\/(redis\/)?kline\/batch$/.test(url.pathname)) businessWrites.push(url.pathname)
        let body = { success: true, list: [], data: [] }
        if (/\/market\/(all|symbols|search)$/.test(url.pathname)) body = { list: [{ symbol: 'XAUUSD', alltickSymbol: 'XAUUSD', category: 'Metal', quoteCurrency: 'USD', lotSize: 1000, feeMultiplier: 30, maxLeverage: 100, leverageEnabled: true, pricePrecision: 2 }] }
        else if (url.pathname.endsWith('/categories')) body = { list: [{ key: 'Metal', label: 'Metal' }, { key: 'Forex', label: 'Forex' }, { key: 'US', label: 'US' }] }
        else if (url.pathname.endsWith('/price/batch')) body = { ret: 200, data: quote() }
        else if (url.pathname.endsWith('/user/assets')) body = { success: true, contractBalance: 10000, fundBalance: 10000, optionBalance: 10000 }
        else if (url.pathname.endsWith('/balance')) body = { success: true, balance: 10000, available: 10000 }
        else if (url.pathname.endsWith('/durations')) body = [{ duration: 60, label: '60秒', profitRate: .8, lossRate: 1, minAmount: 1, maxAmount: 10000 }]
        else if (url.pathname.endsWith('/timezone')) body = { timezone: 'UTC' }
        else if (url.pathname.endsWith('/currencies')) body = { rates: {} }
        else if (url.pathname.includes('/kline/') && !url.pathname.endsWith('/batch')) {
          const count = Number(url.searchParams.get('limit')) || 200, end = Math.floor(Date.now() / 300000) * 300000
          body = { ret: 200, data: { status: 'available', kline_list: Array.from({ length: count }, (_, i) => ({ timestamp: end - (count - i - 1) * 300000, open_price: 100, close: 100, close_price: 100, high_price: 101, low_price: 99, volume: 10 })) } }
        }
        if (url.pathname === '/api/financial/products') body={success:true,list:[product]};
        if (/\/financial\/product\/\d+$/.test(url.pathname)) body={success:true,data:product};
        if (/\/loan\/\d+$/.test(url.pathname)) body={success:true,data:loan};
        if (url.pathname.endsWith('/loan/list')) body={success:true,list:[loan]};
        if (url.pathname.endsWith('/loan/settings')) body={success:true,list:[{...loan,minAmount:100,maxAmount:10000,freeDays:0}]};
        if (url.pathname.endsWith('/personal-info/status')) body={success:true,verified:false,data:{}};
        if (url.pathname.endsWith('/deposit/settings/list')) body={success:true,list:[{id:1,currency:'USDT',network:'TRC20',address:'TEST-ONLY-ADDRESS'}]};
        if (url.pathname.endsWith('/deposit/settings/bank')) body={success:true,data:{bankName:'テスト銀行',bankAccount:'TEST-ONLY',recipientName:'検証用名義',currency:'USD',swift:'TESTONLY'}};
        if (url.pathname.endsWith('/deposit/settings')) body={success:true,data:{id:1,currency:'USDT',network:'TRC20',address:'TEST-ONLY-ADDRESS'}};
        if (url.pathname.endsWith('/user/invite/info')) body={success:true,inviteCode:'TESTONLY',inviteLink:'https://example.invalid/register?code=TESTONLY'};
        if (url.pathname.endsWith('/wallet/bank-cards')||url.pathname.endsWith('/wallet/digital-addresses')) body={success:true,list:[]};
        if (url.pathname.endsWith('/kyc/status')) body={kycStatus:'NOT_VERIFIED'};
        if (url.pathname.endsWith('/user/share-templates')) body=['light'];
        if(url.pathname.endsWith('/user/asset-history')) body={points:[{time:Date.now()-60000,value:'10000'},{time:Date.now(),value:'10000'}],total:'10000',from:Date.now()-86400000,intervalMs:60000,asOf:Date.now(),income:'0',incomePercent:'0',timezone:'UTC'};
        if(/\/trade\/(contract|option)\/orders$/.test(url.pathname)){const status=url.pathname.includes('/option/') ? 'CLOSED' : url.searchParams.get('status')||'OPEN'; body={list:[{id:1,symbol:'XAUUSD',category:'Metal',status,side:'BUY',direction:'UP',quantity:.1,lots:.1,leverage:10,margin:100,amount:100,fee:1,openPrice:100,closePrice:101,profit:10,settlementCurrency:'USD',openTime:'2026-09-20T12:00:00Z',closeTime:'2026-09-20T13:00:00Z',createdAt:'2026-09-20T12:00:00Z',duration:60,profitRate:.8,lossRate:1}]};}
        if(url.pathname.endsWith('/financial/orders')) body={success:true,list:[{id:1,productName:'検証用商品',status:'IN_PROGRESS',currency:'USD',purchaseAmount:100,dailyYield:.2,totalYield:6,termDays:30,purchaseTime:'2026-09-20T12:00:00Z',penaltyRate:5}]};
        if(/\/financial\/penalty\//.test(url.pathname))body={success:true,penalty:5,penaltyAmount:5};
        if(/\/financial\/yield\/order\//.test(url.pathname))body={success:true,list:[{id:1,status:'PAID',dailyYield:.2,cumulativeYield:.2,yieldDate:'2026-09-20T12:00:00Z',paidAt:'2026-09-20T12:00:00Z'}],stats:{totalYield:.2,paidYield:.2,pendingYield:0,recordCount:1}};
        await route.fulfill({ json: body })
      })
      const base = `http://127.0.0.1:${port}`
      await page.goto(base + (app === 'pc' ? '/' : '/trade?symbol=XAUUSD&category=Metal&tab=contract'))
      await page.locator('.chart-workspace').waitFor()
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent.setupState.chart)
      const pages = [];
      async function capture(name) {
        await page.locator('.forex-transition').waitFor({ state: 'hidden' }).catch(() => {});
        await page.waitForTimeout(120);
        const text = await page.locator('body').innerText();
        const attrs = await page.locator('[placeholder], [aria-label], img[alt]').evaluateAll(elements => elements.filter(e => e.getBoundingClientRect().width).map(e => [e.getAttribute('placeholder'), e.getAttribute('aria-label'), e.getAttribute('alt')].filter(Boolean).join(' '))).then(x=>x.join('\n'));
        assert.ok(text.trim(), name+' blank page');
        assert.equal(await page.locator('html').getAttribute('lang'), 'ja');
        const filename=name.replace(/[^a-z0-9-]/gi,'_');
        await page.screenshot({path:path.join(output,`${app}-${filename}.png`),fullPage:true});
        fs.writeFileSync(path.join(output,`${app}-${filename}.txt`),text+'\n\nATTRIBUTES\n'+attrs);
        const leaks=(text+'\n'+attrs).split('\n').filter(t=>/部分数据源|正在重试|訂單頁面|您的訂單|確認修改|資產走勢|Refreshing|No data|Please enter|Welcome to use|This week|This month|Today’s|Unable to load|Recording real|Assets hidden|Hold and drag|Close this dialog|decrease number|increase number|\bparameter \d|本人確認を完了認証|発効日日付/.test(t));
        const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+2);
        pages.push({name,url:page.url(),characters:text.length,leaks,horizontalOverflow:overflow});
      }
      async function state(selector,patch) {
        await page.evaluate(({selector,patch})=>{let n=document.querySelector(selector)?.__vueParentComponent;while(n&&!Object.keys(patch).every(k=>k in n.setupState))n=n.parent;if(!n)throw Error('State not found: '+selector+' '+Object.keys(patch));Object.assign(n.setupState,patch)}, {selector,patch});
        await page.waitForTimeout(400);
      }
      for(const panel of ['indicators','settings','timezone']){
        await page.evaluate(panel=>document.querySelector('.chart-workspace').__vueParentComponent.setupState.openPanel(panel),panel);
        await page.locator('.chart-dialog[open]').waitFor();
        if(panel==='indicators') {
          const names=await page.locator('.indicator-card strong').allTextContents();
          assert.equal(names.length,26);assert.ok(names.includes('SMA')&&names.includes('TRIX')&&names.includes('PVT'));
          const copy=await page.locator('.chart-dialog[open]').innerText();
          for(const expected of ['TRIX（トリックス）','PVT（プライス・ボリューム・トレンド）','単純移動平均線とは異なります'])assert.ok(copy.includes(expected),expected);
          const clipped=await page.locator('.indicator-card small').evaluateAll(nodes=>nodes.filter(n=>n.scrollWidth>n.clientWidth+2).map(n=>n.textContent));
          assert.deepEqual(clipped,[], 'Indicator descriptions clipped');
        }
        await capture('chart-'+panel);await page.keyboard.press('Escape');
      }
      if(app==='mobile') {
        const source=fs.readFileSync(path.resolve(__dirname,'../exchange-frontend/src/router/index.ts'),'utf8');
        const routes=[...source.matchAll(/\{ path: '([^']+)', component:/g)].map(m=>m[1]);
        for(const route of routes){
          if(route==='/login')await page.evaluate(()=>{localStorage.removeItem('token');document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('auth').token=''});
          const query=route==='/loan/contract'||route==='/loan/sign'||route==='/financial/purchase'?'?id=1':route==='/financial/yield-list'?'?orderId=1':'';
          // Use router navigation so an intentionally logged-out page stays logged out.
          await page.evaluate(url=>document.querySelector('#app').__vue_app__.config.globalProperties.$router.push(url),route+query);
          await page.waitForTimeout(220);
          assert.equal(new URL(page.url()).pathname,route,route+' unexpectedly redirected');
          if(route==='/loan/contract'){const copy=await page.locator('.contract-box').innerText();assert.ok(copy.includes('日利率を0.18%'));assert.ok(!copy.includes('18.00%'));}await capture(route);
          if(route==='/complaint') {
            await state('.complaint-page',{loading:false,emailAvailable:false,complaintEmail:'',loadError:false});
            assert.ok((await page.locator('body').innerText()).includes('苦情受付メールアドレスが設定されていません'));await capture('complaint-unconfigured');
            await state('.complaint-page',{loadError:true});
            assert.ok((await page.locator('body').innerText()).includes('苦情受付メールアドレスを取得できませんでした'));await capture('complaint-failed');
            await state('.complaint-page',{loadError:false,complaintEmail:'fixture@example.invalid'});
            assert.ok((await page.locator('body').innerText()).includes('現在この連絡先をご利用いただけません'));await capture('complaint-unavailable');
          }
          if(route==='/orders'){for(const tab of ['pending','history']){await state('.orders-page',{subTab:tab});await capture('orders-'+tab)} await state('.orders-page',{mainTab:'term'});await capture('orders-term');}
          if(route==='/trade'){await state('.trade-page',{showConfirm:true});await capture('term-confirm');await state('.trade-page',{showConfirm:false,activeTab:'contract'});await capture('contract-trade');await state('.trade-page',{showConfirm:true});await capture('contract-confirm');await state('.trade-page',{showConfirm:false});for(const flag of ['showSymbols','showRules','showRisk','showEstimates']){await state('.trade-page',{[flag]:true});await capture('trade-'+flag);await state('.trade-page',{[flag]:false})}}
          if(route==='/financial/orders'){await page.locator('.redeem-btn').first().click();await page.locator('.redeem-dialog').waitFor();await capture('financial-redeem');}

          if(route==='/verification')for(const status of ['PENDING','REJECTED','VERIFIED']){await state('.verification-page',{kycStatus:status==='VERIFIED'?status:'NOT_VERIFIED',latestRecord:{status,realName:'検証 太郎',idNumber:'TESTONLY',reviewRemark:'検証用の理由です'}});await capture('verification-'+status)}
          if(route==='/financial/purchase'){await state('.financial-purchase-page',{purchaseAmount:'100'});await page.locator('.purchase-btn').click();await page.locator('.confirm-dialog').waitFor();assert.ok((await page.locator('.confirm-dialog').innerText()).includes('予想日次収益額'));await capture('financial-confirm')}
        }
      } else {
        await capture('trade');
        const dialogs=['showFinancialPurchase','showLoanContract','showLoanSign','showCreditLoan','showLoanRecordsModal','showWealth','showYieldList','showUserCenter','showTpSlModal','showPersonalInfoModal','showBindBankModal','showBindDigitalModal','showLoginModal','showRegisterModal','showForgotModal'];
        await state('.trade-page',{activeFinancialProduct:product,currentLoanRecord:loan,activeOrder:{id:1,symbol:'XAUUSD',openPrice:100,lots:1,side:'BUY'},userLoanRecords:[loan]});
        for(const flag of dialogs){
          await state('.trade-page',Object.fromEntries(dialogs.map(k=>[k,k===flag])));
          await capture(flag);
          if(flag==='showUserCenter')for(const menu of ['assets','deposit','withdraw','transfer','wallet','kyc','announcement','invite','password','support']){
            await state('.trade-page',{activeUserMenu:menu}); await capture('user-'+menu);
            if(menu==='deposit'||menu==='withdraw'){await state('.trade-page',{[menu+'Tab']:'bank'});await capture('user-'+menu+'-bank')}
            if(menu==='kyc')for(const status of ['PENDING','VERIFIED']){await state('.trade-page',{kycStatus:status});await capture('user-kyc-'+status)}
          }
          if(flag==='showWealth'){await state('.trade-page',{wealthTab:'purchased'});await capture('wealth-purchased')}
        }
        await state('.trade-page',Object.fromEntries(dialogs.map(k=>[k,false])));
        await state('.trade-page',{tradeMode:'option'});await capture('term-trade');
        await state('.trade-page',{shareOrder:{id:1,kind:'contract'}});await page.locator('.pnl-save:not([disabled])').waitFor();await capture('share-poster');await state('.trade-page',{shareOrder:null});
        await page.goto(base+'/language');await capture('language');
      }
      results.push({app,pages,runtimeErrors:errors,businessWrites});
      fs.writeFileSync(path.join(output,'results.json'),JSON.stringify(results,null,2));
      await page.close();
    }
    console.log(JSON.stringify(results.map(r=>({app:r.app,pages:r.pages.length,leaks:r.pages.filter(p=>p.leaks.length),overflow:r.pages.filter(p=>p.horizontalOverflow),runtimeErrors:r.runtimeErrors,businessWrites:r.businessWrites})),null,2));
    assert.ok(results.every(r=>!r.runtimeErrors.length&&!r.businessWrites.length&&!r.pages.some(p=>p.leaks.length)), 'Page validation failed');
  } finally { await browser.close() }
})().catch(error=>{console.error(error);process.exitCode=1});
