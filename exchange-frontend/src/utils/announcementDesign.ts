import type { ActivityDesign } from './activityDesign'

export const announcementTextFields = [
  'label', 'milestone', 'milestoneLabel', 'title', 'subtitle', 'period', 'greeting', 'intro',
  'depositHeading', 'rewardHeading', 'currency', 'currencyNote', 'rulesHeading', 'tierRule',
  'withdrawalHeading', 'withdrawalRule', 'withdrawalRights', 'tradingRule', 'durationRule',
  'supportRule', 'signoff', 'footerNote', 'supportLabel', 'acknowledgeLabel',
] as const

export type AnnouncementDocument = Record<typeof announcementTextFields[number], string> & {
  tiers: { deposit: string; reward: string }[]
}

const longFields: readonly string[] = ['intro', 'tierRule', 'withdrawalRule', 'withdrawalRights', 'tradingRule', 'durationRule', 'supportRule', 'signoff']

export function isAnnouncementDocument(value: unknown): value is AnnouncementDocument {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const document = value as Record<string, unknown>
  return announcementTextFields.every(field => typeof document[field] === 'string' && document[field].trim().length > 0
    && document[field].length <= (longFields.includes(field) ? 10000 : 200))
    && Array.isArray(document.tiers) && document.tiers.length >= 1 && document.tiers.length <= 20
    && document.tiers.every(tier => tier && typeof tier === 'object' && !Array.isArray(tier)
      && ['deposit', 'reward'].every(field => typeof tier[field] === 'string' && tier[field].trim().length > 0 && tier[field].length <= 80))
}

export function resolveAnnouncementDocument(design: ActivityDesign | null, locale: string, fallback: string): AnnouncementDocument | null {
  if (design?.presentation !== 'announcement') return null
  const document = design.locales[locale]?.announcement ?? design.locales[fallback]?.announcement
  return isAnnouncementDocument(document) ? document : null
}

function celebrationDocuments(): Record<string, AnnouncementDocument> {
  const tiers = () => [
    { deposit: '30,000', reward: '18,888' },
    { deposit: '50,000', reward: '38,888' },
    { deposit: '80,000', reward: '58,888' },
    { deposit: '100,000', reward: '88,888' },
  ]
  const zh: AnnouncementDocument = {
    label: '平台公告', milestone: '1,000,000', milestoneLabel: '同时在线交易用户', title: '百万用户庆典',
    subtitle: '交易体验金奖励活动', period: '2026年10月21日 — 2026年11月21日', greeting: '尊敬的用户，您好！',
    intro: '为庆祝交易所同时在线交易用户突破100万人，感谢广大用户长期以来的信任与支持，平台拟推出百万用户庆典交易体验金奖励活动。',
    depositHeading: '活动期间累计入金', rewardHeading: '对应交易体验金', currency: '美元', currencyNote: '金额单位：美元（USD）', tiers: tiers(),
    rulesHeading: '活动规则',
    tierRule: '活动期间，用户累计入金达到相应奖励标准后，即符合对应档位的体验金领取条件。用户达到更高奖励档位时，按照最高档位计算奖励，已发放的体验金计入该档位奖励总额，不重复叠加。',
    withdrawalHeading: '提款与活动资格', withdrawalRule: '请注意，活动期间申请提款，将被视为自动放弃本次活动的参与资格。',
    withdrawalRights: '该限制仅涉及活动奖励资格，不影响用户依据正常账户规则提取自有资金的权利。',
    tradingRule: '本次活动发放的体验金仅限用于交易，不支持直接提现。使用体验金进行交易所产生的利润，可按照平台公布的交易及提现规则申请提取。活动结束后，平台将按照活动条款收回相应的交易体验金。',
    durationRule: '活动时间为2026年10月21日至2026年11月21日。',
    supportRule: '如您希望参与本次活动，或需要了解具体奖励规则，请联系平台官方在线客服进行咨询。',
    signoff: '感谢您一直以来的信任与支持，祝您生活愉快！', footerNote: '体验金仅限交易使用，不支持直接提现。',
    supportLabel: '咨询在线客服', acknowledgeLabel: '我已知悉',
  }
  return {
    'zh-CN': zh,
    'zh-TW': {
      label: '平台公告', milestone: '1,000,000', milestoneLabel: '同時在線交易用戶', title: '百萬用戶慶典',
      subtitle: '交易體驗金獎勵活動', period: '2026年10月21日 — 2026年11月21日', greeting: '尊敬的用戶，您好！',
      intro: '為慶祝交易所同時在線交易用戶突破100萬人，感謝廣大用戶長期以來的信任與支持，平台擬推出百萬用戶慶典交易體驗金獎勵活動。',
      depositHeading: '活動期間累計入金', rewardHeading: '對應交易體驗金', currency: '美元', currencyNote: '金額單位：美元（USD）', tiers: tiers(),
      rulesHeading: '活動規則',
      tierRule: '活動期間，用戶累計入金達到相應獎勵標準後，即符合對應檔位的體驗金領取條件。用戶達到更高獎勵檔位時，按照最高檔位計算獎勵，已發放的體驗金計入該檔位獎勵總額，不重複疊加。',
      withdrawalHeading: '提款與活動資格', withdrawalRule: '請注意，活動期間申請提款，將被視為自動放棄本次活動的參與資格。',
      withdrawalRights: '該限制僅涉及活動獎勵資格，不影響用戶依據正常帳戶規則提取自有資金的權利。',
      tradingRule: '本次活動發放的體驗金僅限用於交易，不支持直接提現。使用體驗金進行交易所產生的利潤，可按照平台公布的交易及提現規則申請提取。活動結束後，平台將按照活動條款收回相應的交易體驗金。',
      durationRule: '活動時間為2026年10月21日至2026年11月21日。',
      supportRule: '如您希望參與本次活動，或需要了解具體獎勵規則，請聯繫平台官方線上客服進行諮詢。',
      signoff: '感謝您一直以來的信任與支持，祝您生活愉快！', footerNote: '體驗金僅限交易使用，不支持直接提現。',
      supportLabel: '諮詢線上客服', acknowledgeLabel: '我已知悉',
    },
    ja: {
      label: 'お知らせ', milestone: '1,000,000', milestoneLabel: '同時オンライン取引ユーザー', title: '100万人達成記念',
      subtitle: '取引体験ボーナスキャンペーン', period: '2026年10月21日 — 2026年11月21日', greeting: 'ユーザーの皆様へ',
      intro: '平素より当プラットフォームをご利用いただき、誠にありがとうございます。取引所の同時オンライン取引ユーザー数が100万人を突破したことを記念し、皆様の長年にわたるご信頼とご支援への感謝を込めて、100万人達成記念・取引体験ボーナスキャンペーンを実施する予定です。',
      depositHeading: '期間中の累計入金額', rewardHeading: '取引体験ボーナス', currency: '米ドル', currencyNote: '金額単位：米ドル（USD）', tiers: tiers(),
      rulesHeading: 'キャンペーン詳細',
      tierRule: 'キャンペーン期間中の累計入金額が所定の条件に達すると、該当するランクの取引体験ボーナスを受け取る資格が得られます。より上位のランクに達した場合は、達成した最上位ランクを基準にボーナス額を計算します。すでに付与されたボーナスは当該ランクの総額に含まれ、重複して加算されることはありません。',
      withdrawalHeading: '出金とキャンペーン参加資格について', withdrawalRule: 'キャンペーン期間中に出金を申請した場合、本キャンペーンへの参加資格を自動的に放棄したものとみなされます。',
      withdrawalRights: 'この制限はキャンペーンの特典受給資格にのみ適用され、通常の口座規定に基づき、お客様ご自身の資金を出金する権利に影響を与えるものではありません。',
      tradingRule: '本キャンペーンで付与される取引体験ボーナスは取引にのみ利用でき、直接出金することはできません。ボーナスを利用した取引で生じた利益は、当プラットフォームが公表する取引・出金規定に従って出金を申請できます。キャンペーン終了後、当プラットフォームはキャンペーン規約に基づき、該当する取引体験ボーナスを回収します。',
      durationRule: 'キャンペーン期間：2026年10月21日から2026年11月21日まで。',
      supportRule: '本キャンペーンへの参加をご希望の方、または特典の詳しい条件についてお知りになりたい方は、当プラットフォームの公式オンラインサポートまでお問い合わせください。',
      signoff: '日頃のご信頼とご支援に、心より感謝申し上げます。皆様の毎日が健やかで充実したものとなりますようお祈り申し上げます。',
      footerNote: 'ボーナスは取引専用です。直接出金はできません。', supportLabel: 'サポートに相談', acknowledgeLabel: '確認しました',
    },
  }
}

export function createCelebrationAnnouncementDesign(): ActivityDesign {
  return {
    version: 1, presentation: 'announcement',
    dialog: { width: 760, radius: 22, backdrop: '#17221973', blur: 5 },
    locales: Object.fromEntries(Object.entries(celebrationDocuments()).map(([locale, announcement]) => [locale, {
      pages: [{ id: 'gift', name: announcement.label, nodes: [] }], announcement,
    }])),
  }
}

export function createCelebrationAnnouncementTemplate() {
  const design = createCelebrationAnnouncementDesign()
  const translations = Object.fromEntries(Object.entries(design.locales).map(([locale, entry]) => {
    const document = entry.announcement!
    return [locale, { title: document.title, body: document.intro, terms: [document.tierRule, document.withdrawalRule, document.withdrawalRights, document.tradingRule, document.durationRule, document.supportRule].join('\n\n'), open: document.acknowledgeLabel, close: document.acknowledgeLabel, success: document.acknowledgeLabel }]
  }))
  return {
    name: '百万用户庆典公告', campaignKind: 'NOTICE', template: true, status: 'DRAFT', amount: 0, budget: 0,
    maxClaims: 1000000, claimCount: 0, granted: 0, recentLoginDays: 0, defaultLocale: 'zh-CN',
    translations: JSON.stringify(translations), layoutJson: JSON.stringify(design),
    positions: ['AUTH_HOME', 'AUTH_TRADE', 'AUTH_PROFILE', 'SUPPORT'], triggerConditions: ['LOGIN'],
    allowRepeatSend: true, repeatUnread: true, allowRepeatClaim: false, autoPopup: true, autoSendEnabled: false,
    animation: 'NONE', claimValidityDays: null, startsAt: '2026-10-21T00:00:00', endsAt: '2026-11-22T00:00:00',
  }
}
