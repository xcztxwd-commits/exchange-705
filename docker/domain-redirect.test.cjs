const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '../exchange-pc/public/entry.html'), 'utf8');
assert.match(html, /<script src="\/forex-transition\.js" data-hold><\/script>/);
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

async function check(token, response, expected) {
  let destination;
  let request;
  let finishAnimation;
  const animation = new Promise(resolve => { finishAnimation = resolve; });
  const context = {
    window: { forexTransitionFinished: animation },
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
  assert.equal(destination, undefined, 'redirect must wait for animation');
  finishAnimation();
  await new Promise(setImmediate);
  assert.equal(destination, expected);
  if (token) {
    assert.equal(request.url, '/api/auth/heartbeat');
    assert.equal(request.options.method, 'POST');
    assert.equal(request.options.headers.Authorization, `Bearer ${token}`);
  } else {
    assert.equal(request, undefined);
  }
}

const aliasHtml = fs.readFileSync(path.join(__dirname, '../exchange-pc/public/domain-transition.html'), 'utf8');
assert.match(aliasHtml, /<script src="\/forex-transition\.js" data-hold><\/script>/);
const aliasScript = aliasHtml.match(/<script>([\s\S]*?)<\/script>/)[1];
const caddy = fs.readFileSync(path.join(__dirname, 'Caddyfile'), 'utf8');
assert.equal((caddy.match(/domain-transition\.html\?next=\{uri\}/g) || []).length, 2);
assert.equal((caddy.match(/@document header Accept \*text\/html\*/g) || []).length, 2);
assert.equal((caddy.match(/redir https:\/\/trade\.forex-exchange\.cc\{uri\} 302/g) || []).length, 2);

async function checkAlias(search, expected) {
  let destination;
  let finishAnimation;
  const animation = new Promise(resolve => { finishAnimation = resolve; });
  const location = {
    origin: 'https://trade.forex-exchange.cc', search,
    replace: url => { destination = url; },
  };
  vm.runInNewContext(aliasScript, { window: { forexTransitionFinished: animation }, location, URL });
  await new Promise(setImmediate);
  assert.equal(destination, undefined, 'alias redirect must wait for animation');
  finishAnimation();
  await new Promise(setImmediate);
  assert.equal(destination, expected);
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
  console.log('PASS: animation gate, auth redirect, alias path preservation, external target rejection');
})().catch(error => { console.error(error); process.exitCode = 1; });
