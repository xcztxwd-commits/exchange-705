<script setup lang="ts">
import { computed, ref, watch, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import request from '@/utils/request'
import SupportThread from '@/components/SupportThread.vue'
import AppSelect from '@/components/AppSelect.vue'
import { supportText as t, supportDate, type Conversation, type SupportConfig } from '@/utils/support'
import { useLocaleStore } from '@/store/locale'
const localeStore = useLocaleStore()
const router = useRouter(),
  config = ref<SupportConfig>(),
  sessions = ref<Conversation[]>([]),
  current = ref<Conversation>()
const sessionOptions = computed(() => sessions.value.map(s => ({ value: s.id, label: `#${s.id} · ${supportDate(s.createdAt)} · ${s.status}` })))
const busy = ref(false),
  error = ref(''),
  historyPage = ref(0)
let timer: ReturnType<typeof setTimeout> | undefined,
  disposed = false
async function refreshConfig() {
  clearTimeout(timer)
  const locale = localeStore.locale
  try {
    const result: any = await request.get('/user/support/config', { params: { locale } })
    if (!disposed && locale === localeStore.locale) config.value = result
  } catch (e: any) {
    if (!disposed && locale === localeStore.locale) error.value = e.message
  } finally {
    if (!disposed && locale === localeStore.locale) timer = setTimeout(refreshConfig, 10000)
  }
}
async function history() {
  try {
    const rows: any = await request.get('/user/support/sessions', { params: { page: historyPage.value } })
    sessions.value = rows
    if (!current.value) current.value = rows[0]
  } catch (e: any) {
    error.value = e.message
  }
}
async function start() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const row: any = await request.post('/user/support/sessions', null, { params: { locale: localeStore.locale } })
    current.value = row
    historyPage.value = 0
    await history()
  } catch (e: any) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
async function close() {
  if (
    !current.value ||
    busy.value ||
    !window.confirm(
      t('确定结束当前会话？聊天记录将保留。', 'End this conversation? Your chat history will be retained.'),
    )
  )
    return
  busy.value = true
  try {
    await request.post(`/user/support/sessions/${current.value.id}/close`)
    current.value = { ...current.value, status: 'CLOSED' }
    await history()
  } catch (e: any) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
function updated(row: Conversation) {
  current.value = row
  const index = sessions.value.findIndex((s) => s.id === row.id)
  if (index >= 0) sessions.value[index] = row
}
watch(() => localeStore.locale, () => {
  if (config.value) config.value.offline = ''
  void refreshConfig()
})
onMounted(() => {
  void refreshConfig()
  void history()
})
onUnmounted(() => {
  disposed = true
  clearTimeout(timer)
})
function changePage(delta: number) {
  historyPage.value += delta
  void history()
}
</script>
<template>
  <main class="support-page">
    <header>
      <button class="back" :aria-label="t('返回', 'Back')" @click="router.back()">‹</button>
      <div>
        <h1>{{ t('在线客服', 'Customer support') }}</h1>
        <p>{{ t('专属协助 · 安心沟通', 'Personal assistance. A conversation you can trust.') }}</p>
      </div>
      <span class="header-mark">SUPPORT</span>
    </header>
    <p v-if="error" class="page-error" role="alert">{{ error }}</p>
    <div v-if="!config" class="welcome">{{ t('加载中…', 'Loading…') }}</div>
    <section v-else-if="config.mode === 'external'" class="external">
      <template v-if="config.link"
        ><a :href="config.link" target="_blank" rel="noopener noreferrer"
          >{{ t('在新窗口打开客服', 'Open support in a new window') }} ↗</a
        ><iframe
          :src="config.link"
          :title="t('在线客服', 'Customer support')"
          referrerpolicy="no-referrer"
          sandbox="allow-forms allow-scripts allow-same-origin allow-popups"
      /></template>
      <p v-else>
        {{ t('客服链接尚未配置，请稍后再试', 'Support is not configured yet. Please try again later.') }}
      </p>
    </section>
    <template v-else>
      <div v-if="config.mode === 'off'" class="mode-notice">
        {{
          t('客服暂时关闭，历史记录仍可查看', 'Support is currently closed. Your history is still available.')
        }}
      </div>
      <div class="session-toolbar">
        <AppSelect v-if="sessions.length" class="session-select" :model-value="current?.id ?? ''"
          :options="sessionOptions" :label="t('历史会话', 'Conversation history')"
          @update:model-value="current = sessions.find(s => s.id === Number($event))" />
        <div v-if="historyPage > 0 || sessions.length === 30" class="history-pages">
          <button :disabled="historyPage === 0" @click="changePage(-1)">‹</button
          ><button :disabled="sessions.length < 30" @click="changePage(1)">›</button>
        </div>
        <button v-if="current && current.status !== 'CLOSED'" :disabled="busy" @click="close">
          {{ t('结束会话', 'End chat') }}
        </button>
        <button
          v-else-if="config.mode === 'internal' && current"
          class="primary"
          :disabled="busy"
          @click="start"
        >
          {{ t('发起新会话', 'New conversation') }}
        </button>
      </div>
      <div v-if="current" class="conversation">
        <SupportThread
          :id="current.id"
          :offline="config.offline"
          :disabled="config.mode !== 'internal'"
          @updated="updated"
        />
      </div>
      <section v-else class="welcome">
        <div class="welcome-icon">◌</div>
        <h2>{{ t('有什么可以帮您？', 'How can we help?') }}</h2>
        <p>
          {{
            t(
              '发起会话后进入队列。消息和图片将安全保留，客服接入后即可为您解答。',
              'Start a conversation to join the queue. Messages and images are securely retained for your support history.',
            )
          }}
        </p>
        <button v-if="config.mode === 'internal'" class="primary" :disabled="busy" @click="start">
          {{ busy ? t('连接中…', 'Connecting…') : t('联系在线客服', 'Start a conversation') }}
        </button>
      </section>
    </template>
  </main>
</template>
<style scoped>
.support-page {
  max-width: 1000px;
  margin: 0 auto;
  height: calc(100dvh - 72px);
  min-height: 460px;
  display: flex;
  flex-direction: column;
  background: #fff;
  color: #273247;
  font-family: inherit;
}
.support-page header {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 22px 26px;
  border-bottom: 1px solid #edf0f3;
}
.support-page h1 {
  font-size: 20px;
  letter-spacing: -0.5px;
  margin: 0;
}
.support-page header p {
  font-size: 11px;
  color: #8992a1;
  margin: 5px 0 0;
}
.support-page button {
  font-family: inherit;
  cursor: pointer;
  border: 1px solid #e5e9df;
  background: white;
  color: #5e7149;
  border-radius: 8px;
  padding: 8px 12px;
}
.support-page button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.support-page .back {
  font-size: 32px;
  border: 0;
  padding: 0 12px 0 0;
}
.header-mark {
  margin-left: auto;
  font-size: 10px;
  letter-spacing: 2px;
  color: #a0ac95;
}
.session-toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 12px 22px;
  min-height: 30px;
}
.session-select { min-width:0; max-width:65%; font-size:12px; }
.session-toolbar > button {
  margin-left: auto;
  font-size: 12px;
}
.support-page .primary {
  background: #73a72d;
  color: #fff;
  border-color: #73a72d;
  padding: 12px 22px;
}
.conversation {
  flex: 1;
  min-height: 0;
}
.welcome {
  padding: 70px 32px;
  text-align: center;
  max-width: 460px;
  margin: auto;
}
.welcome-icon {
  display: grid;
  place-items: center;
  width: 72px;
  height: 72px;
  background: #f1f7e9;
  border-radius: 24px;
  margin: auto;
  color: #7d9f4a;
  font-size: 60px;
}
.welcome h2 {
  font-size: 24px;
  margin-top: 28px;
  font-weight: 600;
}
.welcome p {
  font-size: 14px;
  line-height: 1.9;
  color: #8b94a2;
  margin: 15px 0 30px;
}
.page-error,
.mode-notice {
  padding: 12px 22px;
  font-size: 13px;
  background: #fff7ea;
  color: #997744;
}
.external {
  padding: 20px;
  height: calc(100dvh - 130px);
}
.external a {
  display: block;
  color: #638b35;
  font-size: 12px;
  margin-bottom: 14px;
}
.external iframe {
  width: 100%;
  height: 94%;
  border: 0;
  border-radius: 12px;
}
.history-pages {
  display: flex;
}
@media (max-width: 600px) {
  .support-page header {
    padding: 17px;
  }
  .header-mark {
    display: none;
  }
  .session-toolbar {
    padding: 10px 14px;
  }
  .support-page {
    height: calc(100dvh - 56px);
    min-height: 400px;
  }
  .conversation {
    min-height: 0;
  }
  .welcome {
    padding: 60px 26px;
  }
}
</style>
