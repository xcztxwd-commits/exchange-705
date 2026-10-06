const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '../exchange-pc/public/entry.html'), 'utf8');
assert.match(html, /<script defer src="\/forex-transition\.js" data-hold><\/script>/);
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
assert.doesNotMatch(script, /forexTransitionFinished/, 'auth must not wait for animation');
const index = fs.readFileSync(path.join(__dirname, '../exchange-pc/index.html'), 'utf8');
assert.match(index, /<script src="\/forex-transition\.js"><\/script>/, 'destination keeps loading overlay');

async function check(token, response, expected) {
  let destination;
  let request;
  const context = {
    window: { forexTransitionFinished: new Promise(() => {}) },
    localStorage: { getItem: () => token },
    location: { replace: url => { destination = url; } },
    fetch: async (url, options) => {
      request = { url, options };
      if (response instanceof Error) throw response;
      return { ok: response };
    },
    AbortController,
    setTimeout,
    clearTimeout,
  };
  vm.runInNewContext(script, context);
  await new Promise(setImmediate);
  assert.equal(destination, expected, 'redirect starts as soon as destination is known');
  if (token) {
    assert.equal(request.url, '/api/auth/heartbeat');
    assert.equal(request.options.method, 'POST');
    assert.equal(request.options.headers.Authorization, `Bearer ${token}`);
  } else {
    assert.equal(request, undefined);
  }
}

const aliasHtml = fs.readFileSync(path.join(__dirname, '../exchange-pc/public/domain-transition.html'), 'utf8');
assert.doesNotMatch(aliasHtml, /forex-transition\.js/, 'legacy alias must not block on animation asset');
const aliasScript = aliasHtml.match(/<script>([\s\S]*?)<\/script>/)[1];
assert.doesNotMatch(aliasScript, /forexTransitionFinished/);
const caddy = fs.readFileSync(path.join(__dirname, 'Caddyfile'), 'utf8');
assert.equal((caddy.match(/domain-transition\.html\?next=\{uri\}/g) || []).length, 0);
assert.equal((caddy.match(/@document header Accept \*text\/html\*/g) || []).length, 0);
assert.doesNotMatch(caddy, /redir https:\/\/trade\.forex-exchange\.cc/, 'entry targets must come from tenant DB, not global suffix or hardcoded alias');
assert.match(caddy, /http:\/\/\*\.forex-exchange\.net/);
assert.match(caddy, /reverse_proxy pc:80/);

async function checkAlias(search, expected) {
  let destination;
  const location = {
    origin: 'https://trade.forex-exchange.cc', search,
    replace: url => { destination = url; },
  };
  vm.runInNewContext(aliasScript, { location, URL });
  assert.equal(destination, expected, 'legacy alias redirects immediately');
}

(async () => {
  const japan = 'https://www.forex-exchange.co.jp/';
  await check(null, false, japan);
  await check('valid-token', true, '/');
  await check('expired-token', false, japan);
  await check('unreachable', new Error('offline'), japan);
  await checkAlias('?next=/mobile/home?tab=1&x=2', 'https://trade.forex-exchange.cc/mobile/home?tab=1&x=2');
  await checkAlias('?next=https://evil.example/', 'https://trade.forex-exchange.cc/');
  await checkAlias('?next=/domain-transition.html', 'https://trade.forex-exchange.cc/');
  console.log('PASS: auth routing, immediate alias redirect, path preservation, external target rejection');
})().catch(error => { console.error(error); process.exitCode = 1; });
