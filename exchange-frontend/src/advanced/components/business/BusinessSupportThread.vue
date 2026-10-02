<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted, nextTick, computed } from 'vue'
import { useBusinessLifecycle } from './useBusinessLifecycle'
const { request, setTimeout } = useBusinessLifecycle()
import { requestId, supportText, supportDate, type Conversation, type ChatMessage } from '@/utils/support'
import SupportImage from '@/components/SupportImage.vue'
const props = withDefaults(
  defineProps<{
    id: number
    admin?: boolean
    canReply?: boolean
    canImage?: boolean
    disabled?: boolean
    offline?: string
  }>(),
  { admin: false, canReply: true, canImage: true, disabled: false, offline: '' },
)
const emit = defineEmits<{ updated: [conversation: Conversation] }>()
const t = (zh: string, en: string) => supportText(zh, en, props.admin)
const endpoint = computed(() => `/${props.admin ? 'admin' : 'user'}/support`)
const messages = ref<ChatMessage[]>([]),
  conversation = ref<Conversation>(),
  error = ref(''),
  sending = ref(false),
  loading = ref(false)
const draft = ref(''),
  file = ref<File>(),
  input = ref<HTMLInputElement>(),
  log = ref<HTMLElement>()
const ahead = ref(0),
  online = ref(0)
let version = 0,
  timer: ReturnType<typeof setTimeout> | undefined,
  pendingId = '',
  pendingSignature = '',
  disposed = false
const writable = computed(
  () =>
    !props.disabled &&
    props.canReply &&
    conversation.value?.status !== 'CLOSED' &&
    (!props.admin || conversation.value?.status === 'ACTIVE'),
)
async function poll() {
  if (loading.value || disposed) return
  const current = version,
    id = props.id
  loading.value = true
  try {
    let rows: ChatMessage[]
    do {
      const last = messages.value[messages.value.length - 1]?.id || 0
      const data: any = await request.get(`${endpoint.value}/sessions/${id}`, { params: { after: last } })
      if (current !== version || disposed) return
      conversation.value = data.conversation
      ahead.value = data.ahead
      online.value = data.online
      emit('updated', data.conversation)
      rows = data.messages
      const nearBottom =
        !log.value || log.value.scrollHeight - log.value.scrollTop - log.value.clientHeight < 100
      messages.value.push(...rows.filter((row) => !messages.value.some((m) => m.id === row.id)))
      if (nearBottom) {
        await nextTick()
        log.value?.scrollTo({ top: log.value.scrollHeight })
      }
    } while (rows.length === 100)
    const through = messages.value[messages.value.length - 1]?.id
    const read = props.admin ? conversation.value?.adminReadId : conversation.value?.userReadId
    if (through && through > (read || 0) && document.visibilityState === 'visible')
      await request.post(`${endpoint.value}/sessions/${id}/read`, { through })
    if (current === version) error.value = ''
  } catch (e: any) {
    if (current === version) error.value = e.message
  } finally {
    loading.value = false
    if (!disposed) timer = setTimeout(poll, 3000)
  }
}
watch(
  () => props.id,
  () => {
    ++version
    messages.value = []
    conversation.value = undefined
    error.value = ''
    draft.value = ''
    file.value = undefined
    pendingId = ''
    clearTimeout(timer)
    if (!loading.value) void poll()
  },
  { immediate: true },
)
async function send() {
  if (sending.value || !writable.value || (!draft.value.trim() && !file.value)) return
  const signature = JSON.stringify([
    props.id,
    draft.value,
    file.value?.name,
    file.value?.size,
    file.value?.lastModified,
  ])
  if (signature !== pendingSignature) {
    pendingId = requestId()
    pendingSignature = signature
  }
  const id = props.id,
    current = version
  sending.value = true
  error.value = ''
  try {
    if (file.value) {
      const data = new FormData()
      data.append('file', file.value)
      data.append('requestId', pendingId)
      await request.post(`${endpoint.value}/sessions/${id}/images`, data)
    } else
      await request.post(`${endpoint.value}/sessions/${id}/messages`, {
        requestId: pendingId,
        text: draft.value,
      })
    if (current === version) {
      if (!file.value) draft.value = ''
      file.value = undefined
      if (input.value) input.value.value = ''
      pendingId = ''
      pendingSignature = ''
      clearTimeout(timer)
      if (!loading.value) void poll()
    }
  } catch (e: any) {
    if (current === version) error.value = e.message
  } finally {
    sending.value = false
  }
}
function choose(event: Event) {
  const selected = (event.target as HTMLInputElement).files?.[0]
  if (
    selected &&
    (!['image/png', 'image/jpeg', 'image/gif'].includes(selected.type) || selected.size > 5 * 1024 * 1024)
  ) {
    error.value = t('请选择 5MB 以内的 JPG、PNG 或 GIF 图片', 'Choose a JPG, PNG or GIF under 5MB')
    return
  }
  file.value = selected
}
function visible() {
  if (document.visibilityState === 'visible' && !loading.value) {
    clearTimeout(timer)
    void poll()
  }
}
onMounted(() => document.addEventListener('visibilitychange', visible))
onUnmounted(() => {
  disposed = true
  ++version
  clearTimeout(timer)
  document.removeEventListener('visibilitychange', visible)
})
function onEnter(event: KeyboardEvent) {
  if (!event.isComposing) {
    event.preventDefault()
    void send()
  }
}
</script>
<template>
  <section class="support-thread">
    <div class="thread-status" aria-live="polite">
      <span class="status-dot" :class="conversation?.status?.toLowerCase()"></span>
      <span v-if="conversation?.status === 'WAITING'"
        >{{ t('排队中 · 前方', 'Waiting · ahead') }} {{ ahead }} ·
        {{
          online
            ? t('客服将尽快接待', 'An agent will join shortly')
            : offline || t('客服暂未在线，可先留言', 'Agents are offline. Leave a message.')
        }}</span
      >
      <span v-else-if="conversation?.status === 'ACTIVE'"
        >{{ t('客服已接入', 'Connected to an agent') }} · #{{ conversation.adminId }}</span
      >
      <span v-else-if="conversation?.status === 'CLOSED'">{{
        t('会话已结束，记录已保留', 'Session closed. Your history is retained.')
      }}</span>
      <span v-else>{{ t('加载会话…', 'Loading conversation…') }}</span>
    </div>
    <div
      ref="log"
      class="thread-log"
      role="log"
      :aria-label="t('聊天记录', 'Chat history')"
      aria-live="polite"
    >
      <p v-if="!messages.length && !loading" class="thread-empty">
        {{ t('发送第一条消息', 'Send your first message') }}
      </p>
      <article
        v-for="m in messages"
        :key="m.id"
        class="message"
        :class="{ mine: (admin ? ['ADMIN','CONTROL'].includes(m.sender) : m.sender === 'USER'), system: m.sender === 'SYSTEM' }"
      >
        <template v-if="m.sender === 'SYSTEM'"
          ><p>{{ m.text }}</p>
          <time>{{ supportDate(m.createdAt) }}</time></template
        >
        <template v-else>
          <div class="message-meta">
            {{ m.sender === 'USER' ? t('客户', 'You') : t('客服', 'Agent') }} · {{ supportDate(m.createdAt) }}
          </div>
          <div class="message-bubble">
            <SupportImage
              v-if="m.image && canImage"
              :endpoint="`${endpoint}/images/${m.id}`"
              :label="t('聊天图片', 'Chat image')"
            /><span v-else-if="m.image">{{ t('无图片查看权限', 'Image access not permitted') }}</span>
            <p v-if="m.text">{{ m.text }}</p>
          </div>
          <small v-if="(admin ? ['ADMIN','CONTROL'].includes(m.sender) : m.sender === 'USER')">{{
            m.id <= (admin ? conversation?.userReadId || 0 : conversation?.adminReadId || 0)
              ? t('已读', 'Read')
              : t('已发送', 'Sent')
          }}</small>
        </template>
      </article>
    </div>
    <p v-if="error" class="thread-error" role="alert">
      {{ error }} <button type="button" @click="visible">{{ t('重试', 'Retry') }}</button>
    </p>
    <form v-if="writable" class="thread-composer" @submit.prevent="send">
      <div v-if="file" class="file-choice">
        {{ file.name }}
        <button type="button" @click="file = undefined">{{ t('取消图片', 'Remove image') }}</button>
      </div>
      <textarea
        v-model="draft"
        :disabled="sending"
        maxlength="4000"
        rows="2"
        :aria-label="t('消息内容', 'Message')"
        :placeholder="
          t('输入消息，Enter 发送，Shift+Enter 换行', 'Message · Enter to send · Shift+Enter for a new line')
        "
        @keydown.enter.exact="onEnter"
      ></textarea>
      <div class="composer-actions">
        <label v-if="canImage" class="image-choice"
          >{{ t('添加图片', 'Add image')
          }}<input
            ref="input"
            type="file"
            accept="image/png,image/jpeg,image/gif"
            :disabled="sending"
            @change="choose" /></label
        ><small>{{ draft.length }}/4000</small
        ><button class="send-button" type="submit" :disabled="sending || (!draft.trim() && !file)">
          {{ sending ? t('发送中…', 'Sending…') : file ? t('发送图片', 'Send image') : t('发送', 'Send') }}
        </button>
      </div>
    </form>
  </section>
</template>
<style scoped>
.support-thread{display:flex;flex-direction:column;gap:20px}.thread-status{font-size:12px;color:#707780;background:#f5f2f7;border-radius:7px;padding:10px}.thread-log{display:flex;flex-direction:column;gap:20px;max-height:48dvh;overflow:auto;scroll-behavior:auto}.message{border:1px solid #e9edef;border-radius:10px;padding:12px;display:flex;flex-direction:column;gap:12px;overflow-wrap:anywhere}.message-meta{font-size:14px;font-weight:500}.message-bubble{white-space:pre-wrap}.message-bubble p{margin:0}.message small,.message time{color:#707780;font-size:12px}.message.system{color:#707780;font-size:12px;background:#f5f6f7}.thread-composer{display:flex;flex-direction:column;gap:12px}.composer-actions{display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap}.image-choice{font-size:12px;color:#736582}.image-choice input{display:block;max-width:180px}.send-button{width:100%;background:#d9e6c8!important;color:#2f4129!important}.thread-error{color:#9a3939}.file-choice{font-size:12px;overflow-wrap:anywhere}.thread-empty{color:#707780}
</style>
