import assert from 'node:assert/strict'
import { reactive, isReactive } from 'vue'
import { announcementFieldGroups, announcementTranslations, cloneAnnouncementLocale, validateAnnouncementDocument } from '../src/utils/announcementEditor.ts'
import { activitySettings, validateActivitySettings, assertActivitySettingsSaved } from '../src/utils/activitySettings.ts'

const keys = announcementFieldGroups.flatMap(group => group.fields.map(field => field.key))
assert.equal(new Set(keys).size, keys.length, 'Each editable field appears once')
assert.deepEqual([...keys].sort(), 'label milestone milestoneLabel title subtitle period greeting intro depositHeading rewardHeading currency currencyNote rulesHeading tierRule withdrawalHeading withdrawalRule withdrawalRights tradingRule durationRule supportRule signoff footerNote supportLabel acknowledgeLabel'.split(' ').sort(), 'Every visible document field is editable')
const document = { ...Object.fromEntries(keys.map(key => [key, '公告文案'])), tiers: [{ deposit: '30,000', reward: '18,888' }] }
validateAnnouncementDocument(document)
validateAnnouncementDocument({ ...document, title: '题'.repeat(200), intro: '文'.repeat(10000), tiers: Array.from({ length: 20 }, () => ({ deposit: '1', reward: '2' })) })
for (const invalid of [null, [], { ...document, title: '' }, { ...document, title: '题'.repeat(201) }, { ...document, intro: '文'.repeat(10001) }, { ...document, withdrawalRights: ' ' }, { ...document, tiers: [] }, { ...document, tiers: Array(21).fill(document.tiers[0]) }, { ...document, tiers: [{ deposit: '', reward: '1' }] }, { ...document, tiers: [{ deposit: '1', reward: '2'.repeat(81) }] }, { ...document, tiers: [null] }]) assert.throws(() => validateAnnouncementDocument(invalid))
const design = { version: 1, presentation: 'announcement', locales: { 'zh-CN': { pages: [{ id: 'gift', name: '公告', nodes: [] }], announcement: { ...document, title: '题'.repeat(200) } }, ja: { pages: [{ id: 'gift', name: 'お知らせ', nodes: [] }], announcement: { ...document, title: '日本語のお知らせ' } } } }
const reactiveDesign = reactive(structuredClone(design))
assert.equal(isReactive(reactiveDesign.locales['zh-CN']), true)
assert.throws(() => structuredClone(reactiveDesign.locales['zh-CN']), { name: 'DataCloneError' }, 'Regression begins with a real Vue proxy')
delete reactiveDesign.locales.ja
reactiveDesign.locales.ja = cloneAnnouncementLocale(reactiveDesign.locales['zh-CN'])
reactiveDesign.locales.ja.announcement.title = '再追加した日本語'
reactiveDesign.locales.ja.announcement.tiers[0].reward = '20,000'
reactiveDesign.locales.ja.pages[0].name = '日本語のページ'
assert.equal(reactiveDesign.locales['zh-CN'].announcement.title.length, 200, 'Re-added locale has independent text')
assert.equal(reactiveDesign.locales['zh-CN'].announcement.tiers[0].reward, '18,888', 'Re-added locale has independent nested tiers')
assert.equal(reactiveDesign.locales['zh-CN'].pages[0].name, '公告', 'Re-added locale has independent pages')
reactiveDesign.locales.en = cloneAnnouncementLocale(reactiveDesign.locales['zh-CN'])
assert.equal(reactiveDesign.locales.en.announcement.intro, document.intro, 'Missing language can be created from reactive fallback')
const summaries = announcementTranslations(design)
assert.equal(summaries['zh-CN'].title.length, 160, 'Legacy inbox summary retains its title bound')
assert.equal(design.locales['zh-CN'].announcement.title.length, 200, 'Document title stays intact')
assert.equal(summaries.ja.title, '日本語のお知らせ')
assert.equal(summaries.ja.body, document.intro)
assert.equal(summaries.ja.close, document.acknowledgeLabel)
assert.equal('claim' in summaries.ja, false, 'Notice summary adds no claim action')

const trial = activitySettings({ amount: 300, budget: 300000, maxClaims: 1000, status: 'DRAFT', autoSendEnabled: false })
assert.equal(trial.campaignKind, 'TRIAL')
assert.equal(trial.amount, 300)
validateActivitySettings(trial)
assert.throws(() => validateActivitySettings({ ...trial, amount: 0 }), /体验金金额/)
assert.throws(() => validateActivitySettings({ ...trial, campaignKind: 'UNKNOWN' }), /活动类型/)
const notice = activitySettings({ campaignKind: 'NOTICE', amount: 0, budget: 0, template: false, status: 'DRAFT', recentLoginDays: 3, positions: ['AUTH_HOME', 'AUTH_TRADE'], triggerConditions: ['LOGIN'], autoSendEnabled: true, allowRepeatSend: true, startsAt: '2026-10-21T00:00:00', endsAt: '2026-11-22T00:00:00' })
validateActivitySettings(notice)
assert.equal(notice.amount, 0)
assert.equal(notice.budget, 0)
assert.equal(notice.claimValidityDays, null)
assert.equal(notice.allowRepeatClaim, false)
assert.equal(notice.recentLoginDays, 0)
assert.equal(notice.animation, 'NONE')
assert.equal(notice.allowRepeatSend, true)
for (const changed of [{ amount: 1 }, { budget: 1 }, { allowRepeatClaim: true }, { claimValidityDays: 3 }, { positions: ['ANONYMOUS_HOME'] }, { triggerConditions: ['PAGE_HOME'] }, { startsAt: null }, { endsAt: '2026-10-20T00:00:00' }]) assert.throws(() => validateActivitySettings({ ...notice, ...changed }))
assertActivitySettingsSaved({ campaignKind: 'NOTICE', amount: 0, budget: 0 }, { campaignKind: 'NOTICE', amount: '0.00', budget: '0' })
assert.throws(() => assertActivitySettingsSaved({ campaignKind: 'NOTICE' }, { campaignKind: 'TRIAL' }), /campaignKind/)
console.log('PASS announcement editor: all fields, text/tier bounds, isolated multilingual summaries, NOTICE settings and legacy TRIAL validation')
