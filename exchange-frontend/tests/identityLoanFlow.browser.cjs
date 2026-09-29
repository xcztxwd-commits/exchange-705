const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'C:/Users/徐乾妖/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const out = path.resolve('reports/identity-unification-20260928');
fs.mkdirSync(out, { recursive: true });
const identity = { realName: 'Test User', idNumber: 'TEST-ID-001', idFrontImage: '/uploads/test.png', idBackImage: '/uploads/test.png', status: 'APPROVED', createdAt: '2026-09-28T12:00:00' };
const info = { ...identity, phone: '+819012345678', address: 'Test address', handheldImage: '/uploads/test.png' };
(async () => {
 const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
 const passed = [];
 try {
  for (const desktop of [false, true]) {
   const page = await browser.newPage({ viewport: { width: desktop ? 1440 : 390, height: 960 } });
   let kyc = 'APPROVED', status = 'REJECTED';
   const writes = [], errors = [];
   page.on('pageerror', e => errors.push(e.message));
   await page.addInitScript(() => { localStorage.setItem('locale','en'); localStorage.setItem('token','identity-ui-test'); localStorage.setItem('user',JSON.stringify({id:1,email:'fixture@example.test'})); });
   await page.route('**/uploads/**', r => r.fulfill({ contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="240" height="140"><rect width="240" height="140" rx="10" fill="#dce5ed"/><text x="22" y="55" font-family="sans-serif" font-size="20" fill="#344054">TEST ID</text><text x="22" y="88" font-family="sans-serif" font-size="13" fill="#667085">Synthetic test image only</text></svg>' }));
   await page.route('**/api/**', async r => {
    const u = new URL(r.request().url()), p = u.pathname;
    if(p.includes('/uploads/')) return r.fallback();
    if (r.request().method() === 'POST') writes.push({path:p,body:r.request().postData() || ''});
    let data = {success:true,list:[],data:[],symbols:[],orders:[],balances:[],announcements:[]};
    if(p === '/api/kyc/status') data = {kycStatus:kyc === 'APPROVED' ? 'VERIFIED' : 'NOT_VERIFIED',latestRecord:kyc === 'NONE' ? null : {...identity,status:kyc}};
    if(p === '/api/loan/personal-info/status') data = {success:true,kycVerified:kyc==='APPROVED',verified:status==='APPROVED',status,reviewRemark:status === 'REJECTED' ? 'Please update address' : '',data:kyc==='APPROVED'?info:{}};
    if(p === '/api/loan/personal-info/submit') {status='PENDING';data={success:true};}
    if(p === '/api/kyc/submit') {kyc='PENDING';data={success:true};}
    if(p === '/api/loan/settings') data={success:true,list:[{id:3,days:10,dailyRate:1,freeDays:0,minAmount:1,maxAmount:10000}]};
    if(p.endsWith('/timezone')) data={timezone:'UTC'};
    await r.fulfill({json:data});
   });
   const base = desktop ? 'http://127.0.0.1:5295' : 'http://127.0.0.1:5293';
   if (!desktop) {
    await page.goto(base+'/loan/personal-info');
    await page.getByText('Please update address',{exact:true}).waitFor();
    assert.equal(await page.locator('input[readonly]').count(),2);
    assert.equal(await page.locator('input[type=file]').count(),1);
    await page.locator('textarea').fill('Updated test address');
    await page.locator('.submit-btn').click();
    await page.waitForFunction(()=>document.querySelector('input[type=tel]')?.disabled);
    assert.equal(writes.filter(w=>w.path==='/api/loan/personal-info/submit').length,1);
    const body = writes.find(w=>w.path==='/api/loan/personal-info/submit').body;
    assert(!body.includes('name="realName"') && !body.includes('name="idNumber"') && !body.includes('name="idFrontImage"'));
    assert.equal(await page.locator('.submit-btn').count(),0);
    await page.waitForFunction(()=>Array.from(document.querySelectorAll('.upload-section img')).every(img=>img.complete && img.naturalWidth>0));
    await page.screenshot({path:path.join(out,'mobile-pending.png'),fullPage:true});
    kyc='NONE'; await page.reload();
    await page.getByText('Complete account identity verification before submitting loan details.',{exact:true}).waitFor();
    assert.equal(await page.locator('input[type=file]').count(),0);
    await page.screenshot({path:path.join(out,'mobile-needs-kyc.png'),fullPage:true});
    kyc='PENDING'; await page.goto(base+'/verification');
    await page.locator('.pending-status').waitFor();
    assert.equal(await page.locator('.verification-form').count(),0);
    passed.push('Mobile: approved identity read-only; only supplement submitted; pending locked; missing KYC blocked; pending KYC form hidden');
   } else {
    await page.goto(base+'/');
    await page.locator('.trade-page').waitFor();
    await page.evaluate(() => {
      let c = document.querySelector('.trade-page').__vueParentComponent;
      while(c && !c.setupState.loadKycStatus) c=c.parent;
      if(!c) throw new Error('Desktop component not found');
      window.identityState=c.setupState;
    });
    await page.evaluate(async()=>{ await window.identityState.loadKycStatus(); window.identityState.showUserCenter=true; window.identityState.activeUserMenu='kyc'; });
    await page.waitForFunction(()=>window.identityState.isKycVerified && window.identityState.kycForm.realName === 'Test User');
    assert.equal(await page.evaluate(()=>window.identityState.isLoanInfoVerified),false);
    await page.screenshot({path:path.join(out,'pc-kyc-approved.png')});
    await page.evaluate(async()=>{window.identityState.showUserCenter=false; await window.identityState.openLoanPersonalInfo();});
    const dialog=page.getByRole('dialog',{name:'Loan details review'});
    await dialog.waitFor();
    assert.equal(await dialog.locator('input[readonly]').count(),2);
    assert.equal(await dialog.locator('input[type=file]').count(),1);
    await dialog.locator('textarea').fill('PC test address');
    await dialog.getByRole('button').last().click();
    await page.waitForFunction(()=>window.identityState.loanInfoStatus === 'PENDING');
    assert.equal(await dialog.locator('input[type=tel]').isDisabled(),true);
    await page.screenshot({path:path.join(out,'pc-loan-pending.png')});
    kyc='REJECTED';
    await page.evaluate(async()=>{window.identityState.showPersonalInfoModal=false;await window.identityState.loadKycStatus();window.identityState.showUserCenter=true;window.identityState.activeUserMenu='kyc';});
    await page.waitForFunction(()=>window.identityState.kycStatus==='REJECTED');
    assert.equal(await page.locator('.max-w-2xl input[type=file]').count(),2);
    await page.evaluate(async()=>{window.identityState.kycForm.realName='New Test';window.identityState.kycForm.idNumber='NEW-ID';await window.identityState.submitPersonalInfoFromKyc();});
    const body=writes.find(w=>w.path==='/api/kyc/submit')?.body;
    assert(body?.includes('name="idFrontImageStr"') && !body.includes('name="phone"') && !body.includes('name="handheldImage"'));
    assert.equal(await page.evaluate(()=>window.identityState.kycStatus),'PENDING');
    assert.equal(await page.evaluate(()=>window.identityState.isLoanInfoVerified),false);
    passed.push('PC: KYC and loan state independent; immutable identity; supplement pending locked; KYC submits only /kyc/submit without placeholders');
   }
   assert.deepEqual(errors,[], 'Browser runtime errors');
   await page.close();
  }
  fs.writeFileSync(path.join(out,'browser-results.json'),JSON.stringify({passed},null,2));
  console.log(passed.join('\n'));
 } finally { await browser.close(); }
})().catch(e=>{console.error(e);process.exitCode=1;});
