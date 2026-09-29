import { getAccountApiBase } from '@/utils/accountMode'
import { useLocaleStore } from '@/store/locale'

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
  sender: 'USER' | 'ADMIN' | 'SYSTEM'
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
  admin ? zh : useLocaleStore().text(zh, en)
export const requestId = () =>
  Array.from(crypto.getRandomValues(new Uint8Array(16)), (n) => n.toString(16).padStart(2, '0')).join('')
export const supportUrl = (path: string) =>
  `${String(getAccountApiBase()).replace(/\/$/, '')}${path.replace(/^\/api/, '')}`
export const supportDate = (value: string) =>
  new Date(value).toLocaleString(undefined, {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
