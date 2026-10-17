import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import { supportMessages, supportCopy, supportReplyCopy, supportSystemCopy, isSupportSystem, supportErrorCopy, supportErrorKey } from '../src/utils/supportLocalization.ts'

const locales = [...readFileSync(new URL('../src/utils/languages.ts', import.meta.url), 'utf8').matchAll(/locale: '([^']+)'/g)].map(match => match[1])
const welcome = '您好，欢迎联系在线客服。请描述您需要帮助的问题。'
const offline = '客服暂未在线，您可以留言；上线后将按排队顺序接待。'
const defaultWelcome = 'Welcome to customer support. Please describe how we can help.'
const defaultOffline = 'Agents are offline. Leave a message and we will respond in queue order when available.'

test('all 19 supported languages cover every support key and preserve parameters', () => {
  assert.equal(locales.length, 19)
  assert.deepEqual(Object.keys(supportMessages).sort(), [...locales].sort())
  const keys = Object.keys(supportMessages.en).sort()
  assert.equal(keys.length, 75)
  for (const locale of locales) {
    assert.deepEqual(Object.keys(supportMessages[locale]).sort(), keys, locale)
    for (const key of keys) {
      const value = supportMessages[locale][key]
      assert.ok(value.trim(), `${locale}: ${key}`)
      assert.deepEqual([...value.matchAll(/\{\w+\}/g)].map(match => match[0]).sort(), [...key.matchAll(/\{\w+\}/g)].map(match => match[0]).sort(), `${locale}: ${key}`)
      if (!['zh-TW', 'ja'].includes(locale)) assert.doesNotMatch(value, /\p{Script=Han}/u, `${locale}: ${key}`)
    }
    assert.match(supportMessages[locale]['Choose a JPG, PNG or GIF under 5MB'], /5\s?MB/)
  }
  assert.equal(supportCopy('unsupported', 'Connected to an agent'), supportMessages.en['Connected to an agent'])
})

test('every fixed string used by support components has translations for every language', () => {
  for (const path of ['../src/views/SupportPage.vue', '../src/advanced/views/SupportPage.vue', '../src/components/SupportThread.vue', '../src/advanced/components/business/BusinessSupportThread.vue', '../src/components/SupportNotifications.vue', '../src/components/SupportImage.vue']) {
    const source = readFileSync(new URL(path, import.meta.url), 'utf8')
    for (const match of source.matchAll(/\b(?:t|supportText)\(\s*'([^']*)'\s*,\s*'([^']*)'/g)) {
      for (const locale of locales) assert.ok(supportMessages[locale][match[2]], `${locale}: ${path}: ${match[2]}`)
    }
  }
})

test('system events, default replies and legacy transfer records follow the selected language', () => {
  for (const locale of locales) {
    assert.equal(supportReplyCopy(locale, offline), supportMessages[locale][defaultOffline])
    assert.equal(supportSystemCopy(locale, { sender: 'SYSTEM', text: welcome }), supportMessages[locale][defaultWelcome])
    for (const [text, key] of [['客服已接入', 'An agent has joined the conversation'], ['总控管理已接入', 'A support supervisor has joined the conversation'], ['客服结束会话', 'The agent ended the conversation'], ['用户结束会话', 'You ended the conversation']]) {
      assert.equal(supportSystemCopy(locale, { sender: 'SYSTEM', text }), supportMessages[locale][key])
    }
    assert.equal(supportSystemCopy(locale, { sender: 'SYSTEM', text: '会话转接：-7 -> 13' }), supportCopy(locale, 'Conversation transferred: {from} → {to}', { from: '-7', to: '13' }))
  }
})

test('human messages, custom configured replies and persisted records remain intact', () => {
  const records = ['USER', 'ADMIN', 'CONTROL', 'SYSTEM'].map(sender => ({ sender, text: '自定义客服回复，保留原文', id: 1 }))
  const before = structuredClone(records)
  for (const locale of locales) {
    for (const record of records) assert.equal(supportSystemCopy(locale, record), record.text)
    for (const sender of ['USER', 'ADMIN', 'CONTROL']) assert.equal(supportSystemCopy(locale, { sender, text: '客服已接入' }), '客服已接入')
    assert.equal(supportReplyCopy(locale, '我们将在明早九点回复您的工单。'), '我们将在明早九点回复您的工单。')
  }
  assert.deepEqual(records, before)
})

test('server-marked control events are localized while identically worded human replies remain original', () => {
  const automatic = { sender: 'CONTROL', text: '总控管理已接入', requestId: 'system.12345678-1234-1234-1234-123456789012' }
  const human = { ...automatic, requestId: '12345678-1234-1234-1234-123456789012' }
  assert.equal(isSupportSystem(automatic), true)
  assert.equal(isSupportSystem(human), false)
  for (const locale of locales) {
    assert.equal(supportSystemCopy(locale, automatic), supportMessages[locale]['A support supervisor has joined the conversation'])
    assert.equal(supportSystemCopy(locale, human), human.text)
    assert.equal(supportSystemCopy(locale, { ...automatic, text: '会话转接：null -> 13' }), supportCopy(locale, 'Conversation transferred: {from} → {to}', { from: supportMessages[locale]['Support supervisor'], to: '13' }))
  }
})

test('support error messages retain their specific meaning in every language', () => {
  assert.equal(supportErrorKey('会话已结束'), 'This conversation has ended')
  assert.equal(supportErrorKey('余额不足'), undefined)
  for (const locale of locales) {
    assert.equal(supportErrorCopy(locale, '会话已结束'), supportMessages[locale]['This conversation has ended'])
    assert.equal(supportErrorCopy(locale, 'The image is empty or exceeds 5MB'), supportMessages[locale]['The image is empty or exceeds 5MB'])
    assert.equal(supportErrorCopy(locale, 'Custom diagnostic'), 'Custom diagnostic')
  }
})

test('actual mobile and PC support adapters react to locale changes and preserve the admin display', () => {
  for (const path of ['../src/utils/support.ts', '../../exchange-pc/src/utils/support.ts']) {
    let locale = 'es'
    const source = readFileSync(new URL(path, import.meta.url), 'utf8').replace(/^(?:import |export \{).*$/gm, '').replace(/\bexport /g, '')
    const bindings = { useLocaleStore: () => ({ locale, text: (zh, en) => locale === 'zh-TW' ? zh : en }), getAccountApiBase: () => '/api', supportCopy, supportReplyCopy, supportSystemCopy, supportErrorCopy }
    const api = new Function(...Object.keys(bindings), stripTypeScriptTypes(source) + ';return { supportText, supportSystemText, supportReplyText, supportErrorText, supportStatus, supportDate };')(...Object.values(bindings))
    const event = { sender: 'SYSTEM', text: '客服已接入' }
    for (locale of locales) {
      assert.equal(api.supportText('客服已接入', 'Connected to an agent'), supportMessages[locale]['Connected to an agent'])
      assert.equal(api.supportSystemText(event), supportMessages[locale]['An agent has joined the conversation'])
      assert.equal(api.supportReplyText(offline), supportMessages[locale][defaultOffline])
      assert.equal(api.supportErrorText('会话已结束'), supportMessages[locale]['This conversation has ended'])
      assert.equal(api.supportStatus('CLOSED'), supportMessages[locale].Closed)
      assert.equal(api.supportSystemText(event, true), event.text)
      assert.equal(api.supportText('客服已接入', 'Connected to an agent', true), '客服已接入')
      assert.equal(api.supportDate('2026-10-17T10:00:00Z'), new Date('2026-10-17T10:00:00Z').toLocaleString(locale, { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }))
    }
  }
})

test('actual image validation and audio error state can be translated again after a language switch', async () => {
  let locale = 'fr'
  const t = (_zh, en) => supportCopy(locale, en) ?? en
  for (const path of ['../src/components/SupportThread.vue', '../src/advanced/components/business/BusinessSupportThread.vue']) {
    const vue = readFileSync(new URL(path, import.meta.url), 'utf8')
    const chooseSource = vue.slice(vue.indexOf('function choose('), vue.indexOf('\nfunction visible('))
    const error = { value: '' }, file = { value: undefined }
    const choose = new Function('props', 'error', 'file', 't', stripTypeScriptTypes(chooseSource) + ';return choose;')({ admin: false }, error, file, t)
    choose({ target: { files: [{ type: 'text/plain', size: 1 }] } })
    assert.equal(error.value, 'Choose a JPG, PNG or GIF under 5MB')
    for (locale of ['fr', 'de']) assert.equal(supportErrorCopy(locale, error.value), supportMessages[locale]['Choose a JPG, PNG or GIF under 5MB'])
    assert.equal(file.value, undefined)
  }
  const notifications = readFileSync(new URL('../src/components/SupportNotifications.vue', import.meta.url), 'utf8')
  const soundSource = notifications.slice(notifications.indexOf('async function sound('), notifications.indexOf('\nasync function enableAudio('))
  const audioError = { value: '' }, audioEnabled = { value: true }
  const sound = new Function('state', 'audio', 'audioEnabled', 'audioError', 'supportUrl', 't', stripTypeScriptTypes(soundSource) + ';return sound;')({ value: { sound: '/tone.wav' } }, { play: async () => { throw new Error('Permission denied') } }, audioEnabled, audioError, value => value, t)
  await sound()
  assert.equal(audioError.value, 'Sound was blocked. Tap to enable again.')
  const computedSource = notifications.match(/^const localizedAudioError = .+$/m)[0]
  const display = new Function('computed', 'audioError', 't', computedSource + ';return localizedAudioError;')(get => ({ get value() { return get() } }), audioError, t)
  for (locale of ['fr', 'de']) assert.equal(display.value, supportMessages[locale]['Sound was blocked. Tap to enable again.'])
})
