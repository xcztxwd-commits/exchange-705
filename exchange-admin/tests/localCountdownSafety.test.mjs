import assert from 'node:assert/strict'
import { assertLocalCountdownSafety, observeCountdownRuntime, readCountdownSources } from './localCountdownSafety.mjs'

const replace = (source, old, next) => {
  assert.equal(source.split(old).length, 2, 'negative fixture must change exactly one actual source location')
  return source.replace(old, next)
}
const append = (bundle, code) => ({ ...bundle, app: replace(bundle.app, '</script>', `${code}\n</script>`) })
const timer = (bundle, body) => ({ ...bundle, app: replace(bundle.app, '() => { trialWallet.tick = performance.now() }', `() => { ${body} }`) })
const cases = {
  'interval-fetch': b => timer(b, "trialWallet.tick = performance.now(); void fetch('/auth/activity')"),
  'interval-axios': b => timer(b, "trialWallet.tick = performance.now(); void axios.post('/auth/activity', {})"),
  'interval-api': b => timer(b, 'trialWallet.tick = performance.now(); void trialWallet.refresh()'),
  'interval-helper': b => append(timer(b, 'pollClock()'), "const pollClock = () => { trialWallet.tick = performance.now(); void request.get('/user/assets') }"),
  'tick-watch': b => append(b, "watch(() => trialWallet.tick, () => { void request.post('/auth/activity', {}) })"),
  'indirect-watch': b => append(b, "const clockSource = () => trialWallet.remaining; const pollClock = () => request.get('/user/assets'); watch(clockSource, pollClock)"),
  'store-watch': b => ({ ...b, wallet: replace(b.wallet, "import { computed, ref } from 'vue'", "import { computed, ref, watch } from 'vue'").replace('const clock = createServerClock()', "const clock = createServerClock(); watch(tick, () => { void request.get('/user/assets') })") }),
  'computed-helper': b => ({ ...b, wallet: replace(b.wallet, 'const serverNow = computed(() => clock.now(tick.value))', "const pollClock = () => { void request.get('/user/assets'); return clock.now(tick.value) }; const serverNow = computed(() => pollClock())") }),
  'clock-helper-network': b => ({ ...b, lifecycle: replace(b.lifecycle, 'now(monotonic: number) { return', "now(monotonic: number) { void fetch('/auth/activity'); return") }),
  'mode-helper-network': b => ({ ...b, accountMode: replace(b.accountMode, 'export function accountMode(): AccountMode {', "export function accountMode(): AccountMode { void fetch('/auth/activity');") }),
  'extra-interval': b => append(b, "setInterval(() => { void request.post('/auth/activity', {}) }, 1000)"),
  'recursive-timeout': b => append(b, "function pollClock() { void request.get('/user/assets'); setTimeout(pollClock, 1000) }; pollClock()"),
  'scheduler-alias': b => append(b, "const schedule = setInterval; schedule(() => { void request.get('/user/assets') }, 1000)"),
  'heartbeat': b => append(b, "function heartbeat() { void request.post('/auth/activity', {}) }"),
  'cleanup-missing': b => ({ ...b, app: replace(b.app, 'clearInterval(trialTimer)', '') }),
  'cleanup-wrong-handle': b => ({ ...b, app: replace(b.app, 'clearInterval(trialTimer)', 'clearInterval(undefined)') }),
  'store-timer': b => ({ ...b, wallet: replace(b.wallet, 'const clock = createServerClock()', "const clock = createServerClock(); setInterval(() => request.get('/user/assets'), 1000)") }),
  'clock-method-replaced': b => ({ ...b, wallet: replace(b.wallet, 'const clock = createServerClock()', "const clock = createServerClock(); clock.now = value => { void request.get('/user/assets'); return value }") }),
}

const routeSource = '() => [route.path, route.matched.length] as const'
const routeSourceCase = (bundle, source) => ({ ...bundle, app: replace(bundle.app, routeSource, source) })
const routeCases = {
  'route-clock-read': b => routeSourceCase(b, '() => [route.path, trialWallet.tick] as const'),
  'route-helper-read': b => append(routeSourceCase(b, '() => [route.path, routeDepth()] as const'), 'const routeDepth = () => route.matched.length'),
  'route-extra-read': b => routeSourceCase(b, '() => [route.path, route.matched.length, auth.token] as const'),
  'route-depth-expression': b => routeSourceCase(b, '() => [route.path, route.matched.length || 1] as const'),
  'route-nonconst-assertion': b => routeSourceCase(b, '() => [route.path, route.matched.length] as any'),
  'route-async-source': b => routeSourceCase(b, 'async () => [route.path, route.matched.length] as const'),
  'route-parameter-source': b => routeSourceCase(b, '(event: Event) => [route.path, route.matched.length] as const'),
  'route-getter-array': b => routeSourceCase(b, '[() => route.path, () => route.matched.length]'),
  'premature-route-report': b => ({ ...b, app: replace(b.app, ' && route.matched.length > 0', '') }),
  'wrong-default-contract': b => ({ ...b, app: replace(b.app, 'void report(pageCodeForPath(path))', "void report('contract')") }),
  'wrong-default-home': b => ({ ...b, app: replace(b.app, 'void report(pageCodeForPath(path))', "void report('home')") }),
  'missing-readiness-dependency': b => routeSourceCase(b, '() => route.path'),
  'readiness-never-reports': b => ({ ...b, app: replace(b.app, 'route.matched.length > 0', 'route.matched.length > 1') }),
}
const routeStatic = Object.keys(routeCases).slice(0, 8), routeRuntime = Object.keys(routeCases).slice(8)

const args = process.argv.slice(2), option = name => args.includes(name) ? args[args.indexOf(name) + 1] : undefined
if (args.includes('--list')) console.log(JSON.stringify(Object.keys(cases)))
else if (args.includes('--list-route')) console.log(JSON.stringify({ static: routeStatic, runtime: routeRuntime }))
else {
  const app = option('--app'), apps = app ? [app] : ['exchange-frontend', 'exchange-pc']
  const routeProbe = option('--route-policy-probe'), routeRuntimeProbe = option('--route-runtime-probe')
  const probe = option('--probe') || routeProbe, runtimeProbe = option('--runtime-probe') || routeRuntimeProbe, name = probe || runtimeProbe
  const mutations = routeProbe || routeRuntimeProbe ? routeCases : cases
  if (name) assert(Object.hasOwn(mutations, name), 'unknown negative case')
  for (const application of apps) {
    let sources = readCountdownSources(application)
    if (name) sources = mutations[name](sources)
    if (args.includes('--route-bare-positive')) sources = routeSourceCase(sources, '() => [route.path, route.matched.length]')
    if (!runtimeProbe) assertLocalCountdownSafety(sources)
    if (!probe) console.log(JSON.stringify(await observeCountdownRuntime(sources)))
    if (name) assert.fail(`negative ${name} unexpectedly accepted`)
    const whitespace = timer(sources, '/* local display only */ trialWallet.tick = performance.now()')
    assertLocalCountdownSafety(whitespace)
    console.log(`PASS ${application}: reviewed local clock, actual reactive source execution, hidden suppression, mount/unmount, event-driven reports and matched readiness/support/query/auth gates; whitespace accepted`)
  }
}
