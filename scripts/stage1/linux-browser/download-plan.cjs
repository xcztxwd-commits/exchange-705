// Ask the installed exact Playwright and normally signed APT indexes for an offline download plan.
const cp=require('node:child_process'),fs=require('node:fs'),assert=require('node:assert/strict');
const cli='/opt/browser/node_modules/playwright/cli.js';
function command(file,args){const r=cp.spawnSync(file,args,{encoding:'utf8'});assert.equal(r.status,0,r.stderr||r.stdout);return r.stdout;}
const dependency=cp.spawnSync('node',[cli,'install-deps','--dry-run','chromium'],{encoding:'utf8'});
const dry=dependency.stdout+dependency.stderr;assert.ok(dependency.status===0||dependency.status===1&&/Missing system dependencies \(\d+\):/.test(dry),dry);
const install=dry.match(/apt-get install[^\n]*--no-install-recommends ([^\n]+)/);
const packages=install?install[1].trim().split(/\s+/):dry.match(/Missing system dependencies \(\d+\):\s*([\s\S]+)/)[1].trim().split(/\s+/);
assert.ok(packages.every(p=>/^[a-z0-9.+:-]+$/.test(p)));
const apt=command('apt-get',['--print-uris','-o','Acquire::ForceHash=sha256','--yes','--no-install-recommends','install',...packages]);
const debs=[...apt.matchAll(/'([^']+)' (\S+) (\d+) SHA256:([a-f0-9]{64})/g)].map(m=>({url:m[1],file:m[2],bytes:Number(m[3]),sha256:m[4]}));
assert.ok(debs.length>0);assert.ok(debs.every(p=>new URL(p.url).hostname==='mirrors.tuna.tsinghua.edu.cn'&&new URL(p.url).protocol==='https:'&&/^[^/\\]+\.deb$/.test(p.file)));
const browser=command('node',[cli,'install','--dry-run','--only-shell','chromium']);
const browsers=[...browser.matchAll(/Install location:\s+([^\n]+)\n\s*Download url:\s+([^\n]+)/g)].map(m=>({directory:m[1].trim().split('/').at(-1),url:m[2].trim()}));
assert.ok(browsers.some(b=>/^chromium_headless_shell-\d+$/.test(b.directory)));
assert.ok(browsers.every(b=>/^(chromium_headless_shell|ffmpeg)-\d+$/.test(b.directory)&&['cdn.playwright.dev','playwright.download.prss.microsoft.com'].includes(new URL(b.url).hostname)&&new URL(b.url).protocol==='https:'));
fs.writeFileSync('/assets/plan.json',JSON.stringify({playwrightVersion:require('/opt/browser/node_modules/playwright/package.json').version,packages,debs,browsers,aptIntegrity:'Normal APT Signed-By indexes; each SHA256 checked again by offline apt install'},null,2));
console.log('APT-signed package plan '+debs.length+' packages; official Playwright artifacts '+browsers.length);
