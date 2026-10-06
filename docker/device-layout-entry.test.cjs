const fs = require('node:fs');
const assert = require('node:assert/strict');
const path = require('node:path');
const root = path.join(__dirname, '..');
const shared = fs.readFileSync(path.join(__dirname, 'device-layout.js'), 'utf8');
for (const [project, layout] of [['exchange-pc', 'pc'], ['exchange-frontend', 'mobile']]) {
  const folder = path.join(root, project);
  assert.equal(fs.readFileSync(path.join(folder, 'public/device-layout.js'), 'utf8'), shared);
  const html = fs.readFileSync(path.join(folder, 'index.html'), 'utf8');
  assert.equal((html.match(/data-layout=/g) || []).length, 1);
  assert.ok(html.includes(`src="/device-layout.js" data-layout="${layout}"`));
  assert.ok(html.indexOf('device-layout.js') < html.indexOf('</head>'));
}
assert.ok(!fs.readFileSync(path.join(__dirname, 'frontend.Dockerfile'), 'utf8').includes('sed -i "s|</head>'));
console.log('PASS: device routing is included in both standard builds, without Docker-only injection');
