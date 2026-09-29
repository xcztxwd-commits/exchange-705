const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert=require('node:assert/strict');const path=require('node:path');
// Browser plugin not available; exercise real admin UI with isolated API fixtures.
(async()=>{const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});try{
 const page=await browser.newPage({viewport:{width:1600,height:1050}}), errors=[], writes=[];
 page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(m.type()==='error')errors.push(m.text())});
 let materials=[];
 const copy=JSON.stringify({'zh-CN':{title:'测试活动',body:'领取体验金',terms:'仅供交易'}});
 let rows=[{id:1,name:'活动测试',amount:300,budget:300000,maxClaims:1000,recentLoginDays:3,claimCount:0,granted:0,status:'ACTIVE',template:false,autoSendEnabled:false,autoPopup:true,repeatUnread:false,animation:'GIFT',defaultLocale:'zh-CN',translations:copy},{id:2,name:'模板测试',amount:300,budget:300000,maxClaims:1000,recentLoginDays:3,claimCount:0,granted:0,status:'DRAFT',template:true,autoSendEnabled:false,autoPopup:true,repeatUnread:false,animation:'GIFT',defaultLocale:'zh-CN',translations:copy}];
 await page.addInitScript(()=>{localStorage.setItem('admin_token','activity-qa');localStorage.setItem('admin_user',JSON.stringify({id:1,userType:'admin'}))});
 await page.route('**/uploads/**',route=>route.fulfill({body:Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7','base64'),contentType:'image/gif'}));
 await page.route('**/api/**',async route=>{const req=route.request(),url=new URL(req.url()),p=url.pathname,method=req.method();if(p.endsWith('/upload/image'))return route.fulfill({json:{success:true,url:'/api/uploads/images/test.gif'}});if(p.endsWith('/test.gif'))return route.fulfill({body:Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7','base64'),contentType:'image/gif'});
  if(p.endsWith('/activity-materials')&&method==='GET')return route.fulfill({json:{content:materials.filter(m=>m.name.includes(url.searchParams.get('query')||'')),totalPages:1}});
  if(p.endsWith('/activity-materials')&&method==='POST'){const m={id:materials.length+1,...req.postDataJSON()};materials.push(m);return route.fulfill({json:m})}
  if(p.includes('/activity-materials/')&&method==='DELETE'){materials=materials.filter(m=>!p.endsWith('/'+m.id));return route.fulfill({json:{deleted:true}})}
  if(p.endsWith('/announcement/list'))return route.fulfill({json:[]});
  if(p.endsWith('/menus/current'))return route.fulfill({json:{success:true,menus:[{id:1,menuCode:'announcement',menuName:'公告管理',path:'/announcement'}],groups:[],actions:{announcement:['create','edit','delete','detail']}}});
  if(p.endsWith('/recipients/search'))return route.fulfill({json:url.searchParams.get('query')==='7'?[{id:7,email:'first@example.com'}]:[{id:8,email:'second@example.com'}]});
  if(p.includes('/activities')&&method!=='GET'){const body=req.postDataJSON();writes.push({p,method,body});let row=rows.find(x=>p.includes('/'+x.id));
   if(method==='DELETE'){rows=rows.filter(x=>x!==row);return route.fulfill({json:{deleted:true}})}
   if(p.endsWith('/send'))return route.fulfill({json:{sent:body.length,duplicates:0,ineligible:0}});
   if(method==='POST'){row={...body,id:3};rows.push(row)}else Object.assign(row,body);
   return route.fulfill({json:row});
  }
  if(p.endsWith('/activities')){const content=rows.filter(x=>x.template===(url.searchParams.get('template')==='true'));return route.fulfill({json:{content,totalElements:content.length,totalPages:1}})}
  return route.fulfill({json:{success:true,list:[],content:[],total:0,data:{}}});
 });
 await page.goto('http://127.0.0.1:5191/announcement');await page.getByText('活动公告 · 体验金',{exact:true}).click();await page.getByRole('button',{name:'编辑',exact:true}).click();await page.getByRole('button',{name:'打开可视化模板编辑器',exact:true}).click();
 const frame=page.frameLocator('.gjs-frame');await frame.getByText('测试活动',{exact:true}).waitFor();
 assert.equal(await page.locator('.gjs-pn-panels').isVisible(),false);
 await frame.getByRole('button',{name:'打开礼遇',exact:true}).click();await page.getByRole('button',{name:'领取并展示结果',exact:true}).click();assert.equal(await page.locator('.action-step').count(),3);await page.getByRole('button',{name:'动作下移',exact:true}).first().click();assert.equal(await page.locator('.action-step').count(),3);await page.locator('.action-step').last().scrollIntoViewIfNeeded();await page.screenshot({path:path.join(require('node:os').tmpdir(),'activity-button-actions.png'),fullPage:true,animations:'disabled'});
 await page.locator('.studio-header').getByRole('button',{name:'预览',exact:true}).click();const previewDialog=page.getByRole('dialog',{name:'页面预览（不会真实领取）'});await previewDialog.getByRole('button',{name:'打开礼遇',exact:true}).click();await previewDialog.getByText('接口：领取体验金（模拟成功）',{exact:false}).waitFor();await previewDialog.getByText('接口：标记已读（模拟成功）',{exact:false}).waitFor();await previewDialog.getByRole('button',{name:'去交易',exact:true}).waitFor();await previewDialog.getByRole('button',{name:'Close this dialog'}).click();
 await page.locator('.studio-header').getByRole('button',{name:'预览',exact:true}).click();await previewDialog.getByRole('button',{name:'打开礼遇',exact:true}).waitFor();await previewDialog.getByRole('button',{name:'Close this dialog'}).click();
 await page.screenshot({path:path.join(require('node:os').tmpdir(),'activity-studio-clean.png'),fullPage:true,animations:'disabled'});
 await frame.getByText('测试活动',{exact:true}).click();const text=page.getByRole('textbox',{name:'组件文字',exact:true});await text.fill('自由设计测试标题');await text.press('Tab');await frame.getByText('自由设计测试标题',{exact:true}).waitFor();
 const animated=page.locator('[data-material="gift-animation"]');await animated.getByRole('button',{name:'预览',exact:true}).click();
 const motion=page.locator('.full-preview [data-design-motion="gift-open"]');await motion.waitFor();assert.equal(await motion.evaluate(e=>getComputedStyle(e).animationName),'activity-gift-open');
 const before=await motion.evaluate(e=>getComputedStyle(e).transform);await page.waitForTimeout(900);const after=await motion.evaluate(e=>getComputedStyle(e).transform);assert.notEqual(before,after,'preview must actually animate');
 await page.getByRole('button',{name:'添加到页面',exact:true}).click();await frame.locator('[data-design-motion="gift-open"]').waitFor();
 await page.getByRole('button',{name:'存入素材库',exact:true}).click();await page.getByPlaceholder('素材名称').fill('我的开盖礼盒');await page.getByRole('button',{name:'保存素材',exact:true}).click();await page.locator('.material-card').filter({hasText:'我的开盖礼盒'}).waitFor();
 await page.locator('.upload input').first().setInputFiles({name:'animation.gif',mimeType:'image/gif',buffer:Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7','base64')});
 const uploaded=page.locator('.material-card').filter({hasText:'animation.gif'});await uploaded.waitFor();await uploaded.getByRole('button',{name:'添加',exact:true}).click();await frame.locator('img').waitFor();
 await page.getByRole('textbox',{name:'宽度',exact:true}).fill('120px');await page.getByRole('textbox',{name:'宽度',exact:true}).press('Tab');await page.waitForTimeout(150);assert.equal(await frame.locator('img').evaluate(e=>getComputedStyle(e).width),'120px');
 await page.getByRole('button',{name:'图层',exact:true}).click();assert.ok(await page.locator('.gjs-layer').count()>0);await page.getByRole('button',{name:'素材',exact:true}).click();
 await page.getByRole('button',{name:'页面设置',exact:true}).click();await page.getByRole('button',{name:'新增页面',exact:true}).click();await page.getByRole('button',{name:'公告详情',exact:true}).click();await page.screenshot({path:path.join(require('node:os').tmpdir(),'activity-page-presets.png'),fullPage:true,animations:'disabled'});await page.getByRole('button',{name:'使用此预设',exact:true}).click();await frame.getByText('测试活动',{exact:true}).waitFor();await page.getByRole('textbox',{name:'页面名称',exact:true}).fill('第二自定义页面');
 await page.getByRole('button',{name:'应用设计',exact:true}).click();await page.getByRole('button',{name:'保存活动',exact:true}).click();await page.getByText('活动已保存',{exact:true}).waitFor();
 const design=JSON.parse(writes.at(-1).body.layoutJson);require('node:fs').writeFileSync(path.join(require('node:os').tmpdir(),'designer-fixture.json'),JSON.stringify(design));assert.equal(design.locales['zh-CN'].pages.length,4);assert.ok(JSON.stringify(design).includes('自由设计测试标题'));assert.ok(JSON.stringify(design).includes('gift-open'));assert.ok(JSON.stringify(design).includes('\"actions\"'));assert.ok(JSON.stringify(design).includes('\"type\":\"claim\"'));assert.ok(!JSON.stringify(design).includes('blob:'));
 await page.getByRole('button',{name:'编辑',exact:true}).click();await page.getByRole('button',{name:'打开可视化模板编辑器',exact:true}).click();await frame.getByText('自由设计测试标题',{exact:true}).waitFor();await frame.locator('img').waitFor();
 await frame.locator('img').evaluate(img=>{if(img.tagName!=='IMG'||!img.complete||img.naturalWidth!==1)throw Error('GIF not decoded after reload')});
 assert.equal(await frame.locator('[data-design-motion="gift-open"]').evaluate(e=>getComputedStyle(e).animationName),'activity-gift-open');
 await page.locator('[data-material="saved-1"]').waitFor();
 await page.getByRole('button',{name:'桌面',exact:true}).click();assert.equal(await frame.getByText('专属礼遇',{exact:true}).evaluate(e=>getComputedStyle(e).fontSize),'12px');await page.getByRole('button',{name:'手机',exact:true}).click();
 // Actual pointer drag from library into the iframe, then free-position a text box.
 const oldCount=await frame.locator('[data-design-motion="gift-open"]').count();
 const thumb=await page.locator('[data-material="gift-animation"] .material-thumb').boundingBox(),canvas=await page.locator('.gjs-frame').boundingBox();
 await page.mouse.move(thumb.x+40,thumb.y+40);await page.mouse.down();await page.mouse.move(canvas.x+80,canvas.y+500,{steps:25});await page.mouse.up();await page.waitForTimeout(500);
 if(await frame.locator('[data-design-motion="gift-open"]').count()===oldCount){await page.mouse.move(thumb.x+40,thumb.y+40);await page.mouse.down();await page.mouse.move(canvas.x+canvas.width/2,canvas.y+canvas.height/2,{steps:30});await page.mouse.up();await page.waitForTimeout(500)}
 assert.equal(await frame.locator('[data-design-motion="gift-open"]').count(),oldCount+1,'library drag inserts exactly one animated material');
 await page.getByRole('button',{name:'文字',exact:true}).click();await frame.getByText('双击编辑文字',{exact:true}).waitFor();
 const textBox=frame.getByText('双击编辑文字',{exact:true});const origin=await textBox.boundingBox();
 await page.mouse.move(origin.x+60,origin.y+10);await page.mouse.down();await page.mouse.move(origin.x+100,origin.y+50,{steps:20});await page.mouse.up();await page.waitForTimeout(200);
 const moved=await textBox.boundingBox();assert.ok(Math.abs(moved.x-origin.x)>10||Math.abs(moved.y-origin.y)>10,'text box moves freely');
 const handle=page.locator('.gjs-resizer-h-br');await handle.waitFor({state:'visible'});const grip=await handle.boundingBox();const sizeBefore=await textBox.boundingBox();
 await page.mouse.move(grip.x+grip.width/2,grip.y+grip.height/2);await page.mouse.down();await page.mouse.move(grip.x+grip.width/2+45,grip.y+grip.height/2+20,{steps:15});await page.mouse.up();await page.waitForTimeout(200);const sizeAfter=await textBox.boundingBox();assert.ok(Math.abs(sizeAfter.width-sizeBefore.width)>20,'corner drag resizes text box');
 await page.screenshot({path:path.join(require('node:os').tmpdir(),'activity-studio-simple.png'),fullPage:true,animations:'disabled'});
 assert.equal(await page.locator('vite-error-overlay').count(),0);assert.deepEqual(errors,[]);console.log('PASS button flow simulation, step reorder, preset picker, editor: text edit, animated library preview, saved material reuse, GIF library upload, size editing, layers, pages, persisted design reload, responsive styles, no browser errors');
}finally{await browser.close()}})().catch(e=>{console.error(e);process.exitCode=1});
