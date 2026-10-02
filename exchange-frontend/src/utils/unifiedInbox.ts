import { reactive } from 'vue'

// Shared by the single notification poller, both headers and the inbox page.
export const inboxState = reactive({ unread: 0, chatUnread: 0 })
export const refreshInbox = () => window.dispatchEvent(new Event('unified-inbox-changed'))
