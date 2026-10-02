<script setup lang="ts">
import BusinessPage from '@/advanced/components/business/BusinessPage.vue'
const { request, error: advancedError, writing: advancedWriting, setTimeout } = useBusinessLifecycle()
import { computed, ref, watch, onMounted, onUnmounted } from 'vue'
import { useBusinessLifecycle } from '@/advanced/components/business/useBusinessLifecycle'
import SupportThread from '@/advanced/components/business/BusinessSupportThread.vue'
import AppSelect from '@/advanced/components/business/BusinessSelect.vue'
import { supportText as t, supportDate, type Conversation, type SupportConfig } from '@/utils/support'
import { useLocaleStore } from '@/store/locale'
const localeStore = useLocaleStore()
const config = ref<SupportConfig>(),
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
<BusinessPage :title="t('在线客服','Customer support')" :error="advancedError" :busy="advancedWriting">
<p class="notice">{{ t('客服连接状态按当前会话显示','Connection status follows the active conversation') }}</p><p v-if="error" class="page-error" role="alert">{{ error }}</p>
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
      <section v-else class="welcome card">
        
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
</BusinessPage>
</template>

<style scoped>

.page-content,.cards-list,.addresses-list,.records-list,.yield-list,.product-list,.orders-list{display:flex;flex-direction:column;gap:20px}
.form-group,.form-item,.form-section{display:flex;flex-direction:column;gap:8px}
.form-label,.detail-label,.info-label,.stat-label{font-size:12px;color:#707780}
.form-actions,.order-actions,.dialog-footer,.amount-input-wrapper,.password-input-wrapper,.code-input-wrapper{display:flex;gap:8px}.form-actions>*,.order-actions>*{flex:1}
.record-row,.info-item,.info-row,.detail-item,.yield-header{display:flex;justify-content:space-between;gap:8px;padding:12px 0;overflow-wrap:anywhere}
.submit-btn,.submit-button,.confirm-btn,.purchase-btn{background:#d9e6c8!important;color:#2f4129!important;min-height:48px}
.contract-wrapper,.contract-box{display:flex;flex-direction:column;gap:20px}.contract-section{display:flex;flex-direction:column;gap:12px}.section-title{font-size:17px;font-weight:500}.section-text,.section-note{overflow-wrap:anywhere}.signature-image img{max-width:100%}
.card-item,.address-item{position:relative;z-index:1;border:1px solid #e9edef;border-radius:10px;padding:12px;background:white;overflow-wrap:anywhere}.swipe-item-wrapper{position:relative;overflow:hidden}.delete-button-wrapper{position:absolute;right:0;top:0;height:100%;display:flex;align-items:center}.delete-button{color:#9a3939}
.upload-area,.upload-box{background:#f5f6f7;border:1px solid #e9edef;border-radius:10px;padding:16px;min-height:84px}.upload-area img,.upload-box img,.id-image img{max-width:100%;object-fit:contain}
.empty-state,.loading-state{padding:28px 0;color:#707780;text-align:center}.status-badge,.status-text{color:#736582}.signature-canvas{width:100%;height:180px;touch-action:none;background:#f5f6f7}.image-preview img{max-width:100%;max-height:180px;object-fit:contain}

.session-toolbar{display:flex;flex-wrap:wrap;align-items:center;gap:12px}.session-select{flex:1}.welcome{display:flex;flex-direction:column;gap:12px}.external{display:flex;flex-direction:column;gap:12px}.external iframe{width:100%;height:60dvh;border:1px solid #e9edef;border-radius:10px}.mode-notice{background:#f5f2f7;border-radius:7px;padding:10px;color:#707780}.page-error{color:#9a3939}
</style>
