// Local rendered regression: slow private images, fresh array props and session changes.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const base = process.env.ADMIN_URL
if (!base || !['127.0.0.1', 'localhost'].includes(new URL(base).hostname)) throw Error('ADMIN_URL must be an isolated local Vite server')
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAYAAACddGYaAAAAEElEQVR4XmNQCu34z4ANAAAthgH/7/5LwgAAAABJRU5ErkJggg==', 'base64')
const front = '/api/uploads/images/1/user/12/front.png', back = '/api/uploads/images/1/user/12/back.png'
const thumb = front.replace('/images/', '/thumbnails/')
;(async () => {
 const browser = await chromium.launch({ headless: true, channel: 'chrome' })
 const page = await browser.newPage({ viewport: { width: 1100, height: 750 } }), requests = [], errors = []
 let releaseFront, releaseBack
 const frontReady = new Promise(resolve => { releaseFront = resolve }), backReady = new Promise(resolve => { releaseBack = resolve })
 try {
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => sessionStorage.setItem('exchange.admin.session.v2', JSON.stringify({ mode: 'admin', token: 'image-test-one', user: { id: 7, tenantId: 1, userType: 'admin' } })))
  await page.route('**/__image_qa', route => route.fulfill({ contentType: 'text/html', body: '<!doctype html><html><head><title>Private image regression</title></head><body><div id="app"></div></body></html>' }))
  await page.route('**/api/uploads/{images,thumbnails}/**', async route => {
   const request = route.request(), pathname = new URL(request.url()).pathname
   requests.push({ pathname, token: request.headers().authorization })
   if (pathname === front || pathname === thumb) await frontReady
   if (pathname === back) await backReady
   if (pathname.endsWith('missing.png')) return route.fulfill({ status: 404, json: { message: 'Not found' } })
   await route.fulfill({ body: png, contentType: 'image/png' }).catch(() => {})
  })
  await page.goto(base + '/__image_qa')
  await page.addStyleTag({ url: base + '/node_modules/element-plus/dist/index.css' })
  await page.evaluate(async ({ front, back }) => {
   const source = await (await fetch('/src/components/ProtectedElementImage.vue')).text(), store = await (await fetch('/src/store/auth.ts')).text()
   const dependency = (text, name) => text.match(new RegExp('from ["\']([^"\']*' + name + '\\.js[^"\']*)["\']'))[1]
   const { createApp, ref, h } = await import(dependency(source, 'vue'))
   const { createPinia, setActivePinia } = await import(dependency(store, 'pinia')), pinia = createPinia(); setActivePinia(pinia)
   const element = await import(dependency(source, 'element-plus'))
   const { useAuthStore } = await import('/src/store/auth.ts')
   const { default: Image } = await import('/src/components/ProtectedElementImage.vue')
   window.__imageAuth = useAuthStore(); window.__imageState = { src: ref(front), list: ref([front, back]), repaint: ref(0) }
   const state = window.__imageState
   createApp({ setup: () => () => h('main', [h('h1', '私有图片回归'), h('button', { onClick: () => state.repaint.value++ }, '重绘 ' + state.repaint.value), h(Image, { src: state.src.value, previewSrcList: [...state.list.value], initialIndex: 0, previewTeleported: true, hideOnClickModal: true, style: 'width:100px;height:80px' }, { error: () => '真正加载失败' })]) }).use(pinia).use(element.default).mount('#app')
  }, { front, back })
  await page.getByText('加载中…', { exact: true }).waitFor()
  assert.equal(await page.getByText('真正加载失败', { exact: true }).count(), 0, 'pending is not a failure')
  await page.getByRole('button', { name: '重绘 0' }).click()
  await page.waitForTimeout(150)
  assert.equal(requests.length, 1, 'fresh preview array must not cancel/refetch thumbnail or preload preview')
  releaseFront()
  await page.waitForFunction(() => document.querySelector('.el-image img')?.naturalWidth === 3)
  await page.locator('.el-image img').click()
  await page.locator('.el-image-viewer__wrapper').waitFor()
  await page.waitForFunction(() => document.querySelector('.el-image-viewer__img')?.naturalWidth === 3)
  assert.equal(requests.filter(row => row.pathname === thumb).length, 1, 'thumbnail loaded only once')
  assert.equal(requests.filter(row => row.pathname === front).length, 1, 'original requested only when preview opens')
  await page.getByRole('button', { name: '重绘 1' }).evaluate(button => button.click())
  await page.waitForTimeout(150)
  assert.equal(requests.filter(row => row.pathname === back).length, 1, 'preview list survives equivalent parent renders')
  releaseBack()
  await page.locator('.el-image-viewer__next').click()
  await page.waitForFunction(() => document.querySelector('.el-image-viewer__img:not([style*="display: none"])')?.naturalWidth === 3)
  await page.keyboard.press('Escape')
  await page.locator('.el-image-viewer__wrapper').waitFor({ state: 'detached' })
  assert.equal(await page.locator('.el-image img').evaluate(image => image.naturalWidth), 3)
  await page.evaluate(front => { window.__imageState.list.value = [front, '/api/uploads/images/1/user/12/missing.png'] }, front)
  await page.locator('.el-image img').click()
  await page.locator('.el-image-viewer__wrapper').waitFor()
  await page.waitForTimeout(150)
  assert.equal(await page.locator('.el-image img').evaluate(image => image.naturalWidth), 3, 'broken preview does not poison thumbnail')
  await page.evaluate(() => window.__imageAuth.setAuth('image-test-two', { id: 7, tenantId: 1, userType: 'admin' }))
  await page.locator('.el-image-viewer__wrapper').waitFor({ state: 'detached' })
  await page.waitForFunction(() => document.querySelector('.el-image img')?.naturalWidth === 3)
  assert.equal(requests.at(-1).token, 'Bearer image-test-two', 'session change refetches with new bearer')
  assert(requests.every(row => row.token?.startsWith('Bearer image-test-')), 'never read private images without auth')
  await page.evaluate(() => { window.__imageState.src.value = '/api/uploads/images/1/user/12/missing.png' })
  await page.getByText('真正加载失败', { exact: true }).waitFor()
  assert.equal(await page.locator('vite-error-overlay').count(), 0)
  assert.deepEqual(errors, [])
  if (process.env.EVIDENCE_DIR) { fs.mkdirSync(process.env.EVIDENCE_DIR, { recursive: true }); await page.screenshot({ path: path.join(process.env.EVIDENCE_DIR, 'protected-image-error.png') }) }
  console.log('PASS private images: pending, single thumbnail request, stable parent redraw, deferred preview, switch/Escape, isolated failure, session refresh and bearer-only access')
 } finally { releaseFront(); releaseBack(); await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
