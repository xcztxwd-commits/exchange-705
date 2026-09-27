const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const pc = fs.readFileSync(path.join(__dirname, '../exchange-pc/public/forex-transition.js'), 'utf8');
const mobile = fs.readFileSync(path.join(__dirname, '../exchange-frontend/public/forex-transition.js'), 'utf8');
assert.equal(mobile, pc, 'both apps must ship the same animation');

function load(hold = false, reducedMotion = false, brokenAnimation = false) {
  const listeners = new Map();
  const animations = [];
  const element = name => ({
    name, hidden: false, clientWidth: 1280, setAttribute() {},
    animate(frames, options) {
      if (brokenAnimation) throw new Error('animation unavailable');
      animations.push({ name, frames, options });
      return { finished: Promise.resolve(), cancel() {}, startTime: null };
    },
  });
  const parts = Object.fromEntries([
    'img', '.forex-transition__logo', '.forex-transition__fallback',
    '.forex-transition__shine', '.forex-transition__chevron--1',
    '.forex-transition__chevron--2', '.forex-transition__chevron--3',
  ].map(name => [name, element(name)]));
  parts.img.decode = () => Promise.resolve();
  parts.img.naturalWidth = 515;
  const overlay = element('overlay');
  overlay.querySelector = selector => parts[selector];
  const document = {
    currentScript: { hasAttribute: name => name === 'data-hold' && hold },
    head: { append() {} }, body: { append() {} }, timeline: { currentTime: 0 },
    createElement: name => name === 'style' ? {} : overlay,
  };
  const window = {
    addEventListener(name, callback) {
      if (!listeners.has(name)) listeners.set(name, []);
      listeners.get(name).push(callback);
    },
    dispatchEvent(event) {
      for (const callback of listeners.get(event.type) || []) callback(event);
    },
  };
  vm.runInNewContext(pc, {
    document, window, matchMedia: () => ({ matches: reducedMotion }),
    CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options.detail; } },
    setTimeout: () => 1, clearTimeout: () => {},
  });
  return { overlay, window, animations };
}

(async () => {
  const app = load();
  await Promise.resolve();
  assert.equal(app.overlay.hidden, false, 'initial splash stays until app is ready');
  app.window.dispatchEvent({ type: 'forex-app-ready' });
  assert.equal(await app.window.forexTransitionFinished, true);
  assert.equal(app.overlay.hidden, true);
  assert.equal(app.animations.length, 6, 'logo, three chevrons, shine, master');
  app.window.dispatchEvent({ type: 'forex-route-change' });
  assert.equal(app.overlay.hidden, false, 'route change replays transition');
  await new Promise(setImmediate);
  assert.equal(app.overlay.hidden, true);
  assert.equal(app.animations.length, 12);

  const redirect = load(true, true);
  assert.equal(await redirect.window.forexTransitionFinished, true);
  assert.equal(redirect.overlay.hidden, false, 'redirect keeps final frame until navigation');
  assert.equal(redirect.animations.length, 2, 'reduced motion skips chevrons and shine');
  const broken = load(false, false, true);
  assert.equal(await broken.window.forexTransitionFinished, false);
  assert.equal(broken.overlay.hidden, true, 'animation failure must not block the app');
  console.log('PASS: initial load, route replay, redirect hold, reduced motion, failure fallback, PC/mobile sync');
})().catch(error => { console.error(error); process.exitCode = 1; });
