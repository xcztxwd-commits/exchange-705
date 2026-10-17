import { getAccountApiBase } from '@/utils/accountMode'
import { useLocaleStore } from '@/store/locale'
import { supportCopy, supportReplyCopy, supportSystemCopy, supportErrorCopy } from './supportLocalization'
export { isSupportSystem } from './supportLocalization'

export interface Conversation {
  id: number
  userId: number
  adminId: number | null
  status: 'WAITING' | 'ACTIVE' | 'CLOSED'
  createdAt: string
  updatedAt: string
  clientIp: string
  userReadId: number
  adminReadId: number
}
export interface ChatMessage {
  id: number
  requestId?: string
  sender: 'USER' | 'ADMIN' | 'CONTROL' | 'SYSTEM'
  senderId: number
  text: string
  image: boolean
  createdAt: string
}
export interface SupportConfig {
  mode: 'off' | 'external' | 'internal'
  inboxEnabled: boolean
  link: string
  offline: string
  userSound: string
}
export const supportText = (zh: string, en: string, admin = false) =>
  admin ? zh : supportCopy(useLocaleStore().locale, en) ?? useLocaleStore().text(zh, en)
export const supportReplyText = (value: string, admin = false) => admin ? value : supportReplyCopy(useLocaleStore().locale, value)
export const supportSystemText = (message: ChatMessage, admin = false) => admin ? message.text : supportSystemCopy(useLocaleStore().locale, message)
export const supportErrorText = (value: string, admin = false) => admin ? value : supportErrorCopy(useLocaleStore().locale, value)
export const supportStatus = (value: Conversation['status']) => ({ WAITING: supportText('排队中', 'Waiting'), ACTIVE: supportText('接待中', 'Active'), CLOSED: supportText('已结束', 'Closed') })[value]
export const requestId = () =>
  Array.from(crypto.getRandomValues(new Uint8Array(16)), (n) => n.toString(16).padStart(2, '0')).join('')
export const supportUrl = (path: string) =>
  `${String(getAccountApiBase()).replace(/\/$/, '')}${path.replace(/^\/api/, '')}`
export const supportDate = (value: string) =>
  new Date(value).toLocaleString(useLocaleStore().locale, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
