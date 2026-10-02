<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { isNavigationFailure, NavigationFailureType, onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { useLocaleStore } from '@/store/locale'
import home from '../assets/navigation/home.svg'
import homeActive from '../assets/navigation/home-active.svg'
import orders from '../assets/navigation/orders.svg'
import ordersActive from '../assets/navigation/orders-active.svg'
import surface from '../assets/navigation/trade-a-surface.svg'
import buy from '../assets/navigation/trade-a-buy.svg'
import sell from '../assets/navigation/trade-a-sell.svg'
import explore from '../assets/navigation/explore.svg'
import exploreActive from '../assets/navigation/explore-active.svg'
import profile from '../assets/navigation/profile.svg'
import profileActive from '../assets/navigation/profile-active.svg'

const route = useRoute(), router = useRouter(), locale = useLocaleStore()
const tabs = [
  { path: '/home', zh: '主页', en: 'Home', icon: home, active: homeActive },
  { path: '/orders', zh: '订单', en: 'Orders', icon: orders, active: ordersActive },
  { path: '/trade', zh: '交易', en: 'Trade' },
  { path: '/explore', zh: '探索', en: 'Explore', icon: explore, active: exploreActive },
  { path: '/profile', zh: '我的', en: 'Me', icon: profile, active: profileActive },
]
const phase = ref<'idle' | 'entering' | 'selected' | 'exiting'>('idle')
const pressed = ref('')
const buyArrow = ref<HTMLElement[]>([]), sellArrow = ref<HTMLElement[]>([])
const reduced = window.matchMedia('(prefers-reduced-motion: reduce)')
let finishMotion = () => {}
let motion: Promise<void> | undefined
let pressTimer: ReturnType<typeof setTimeout> | undefined

function play(selected: boolean): Promise<void> {
  const arrows = [buyArrow.value[0], sellArrow.value[0]]
  // Continue from the rendered position if a fast navigation interrupts entry.
  const starts = arrows.map(el => el ? getComputedStyle(el).transform : 'none')
  finishMotion()
  phase.value = selected ? 'selected' : 'idle'
  if (reduced.matches || arrows.some(el => !el?.animate)) return Promise.resolve()
  phase.value = selected ? 'entering' : 'exiting'
  const out = selected ? 180 : 160, duration = selected ? 421 : 381
  return new Promise(resolve => {
    let fallback: ReturnType<typeof setTimeout> | undefined
    const animations: Animation[] = []
    const complete = () => {
      clearTimeout(fallback)
      animations.forEach(animation => animation.cancel())
      phase.value = selected ? 'selected' : 'idle'
      finishMotion = () => {}
      resolve()
    }
    try {
      arrows.forEach((el, i) => {
        const direction = i === 0 ? 1 : -1
        animations.push(el!.animate([
          { transform: starts[i], offset: 0, easing: 'ease-in' },
          { transform: `translateX(${36 * direction}px)`, offset: out / duration, easing: 'steps(1, end)' },
          { transform: `translateX(${-36 * direction}px)`, offset: (out + 1) / duration, easing: 'ease-out' },
          { transform: `translateX(${selected ? direction : 0}px)`, offset: 1 },
        ], { duration, fill: 'both' }))
      })
    } catch {
      complete()
      return
    }
    finishMotion = complete
    // Navigation must not depend on a rendering tick (hidden tabs, interrupted animation).
    fallback = setTimeout(complete, duration + 80)
    void Promise.all(animations.map(animation => animation.finished)).then(() => {
      if (finishMotion === complete) complete()
    }).catch(() => {})
  })
}
function retap(path: string) {
  if (route.path !== path || reduced.matches || phase.value === 'exiting') return
  if (path === '/trade') { motion = play(true); return }
  if (pressed.value) return
  pressed.value = path
  pressTimer = setTimeout(() => { pressed.value = '' }, 370)
}
function settle() {
  finishMotion()
  clearTimeout(pressTimer)
  pressed.value = ''
  phase.value = route.path === '/trade' ? 'selected' : 'idle'
}
onMounted(() => { if (route.path === '/trade') motion = play(true) })
onBeforeRouteLeave(to => {
  if (route.path !== '/trade' || to.path === '/trade' || reduced.matches) return
  if (phase.value !== 'exiting') motion = play(false)
  return motion
})
const removeAfterEach = router.afterEach((_to, _from, failure) => {
  if (failure && !isNavigationFailure(failure, NavigationFailureType.cancelled) && route.path === '/trade' && (phase.value === 'exiting' || phase.value === 'idle')) settle()
})
reduced.addEventListener('change', settle)
onBeforeUnmount(() => { removeAfterEach(); settle(); reduced.removeEventListener('change', settle) })
</script>
<template>
  <nav class="advanced-nav" :aria-label="locale.text('主导航', 'Main navigation')" data-node-id="32:509" data-motion-version="A">
    <router-link v-for="tab in tabs" :key="tab.path" :to="tab.path" class="nav-item" :class="{ selected: route.path === tab.path, central: tab.path === '/trade', pressed: pressed === tab.path }" :aria-current="route.path === tab.path ? 'page' : undefined" @click="retap(tab.path)">
      <span class="icon-box">
        <img v-if="tab.path !== '/trade'" class="nav-art" :src="route.path === tab.path ? tab.active : tab.icon" alt="" />
      </span>
      <span class="label" :title="locale.text(tab.zh, tab.en)">{{ locale.text(tab.zh, tab.en) }}</span>
      <span v-if="tab.path === '/trade'" class="trade-hit" aria-hidden="true">
        <img class="trade-surface" :src="surface" alt="" />
        <span class="trade-clip" :class="phase" :data-phase="phase">
          <span ref="buyArrow" class="trade-arrow buy"><img :src="buy" alt="" /></span>
          <span ref="sellArrow" class="trade-arrow sell"><img :src="sell" alt="" /></span>
        </span>
      </span>
    </router-link>
  </nav>
</template>
<style scoped>
.advanced-nav{--nav-accent:#51495f;position:fixed;inset:auto 0 0;z-index:100;background:#fff;border-top:1px solid #e9edef;display:grid;grid-template-columns:repeat(5,minmax(0,1fr));padding:8px 12px calc(8px + env(safe-area-inset-bottom));height:calc(72px + env(safe-area-inset-bottom));box-sizing:border-box;color:#707780}
.nav-item{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:4px;min-width:0;height:56px;color:#707780;text-decoration:none;position:relative;font-size:12px;line-height:17px}
.icon-box{display:block;width:24px;height:24px;position:relative;flex-shrink:0}
.nav-art{display:block;position:absolute;top:0;left:0}
.selected:not(.central) .nav-art{top:-3px;left:-1px}
.selected{color:var(--nav-accent);font-weight:500}
.label{display:block;max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.central{position:static}
.trade-hit{position:absolute;left:calc(50% - 32px);top:-21px;width:64px;height:76px}
.trade-surface{position:absolute;left:1px;top:1px;pointer-events:none}
.trade-clip{position:absolute;left:8px;top:8px;width:48px;height:48px;overflow:hidden;pointer-events:none}
.trade-arrow{position:absolute;left:14px;width:20px;height:8px}
.trade-arrow img{display:block;position:absolute;left:-.9px;top:-.9px;max-width:none}
.buy{top:13px}.sell{top:27px}
.trade-clip.selected .buy{transform:translateX(1px)}.trade-clip.selected .sell{transform:translateX(-1px)}
.pressed .icon-box{animation:nav-retap .37s ease 1}
.nav-item:focus-visible{outline:2px solid var(--nav-accent);outline-offset:1px;border-radius:8px}
.central:focus-visible .trade-hit{outline:2px solid var(--nav-accent);outline-offset:2px;border-radius:32px}
@keyframes nav-retap{0%,100%{transform:scale(1)}19%,40%{transform:scale(.9)}}
@media(prefers-reduced-motion:reduce){.pressed .icon-box{animation:none}}
</style>
