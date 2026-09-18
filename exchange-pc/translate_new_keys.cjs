const fs = require('fs');
const path = require('path');
const http = require('http');
const https = require('https');

function httpsGetViaProxy(urlStr, proxyHost, proxyPort) {
  return new Promise((resolve, reject) => {
    const url = new URL(urlStr);
    const options = { host: proxyHost, port: proxyPort, method: 'CONNECT', path: url.host + ':443' };
    const req = http.request(options);
    req.on('connect', (res, socket, head) => {
      if (res.statusCode === 200) {
        https.get({ host: url.hostname, path: url.pathname + url.search, socket: socket, agent: false }, (res2) => {
          let data = ''; res2.on('data', chunk => data += chunk); res2.on('end', () => resolve(data));
        }).on('error', reject);
      } else { reject(new Error(`Proxy connection failed: ${res.statusCode}`)); }
    });
    req.on('error', reject); req.end();
  });
}

function translate(text, targetLang) {
  return new Promise((resolve, reject) => {
    if (targetLang === 'zh-CN') return resolve(text);
    if (targetLang === 'zh-TW') {
       if (text === '闪电选取') return resolve('閃電選取');
       if (text === '视讯简介') return resolve('視訊簡介');
    }
    
    let tl = targetLang;
    const urlStr = `https://translate.googleapis.com/translate_a/single?client=gtx&sl=zh-CN&tl=${tl}&dt=t&q=${encodeURIComponent(text)}`;
    httpsGetViaProxy(urlStr, '127.0.0.1', 7890).then(data => {
      try {
        const parsed = JSON.parse(data);
        resolve(parsed[0].map(item => item[0] || '').join(''));
      } catch(e) { resolve(text); }
    }).catch(reject);
  });
}

const newTerms = {
  lightningWithdraw: '闪电选取',
  videoIntro: '视讯简介'
};

const localeFile = path.join(__dirname, 'src/store/locale.ts');
let content = fs.readFileSync(localeFile, 'utf8');

const langRegex = /('([^']+)':\s*\{)([\s\S]*?)(\}(?=,\n\s*'|\n?\s*\}))/g;
let langs = [];
let match;
while ((match = langRegex.exec(content)) !== null) {
  langs.push(match[2]);
}

async function main() {
  const textsToTranslate = Object.values(newTerms);
  const keysToTranslate = Object.keys(newTerms);
  
  const translations = {};
  
  for (const lang of langs) {
    translations[lang] = {};
    if (lang === 'zh-CN') {
      for (const k in newTerms) translations[lang][k] = newTerms[k];
      continue;
    }
    if (lang === 'zh-TW') {
      translations[lang]['lightningWithdraw'] = '閃電選取';
      translations[lang]['videoIntro'] = '視訊簡介';
      continue;
    }
    
    console.log(`Translating to ${lang}...`);
    const query = textsToTranslate.join('\\n');
    const result = await translate(query, lang);
    const parts = result.split('\\n').map(s => s.trim());
    
    for (let i = 0; i < keysToTranslate.length; i++) {
      let tText = parts[i] || textsToTranslate[i];
      tText = tText.replace(/'/g, "\\\\'");
      translations[lang][keysToTranslate[i]] = tText;
    }
    await new Promise(r => setTimeout(r, 800));
  }
  
  let newContent = content.replace(langRegex, (match, prefix, lang, body, suffix) => {
    let lines = body.split('\\n');
    for (const key in translations[lang]) {
      const regex = new RegExp(`^\\\\s*${key}:\\\\s*'[^']*',?\\\\s*$`);
      const existingIdx = lines.findIndex(l => regex.test(l));
      const line = `    ${key}: '${translations[lang][key]}',`;
      if (existingIdx !== -1) {
        lines[existingIdx] = line;
      } else {
        lines.push(line);
      }
    }
    return prefix + lines.join('\\n') + '\\n' + suffix;
  });
  
  // add types
  const keysMatch = newContent.match(/export type MessageKeys =([\\s\\S]*?)(?=\\n\\s*\\n)/);
  if (keysMatch) {
    let existingTypes = keysMatch[1];
    for (const key of keysToTranslate) {
      if (!existingTypes.includes(`| '${key}'`)) {
        existingTypes += `\\n  | '${key}'`;
      }
    }
    newContent = newContent.replace(keysMatch[1], existingTypes);
  }
  
  fs.writeFileSync(localeFile, newContent, 'utf8');
  console.log('Done injecting new translations');
}

main().catch(console.error);