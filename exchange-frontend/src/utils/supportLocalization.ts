import { supportAsia } from './supportTranslationsAsia.ts'
import { supportEurope } from './supportTranslationsEurope.ts'
import { supportOther } from './supportTranslationsOther.ts'

export const supportMessages: Record<string, Record<string, string>> = { ...supportAsia, ...supportEurope, ...supportOther }

export function supportCopy(locale: string, key: string, params: Record<string, string | number> = {}) {
  const value = supportMessages[locale]?.[key] ?? supportMessages.en?.[key]
  return value?.replace(/\{(\w+)\}/g, (token, name) => Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : token)
}

const defaultReplies: Record<string, string> = {
  '您好，欢迎联系在线客服。请描述您需要帮助的问题。': 'Welcome to customer support. Please describe how we can help.',
  '您好，歡迎聯繫線上客服。請描述您需要協助的問題。': 'Welcome to customer support. Please describe how we can help.',
  '客服暂未在线，您可以留言；上线后将按排队顺序接待。': 'Agents are offline. Leave a message and we will respond in queue order when available.',
  '客服暫未在線，您可以留言；上線後將按排隊順序接待。': 'Agents are offline. Leave a message and we will respond in queue order when available.',
  '客服暂未在线，可先留言': 'Agents are offline. Leave a message.',
}
const systemEvents: Record<string, string> = {
  '客服已接入': 'An agent has joined the conversation',
  '总控管理已接入': 'A support supervisor has joined the conversation',
  '客服结束会话': 'The agent ended the conversation',
  '用户结束会话': 'You ended the conversation',
}
const serverErrors: Record<string, string> = {
  '无权访问该会话': 'You do not have access to this conversation',
  '站内客服未开启': 'Internal support is not enabled',
  '会话不存在': 'Conversation not found',
  '会话已结束': 'This conversation has ended',
  '会话创建过于频繁，请稍后重试': 'Too many new conversations. Please try again later.',
  '发送过快，请稍后重试': 'Too many messages. Please try again later.',
  '重试编号已用于其他内容': 'This retry identifier was already used for different content',
  '图片为空或超过5MB': 'The image is empty or exceeds 5MB',
  '请上传有效的 JPG、PNG 或 GIF 图片（最多1600万像素）': 'Upload a valid JPG, PNG or GIF image, up to 16 megapixels',
  '图片不存在': 'Image not found',
}

export function supportReplyCopy(locale: string, value: string) {
  const key = defaultReplies[value] ?? (Object.values(defaultReplies).includes(value) ? value : undefined)
  return key ? supportCopy(locale, key) ?? value : value
}

export function isSupportSystem(message: { sender: string; requestId?: string }) {
  // Human request IDs cannot contain dots; only the server can create this event prefix.
  return message.sender === 'SYSTEM' || message.sender === 'CONTROL' && message.requestId?.startsWith('system.') === true
}

export function supportSystemCopy(locale: string, message: { sender: string; text: string; requestId?: string }) {
  if (!isSupportSystem(message)) return message.text
  const reply = supportReplyCopy(locale, message.text)
  if (reply !== message.text) return reply
  const key = systemEvents[message.text] ?? (Object.values(systemEvents).includes(message.text) ? message.text : undefined)
  if (key) return supportCopy(locale, key) ?? message.text
  const transfer = message.text.match(/^会话转接：(null|-?\d+) -> (-?\d+)$/)
  return transfer ? supportCopy(locale, 'Conversation transferred: {from} → {to}', { from: transfer[1] === 'null' ? supportCopy(locale, 'Support supervisor')! : transfer[1]!, to: transfer[2]! }) ?? message.text : message.text
}

export function supportErrorKey(value: string) {
  return serverErrors[value] ?? (Object.prototype.hasOwnProperty.call(supportMessages.en, value) ? value : undefined)
}

export function supportErrorCopy(locale: string, value: string) {
  const key = supportErrorKey(value)
  return key ? supportCopy(locale, key) ?? value : value
}
