// Full-screen version of forex-transition-1s.html. Keep this file in sync with
// exchange-frontend/public/forex-transition.js (the apps build independently).
(() => {
  'use strict'

  const DURATION = 1000
  const holdForRedirect = document.currentScript?.hasAttribute('data-hold') ?? false
  const style = document.createElement('style')
  style.textContent = `
    .forex-transition { position: fixed; inset: 0; z-index: 2147483647; display: grid; place-items: center; overflow: hidden; isolation: isolate; background: #fff; }
    .forex-transition[hidden] { display: none; }
    .forex-transition__composition { position: absolute; inset: 0; display: grid; place-items: center; }
    .forex-transition__logo { position: relative; width: 53.65%; max-width: 1050px; opacity: 0; transform-origin: center; }
    .forex-transition__logo img { display: block; width: 100%; height: auto; user-select: none; -webkit-user-drag: none; }
    .forex-transition__fallback { display: block; color: #211d29; font: 600 clamp(28px, 6vw, 90px) Georgia, serif; text-align: center; }
    .forex-transition__logo [hidden] { display: none; }
    .forex-transition__shine { position: absolute; inset: -18% -3%; overflow: hidden; opacity: 0; pointer-events: none; }
    .forex-transition__shine::before { content: ''; position: absolute; inset: 0; background: linear-gradient(108deg, transparent 34%, #ffffff18 42%, #ffffffdb 50%, #ffffff20 57%, transparent 65%); }
    .forex-transition__chevron { position: absolute; width: 8%; height: 34%; top: 33%; left: 0; opacity: 0; will-change: transform, opacity; clip-path: polygon(0 0, 43% 0, 100% 50%, 43% 100%, 0 100%, 57% 50%); }
    .forex-transition__chevron--1 { background: #69469d; z-index: 3; }
    .forex-transition__chevron--2 { background: #a393bc; z-index: 2; }
    .forex-transition__chevron--3 { background: #d4d0da; z-index: 1; }
    @media (max-width: 600px) { .forex-transition__logo { width: 63%; } }
  `
  document.head.append(style)

  const overlay = document.createElement('div')
  overlay.className = 'forex-transition'
  overlay.setAttribute('role', 'status')
  const loadingLabels = {"zh-TW":"加载中...","en":"Loading...","fr":"Chargement...","de":"Laden...","ru":"Загрузка...","es":"Cargando...","pt":"Carregando...","it":"Caricamento...","ar":"جار التحميل...","tr":"Yükleniyor...","id":"Memuat...","my":"ဖွင့်နေသည်...","hi":"लोड हो रहा है...","cs":"Načítání...","pl":"Ładowanie...","ja":"読み込み中...","ko":"로드 중...","th":"กำลังโหลด...","vi":"Đang tải..."}
  const updateLoadingLabel = () => {
    let language = 'en'
    try { language = localStorage.getItem('locale') || 'en' } catch { /* Storage can be disabled. */ }
    overlay.setAttribute('aria-label', 'FOREX EXCHANGE ' + (loadingLabels[language] || loadingLabels.en))
  }
  updateLoadingLabel()
  overlay.innerHTML = `
    <div class="forex-transition__composition">
      <div class="forex-transition__logo">
        <img src="/img/logo.svg" alt="FOREX EXCHANGE" decoding="async">
        <span class="forex-transition__fallback" hidden>FOREX EXCHANGE</span>
        <div class="forex-transition__shine" aria-hidden="true"></div>
      </div>
      <div class="forex-transition__chevron forex-transition__chevron--3" aria-hidden="true"></div>
      <div class="forex-transition__chevron forex-transition__chevron--2" aria-hidden="true"></div>
      <div class="forex-transition__chevron forex-transition__chevron--1" aria-hidden="true"></div>
    </div>
  `
  document.body.append(overlay)

  const logo = overlay.querySelector('img')
  const logoWrap = overlay.querySelector('.forex-transition__logo')
  const shine = overlay.querySelector('.forex-transition__shine')
  const chevrons = [1, 2, 3].map(number => overlay.querySelector(`.forex-transition__chevron--${number}`))
  const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)').matches
  let resolveAppReady
  const appReady = holdForRedirect ? Promise.resolve() : new Promise(resolve => { resolveAppReady = resolve })
  const imageReady = Promise.race([
    logo.decode ? logo.decode().catch(() => {}) : Promise.resolve(),
    new Promise(resolve => setTimeout(resolve, 1500)),
  ])
  let generation = 0
  let animations = []

  async function playForexTransition() {
    updateLoadingLabel()
    const token = ++generation
    animations.forEach(animation => animation.cancel())
    animations = []
    overlay.hidden = false
    await imageReady
    if (token !== generation) return false
    if (!logo.naturalWidth) {
      logo.hidden = true
      overlay.querySelector('.forex-transition__fallback').hidden = false
    }

    const width = overlay.clientWidth
    const options = { duration: DURATION, fill: 'forwards', easing: 'linear' }
    const animate = (element, frames) => {
      const animation = element.animate(frames, options)
      animations.push(animation)
      return animation
    }

    if (reducedMotion) {
      animate(logoWrap, [
        { opacity: 0, offset: 0 },
        { opacity: 1, offset: .30 },
        { opacity: 1, offset: 1 },
      ])
    } else {
      animate(logoWrap, [
        { opacity: 0, transform: 'translateX(-20px) scale(.978)', clipPath: 'inset(0 100% 0 0)', offset: 0 },
        { opacity: 0, transform: 'translateX(-20px) scale(.978)', clipPath: 'inset(0 100% 0 0)', offset: .12 },
        { opacity: 1, transform: 'translateX(-9px) scale(.989)', clipPath: 'inset(0 61% 0 0)', offset: .26, easing: 'cubic-bezier(.16,1,.3,1)' },
        { opacity: 1, transform: 'translateX(0) scale(1)', clipPath: 'inset(0 0% 0 0)', offset: .53 },
        { opacity: 1, transform: 'translateX(0) scale(1)', clipPath: 'inset(0 0% 0 0)', offset: 1 },
      ])
      chevrons.forEach((element, index) => {
        const shift = index * .028
        const alpha = [.92, .55, .28][index]
        animate(element, [
          { opacity: 0, transform: `translateX(${-width * .14}px)`, offset: 0 },
          { opacity: 0, transform: `translateX(${-width * .14}px)`, offset: .04 + shift, easing: 'cubic-bezier(.30,.08,.66,1)' },
          { opacity: alpha, transform: `translateX(${width * .35}px)`, offset: .24 + shift },
          { opacity: alpha * .7, transform: `translateX(${width * .70}px)`, offset: .39 + shift },
          { opacity: 0, transform: `translateX(${width * 1.16}px)`, offset: .54 + shift },
          { opacity: 0, transform: `translateX(${width * 1.16}px)`, offset: 1 },
        ])
      })
      animate(shine, [
        { opacity: 0, transform: 'translateX(-115%)', offset: 0 },
        { opacity: 0, transform: 'translateX(-115%)', offset: .44 },
        { opacity: .66, transform: 'translateX(-48%)', offset: .54 },
        { opacity: .66, transform: 'translateX(48%)', offset: .69 },
        { opacity: 0, transform: 'translateX(115%)', offset: .80 },
        { opacity: 0, transform: 'translateX(115%)', offset: 1 },
      ])
    }

    const master = animate(overlay, [{ opacity: 1 }, { opacity: 1 }])
    const start = document.timeline?.currentTime
    if (start != null) animations.forEach(animation => { animation.startTime = start })
    try { await master.finished } catch { return false }
    if (token !== generation) return false
    window.dispatchEvent(new CustomEvent('forex-transition-complete', { detail: { duration: DURATION } }))
    if (!holdForRedirect) {
      // Never leave a broken application permanently covered by the splash.
      let timeout
      await Promise.race([appReady, new Promise(resolve => { timeout = setTimeout(resolve, 8000) })])
      clearTimeout(timeout)
      if (token === generation) overlay.hidden = true
    }
    return true
  }

  const playSafely = () => {
    const task = playForexTransition()
    const token = generation
    return task.catch(() => {
      if (token === generation) {
        animations.forEach(animation => animation.cancel())
        overlay.hidden = true
      }
      return false
    })
  }
  window.playForexTransition = playSafely
  window.addEventListener('forex-app-ready', () => resolveAppReady?.())
  window.addEventListener('forex-route-change', () => { void playSafely() })
  window.forexTransitionFinished = playSafely()
})()
