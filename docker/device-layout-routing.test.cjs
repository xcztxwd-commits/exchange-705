const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { runInNewContext } = require('node:vm');
const source = readFileSync(require('node:path').join(__dirname, 'device-layout.js'), 'utf8');

function routing({ layout = 'mobile', path = '/mobile/home', width = 390, coarse = true, screenWidth = 390, screenHeight = 844 } = {}) {
  const redirects = [];
  let change;
  const narrow = { matches: width <= 768, addEventListener: (_, listener) => { change = listener; } };
  runInNewContext(source, {
    document: { currentScript: { dataset: { layout } } },
    screen: { width: screenWidth, height: screenHeight },
    matchMedia: query => query === '(pointer: coarse)' ? { matches: coarse } : narrow,
    location: { href: `https://example.test${path}`, pathname: path, replace: target => redirects.push(new URL(target)) },
    URL,
  });
  return { redirects, resize: width => { narrow.matches = width <= 768; change?.(); } };
}

test('phone fullscreen and orientation changes keep the current mobile page', () => {
  const page = routing({ path: '/mobile/home?edition=advanced' });
  for (const width of [844, 390, 844, 390]) page.resize(width);
  assert.equal(page.redirects.length, 0);
});

test('a phone opened in landscape still selects the mobile entry', () => {
  for (const [screenWidth, screenHeight] of [[390, 844], [844, 390]]) {
    const mobile = routing({ width: 844, screenWidth, screenHeight });
    assert.equal(mobile.redirects.length, 0);
    const desktop = routing({ layout: 'pc', path: '/', width: 844, screenWidth, screenHeight });
    assert.equal(desktop.redirects[0]?.pathname, '/mobile/home');
  }
});

test('desktop resizing still switches at the existing 768px boundary', () => {
  const desktop = routing({ layout: 'pc', path: '/', width: 1280, coarse: false, screenWidth: 1920, screenHeight: 1080 });
  desktop.resize(769);
  assert.equal(desktop.redirects.length, 0);
  desktop.resize(768);
  assert.equal(desktop.redirects[0].pathname, '/mobile/home');
  const mobile = routing({ coarse: false, screenWidth: 1920, screenHeight: 1080 });
  mobile.resize(769);
  assert.equal(mobile.redirects[0].pathname, '/');
});

test('a large touch screen keeps desktop layout and auth route mapping', () => {
  const desktop = routing({ layout: 'pc', path: '/?register=1', width: 1280, screenWidth: 1920, screenHeight: 1080 });
  assert.equal(desktop.redirects.length, 0);
  desktop.resize(390);
  assert.equal(desktop.redirects[0].pathname, '/mobile/register');
  assert.equal(desktop.redirects[0].search, '');
});

test('the dedicated mobile port stays mobile at any size', () => {
  const mobile = routing({ path: '/home', width: 1280, coarse: false });
  mobile.resize(1920);
  assert.equal(mobile.redirects.length, 0);
});
