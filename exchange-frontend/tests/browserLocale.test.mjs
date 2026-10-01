import assert from 'node:assert/strict'
import { browserLocale } from '../src/utils/browserLocale.ts'

const supported = { en: {}, ja: {}, fr: {}, 'zh-TW': {} }
assert.equal(browserLocale(['ja-JP', 'en-US'], supported), 'ja')
assert.equal(browserLocale(['xx-XX', 'fr-FR'], supported), 'fr')
assert.equal(browserLocale(['zh-CN', 'en-US'], supported), 'zh-TW')
assert.equal(browserLocale(['xx-XX'], supported), 'en')
assert.equal(browserLocale([], supported), 'en')
console.log('PASS: browser language priority and fallback')
