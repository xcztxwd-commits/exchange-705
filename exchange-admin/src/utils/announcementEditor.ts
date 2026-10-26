import { toRaw } from 'vue'
import type { ActivityDesign } from '../../../exchange-frontend/src/utils/activityDesign'
import type { AnnouncementDocument } from '../../../exchange-frontend/src/utils/announcementDesign'

type AnnouncementField = Exclude<keyof AnnouncementDocument, 'tiers'>
export function cloneAnnouncementLocale(value: ActivityDesign['locales'][string]) {
  return structuredClone(toRaw(value))
}

export const announcementFieldGroups: { title: string; fields: { key: AnnouncementField; label: string; long?: boolean }[] }[] = [
  { title: '标题与活动时间', fields: [
    { key: 'label', label: '公告类型标签' }, { key: 'milestone', label: '用户数量' },
    { key: 'milestoneLabel', label: '用户数量说明' }, { key: 'title', label: '公告标题' },
    { key: 'subtitle', label: '公告副标题' }, { key: 'period', label: '展示的活动时间' },
  ] },
  { title: '开场文案', fields: [
    { key: 'greeting', label: '称呼' }, { key: 'intro', label: '活动介绍', long: true },
  ] },
  { title: '奖励表格', fields: [
    { key: 'depositHeading', label: '入金列表头' }, { key: 'rewardHeading', label: '奖励列表头' },
    { key: 'currency', label: '金额单位' }, { key: 'currencyNote', label: '表格下方说明' },
  ] },
  { title: '活动规则与提款说明', fields: [
    { key: 'rulesHeading', label: '规则标题' }, { key: 'tierRule', label: '奖励档位规则', long: true },
    { key: 'withdrawalHeading', label: '提款说明标题' }, { key: 'withdrawalRule', label: '提款与活动资格', long: true },
    { key: 'withdrawalRights', label: '自有资金提取权利', long: true }, { key: 'tradingRule', label: '体验金使用及回收规则', long: true },
    { key: 'durationRule', label: '活动时间规则', long: true }, { key: 'supportRule', label: '客服咨询说明', long: true },
    { key: 'signoff', label: '结束语', long: true },
  ] },
  { title: '固定底部与按钮', fields: [
    { key: 'footerNote', label: '底部说明' }, { key: 'supportLabel', label: '客服按钮文案' },
    { key: 'acknowledgeLabel', label: '确认按钮文案' },
  ] },
]

export function validateAnnouncementDocument(value: unknown): asserts value is AnnouncementDocument {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('公告内容格式无效')
  const document = value as Record<string, unknown>
  for (const group of announcementFieldGroups) for (const field of group.fields) {
    const text = document[field.key]
    const limit = field.long ? 10000 : 200
    if (typeof text !== 'string' || !text.trim() || text.length > limit) throw new Error(`${field.label}须为1至${limit}字`)
  }
  if (!Array.isArray(document.tiers) || !document.tiers.length || document.tiers.length > 20) throw new Error('奖励表格须保留1至20个档位')
  for (const [index, tier] of document.tiers.entries()) {
    if (!tier || typeof tier !== 'object') throw new Error(`第${index + 1}档格式无效`)
    for (const key of ['deposit', 'reward']) if (typeof tier[key] !== 'string' || !tier[key].trim() || tier[key].length > 80) throw new Error(`第${index + 1}档金额须为1至80字`)
  }
}

// The inbox keeps a short legacy summary; the structured document remains authoritative.
export function announcementTranslations(design: ActivityDesign) {
  return Object.fromEntries(Object.entries(design.locales).map(([locale, value]) => {
    validateAnnouncementDocument(value.announcement)
    const document = value.announcement
    return [locale, {
      title: document.title.slice(0, 160), body: document.intro, terms: document.tradingRule,
      open: document.supportLabel, close: document.acknowledgeLabel,
    }]
  }))
}
