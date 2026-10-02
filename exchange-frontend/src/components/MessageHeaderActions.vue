<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useLocaleStore } from '@/store/locale'
import { useAuthStore } from '@/store/auth'
import { supportText } from '@/utils/support'
import { inboxState } from '../utils/unifiedInbox'
const router = useRouter(), locale = useLocaleStore(), auth = useAuthStore()
</script>
<template>
  <nav class="message-header-actions" :aria-label="supportText('客服、消息与语言', 'Support, inbox and language')">
    <button type="button" data-action="support" :aria-label="locale.t('customerService')" :title="locale.t('customerService')" @click="router.push('/customer-service')">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 13v-2a8 8 0 0 1 16 0v2M4 12H3v6h3v-6H4Zm16 0h1v6h-3v-6h2Zm0 6c0 3-3 3-6 3"/><path d="M11 21h3"/></svg>
      <b v-if="auth.token && inboxState.chatUnread">{{ inboxState.chatUnread > 99 ? '99+' : inboxState.chatUnread }}</b>
    </button>
    <button type="button" data-action="inbox" :aria-label="supportText('站内信', 'Inbox')" :title="supportText('站内信', 'Inbox')" @click="router.push('/inbox')">
      <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="3"/><path d="m4 7 8 6 8-6"/></svg>
      <b v-if="auth.token && inboxState.unread">{{ inboxState.unread > 99 ? '99+' : inboxState.unread }}</b>
    </button>
    <button type="button" data-action="language" :aria-label="locale.t('language')" :title="locale.t('language')" @click="router.push('/language')">
      <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c5 5 5 13 0 18-5-5-5-13 0-18Z"/></svg>
    </button>
  </nav>
</template>
<style scoped>
.message-header-actions{display:flex;align-items:center;gap:6px;flex-shrink:0}
.message-header-actions button{position:relative;display:flex;align-items:center;justify-content:center;width:44px;height:44px;padding:10px;border:1px solid #e5e9e1;border-radius:12px;background:#f6f8f3;color:#50633c;cursor:pointer}
.message-header-actions button:hover{background:#edf3e5}
.message-header-actions button:focus-visible{outline:2px solid #73b100;outline-offset:2px}
.message-header-actions svg{width:22px;height:22px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}
.message-header-actions b{position:absolute;top:-3px;right:-3px;min-width:16px;padding:0 3px;border-radius:10px;background:#739f32;color:#fff;font:11px/16px sans-serif}
</style>
