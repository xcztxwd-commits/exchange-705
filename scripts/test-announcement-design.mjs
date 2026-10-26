import assert from 'node:assert/strict'
import { announcementTextFields, createCelebrationAnnouncementDesign, createCelebrationAnnouncementTemplate, isAnnouncementDocument, resolveAnnouncementDocument } from '../exchange-frontend/src/utils/announcementDesign.ts'
import { parseDesign } from '../exchange-frontend/src/utils/activityDesign.ts'

const design = createCelebrationAnnouncementDesign()
assert.equal(design.version, 1)
assert.equal(design.presentation, 'announcement')
assert.deepEqual(Object.keys(design.locales), ['zh-CN', 'zh-TW', 'ja'])
assert.deepEqual(design.dialog, { width: 760, radius: 22, backdrop: '#17221973', blur: 5 })
for (const locale of Object.keys(design.locales)) {
  const document = resolveAnnouncementDocument(design, locale, 'zh-CN')
  assert.ok(isAnnouncementDocument(document), locale + ' has a complete editable document')
  assert.deepEqual(Object.keys(document).sort(), [...announcementTextFields, 'tiers'].sort())
  assert.deepEqual(document.tiers, [{ deposit: '30,000', reward: '18,888' }, { deposit: '50,000', reward: '38,888' }, { deposit: '80,000', reward: '58,888' }, { deposit: '100,000', reward: '88,888' }])
  assert.match(document.period, /2026年10月21日.*2026年11月21日/)
  assert.deepEqual(design.locales[locale].pages.map(page => ({ id: page.id, nodes: page.nodes })), [{ id: 'gift', nodes: [] }])
}
assert.equal(resolveAnnouncementDocument(design, 'en', 'ja').title, '100万人達成記念')
assert.equal(resolveAnnouncementDocument(design, 'zh-TW', 'ja').title, '百萬用戶慶典')
assert.equal(resolveAnnouncementDocument(design, 'ja', 'zh-CN').title, '100万人達成記念')
assert.match(resolveAnnouncementDocument(design, 'zh-CN', 'ja').withdrawalRights, /不影响.*自有资金/)
assert.match(resolveAnnouncementDocument(design, 'ja', 'zh-CN').withdrawalRights, /ご自身の資金を出金する権利に影響/)
assert.equal(resolveAnnouncementDocument({ version: 1, locales: { en: { pages: [] } } }, 'en', 'en'), null)
assert.equal(resolveAnnouncementDocument(null, 'ja', 'zh-CN'), null)

const template = createCelebrationAnnouncementTemplate()
assert.equal(template.campaignKind, 'NOTICE')
assert.equal(template.template, true)
assert.equal(template.status, 'DRAFT')
assert.equal(template.amount, 0)
assert.equal(template.budget, 0)
assert.equal(template.autoSendEnabled, false)
assert.equal(template.autoPopup, true)
assert.equal(template.allowRepeatSend, true)
assert.equal(template.allowRepeatClaim, false)
assert.deepEqual(template.triggerConditions, ['LOGIN'])
assert.deepEqual(template.positions, ['AUTH_HOME', 'AUTH_TRADE', 'AUTH_PROFILE', 'SUPPORT'])
assert.equal(template.endsAt, '2026-11-22T00:00:00', 'The activity includes all of November 21')
assert.deepEqual(parseDesign(template.layoutJson), design)
assert.deepEqual(Object.keys(JSON.parse(template.translations)), ['zh-CN', 'zh-TW', 'ja'])
assert.ok(Object.values(JSON.parse(template.translations)).every(copy => copy.title && copy.body && copy.terms && !copy.claim))
assert.equal(template.layoutJson.includes('"type":"claim"'), false, 'The announcement cannot grant trial credit')

const edited = structuredClone(design)
edited.locales.ja.announcement.title = '編集したお知らせ'
edited.locales.ja.announcement.tiers[0].reward = '20,000'
const roundTrip = parseDesign(JSON.stringify(edited))
assert.equal(resolveAnnouncementDocument(roundTrip, 'ja', 'zh-CN').title, '編集したお知らせ')
assert.equal(resolveAnnouncementDocument(roundTrip, 'ja', 'zh-CN').tiers[0].reward, '20,000')
assert.equal(resolveAnnouncementDocument(roundTrip, 'zh-CN', 'ja').tiers[0].reward, '18,888')
assert.equal(createCelebrationAnnouncementDesign().locales.ja.announcement.title, '100万人達成記念', 'Each template instance is independent')

const document = resolveAnnouncementDocument(design, 'zh-CN', 'zh-CN')
for (const invalid of [null, [], {}, { ...document, label: 1 }, { ...document, title: ' ' }, { ...document, title: 'a'.repeat(201) }, { ...document, footerNote: 'a'.repeat(201) }, { ...document, intro: 'a'.repeat(10001) }, { ...document, tiers: [] }, { ...document, tiers: Array(21).fill({ deposit: '1', reward: '2' }) }, { ...document, tiers: [{ deposit: ' ', reward: '2' }] }, { ...document, tiers: [{ deposit: '1', reward: 'a'.repeat(81) }] }]) assert.equal(isAnnouncementDocument(invalid), false)
assert.equal(isAnnouncementDocument({ ...document, intro: 'a'.repeat(10000), title: 'a'.repeat(200), tiers: [{ deposit: 'a'.repeat(80), reward: '2' }] }), true)
console.log('PASS announcement design: three locales, editable round trip, exact tiers/terms/dates, safe draft notice settings, bounds, old-design compatibility')
