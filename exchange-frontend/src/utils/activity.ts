import { useLocaleStore } from '@/store/locale'
export interface ActivityItem { delivery: { id: number; receivedAt?: string; openedAt?: string; closedAt?: string; claimedAt?: string }; campaign: { id: number; amount: number; translations: string; defaultLocale: string; endsAt?: string; startsAt?: string; status: string; autoPopup: boolean; animation: string; recentLoginDays: number; hasQuota: boolean }; active: boolean }
const words: Record<string, string[]> = {
 'zh-CN': ['站内信','活动公告','打开礼遇','关闭','立即领取','领取成功','体验金账户','可用体验金','交易占用','累计领取','已转入正式账户的净收益','去交易','仅用于交易，不可提现或划转。亏损优先扣体验金；净收益进入正式交易账户。体验金用完或提现需实名。','暂无活动消息','活动不可领取','已领取','加载中…','重试','上一页','下一页','查看活动','领取截止','领取后自动入账，无需充值','更多交易机会，从这一份礼遇开始。'],
 'zh-TW': ['站內信','活動公告','打開禮遇','關閉','立即領取','領取成功','體驗金帳戶','可用體驗金','交易佔用','累計領取','已轉入正式帳戶的淨收益','去交易','僅用於交易，不可提現或劃轉。虧損優先扣體驗金；淨收益進入正式交易帳戶。體驗金用完或提現需實名。','暫無活動消息','活動不可領取','已領取','載入中…','重試','上一頁','下一頁','查看活動','領取截止','領取後自動入帳，無需充值','更多交易機會，從這一份禮遇開始。'],
 en: ['Inbox','Exclusive invitation','Open gift','Close','Claim reward','Reward received','Trial credit account','Available credit','In orders','Total received','Net profits credited to real account','Start trading','Trading only. Principal cannot be withdrawn or transferred. Losses use trial credit first; net profits go to your real trading account. Verification is required when credit runs out or to withdraw.','No activity messages','Reward unavailable','Claimed','Loading…','Retry','Previous','Next','View activities','Claim by','Instant credit. No deposit required.','Your next trading opportunity starts with a little extra.'],
 ja: ['受信箱','キャンペーン','ギフトを開く','閉じる','今すぐ受け取る','受取完了','体験資金口座','利用可能','取引中','受取累計','本口座に入金済みの純利益','取引する','取引専用。元本は出金・振替不可。損失は体験資金から先に差し引き、純利益は本取引口座へ入金します。資金消尽時または出金時は本人確認が必要です。','お知らせはありません','受取不可','受取済み','読み込み中…','再試行','前へ','次へ','キャンペーンを見る','受取期限','入金不要。受取後すぐに反映。','新しい取引のチャンスを、このギフトから。'],
 ko: ['받은 편지함','이벤트 안내','선물 열기','닫기','지금 받기','수령 완료','체험금 계정','사용 가능','거래 중','총 수령액','실제 계정에 입금된 순이익','거래 시작','거래 전용입니다. 원금은 출금·이체할 수 없습니다. 손실은 체험금에서 우선 차감되며 순이익은 실제 계정에 입금됩니다. 체험금 소진 또는 출금 시 본인 인증이 필요합니다.','이벤트 메시지가 없습니다','수령 불가','수령 완료','로딩 중…','재시도','이전','다음','이벤트 보기','수령 기한','입금 없이 즉시 지급','새로운 거래의 기회를 선물로 시작하세요.']
}
export function useActivityCopy() {
 const locale = useLocaleStore()
 return (index: number) => (words[locale.locale] || words.en)![index] || words.en![index] || ''
}
export function activityContent(item: ActivityItem, language: string): Record<string,string> {
 let translations: Record<string,Record<string,string>> = {}
 try { translations = JSON.parse(item.campaign.translations) } catch { /* Display safe empty content on malformed legacy data. */ }
 const c = translations[language] || translations[item.campaign.defaultLocale] || translations.en || Object.values(translations)[0] || {}
 return Object.fromEntries(Object.entries(c).map(([key,value]) => [key,String(value).split('{amount}').join(String(Number(item.campaign.amount))).split('{days}').join(String(item.campaign.recentLoginDays))]))
}
export const money = (value: number | string | undefined) => Number(value || 0).toLocaleString('en-US', { maximumFractionDigits: 2 })
