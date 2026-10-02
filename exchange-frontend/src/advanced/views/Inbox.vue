<script setup lang="ts">
import BusinessPage from '@/advanced/components/business/BusinessPage.vue'
const { request, error: advancedError, writing: advancedWriting, setTimeout } = useBusinessLifecycle()
import { ref, watch, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useLocaleStore } from '@/store/locale'
import { useAuthStore } from '@/store/auth'
import { useBusinessLifecycle } from '@/advanced/components/business/useBusinessLifecycle'
import { supportText as t, supportDate } from '@/utils/support'
import { inboxState, refreshInbox } from '@/utils/unifiedInbox'
const router = useRouter(), route = useRoute(), locale = useLocaleStore(), auth = useAuthStore()
const letters = ref<any[]>([]), selected = ref<any>(), page = ref(0), pages = ref(0), error = ref(''), busy = ref(false), marking = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined, generation = 0, detailGeneration = 0, contextGeneration = 0, disposed = false
const params = () => ({ language: locale.locale })
function typeLabel(type: string) {
  return type === 'ANNOUNCEMENT' ? t('公告', 'Announcement') : type === 'ACTIVITY' ? t('活动公告', 'Activity') : t('个人信', 'Personal message')
}
async function load() {
  const run = ++generation
  clearTimeout(timer); busy.value = true
  try {
    const data: any = await request.get('/user/support/unified-inbox', { params: { ...params(), page: page.value } })
    if (disposed || run !== generation) return
    letters.value = data.content; pages.value = data.totalPages; inboxState.unread = data.unread; error.value = ''
  } catch (e: any) { if (run === generation && !disposed) error.value = e.message }
  finally { if (run === generation && !disposed) { busy.value = false; timer = setTimeout(load, 10000) } }
}
async function open(letter: any) {
  const run = ++detailGeneration, context = contextGeneration
  try {
    const data: any = await request.post(`/user/support/unified-inbox/${encodeURIComponent(letter.id)}/read`, {}, { params: params() })
    if (disposed || context !== contextGeneration) return
    letter.readAt = data.readAt; refreshInbox()
    if (run === detailGeneration) { selected.value = data; error.value = '' }
  } catch (e: any) { if (!disposed && context === contextGeneration && run === detailGeneration) error.value = e.message }
}
async function readAll() {
  if (busy.value || marking.value) return
  const context = contextGeneration
  marking.value = true
  try {
    await request.post('/user/support/unified-inbox/read-all', {}, { params: params() })
    if (disposed || context !== contextGeneration) return
    await load()
    if (!disposed && context === contextGeneration) refreshInbox()
  } catch (e: any) { if (!disposed && context === contextGeneration) error.value = e.message }
  finally { if (!disposed && context === contextGeneration) marking.value = false }
}
function clearDetail() { detailGeneration++; selected.value = undefined }
function changePage(delta: number) { page.value += delta; clearDetail(); void load() }
function openActivity() { void router.replace({ query: { ...route.query, activity: String(selected.value.activityId) } }) }
watch(() => [auth.token, locale.locale], () => { contextGeneration++; clearDetail(); letters.value=[]; error.value=''; marking.value=false; page.value=0; void load() }, { immediate: true })
onUnmounted(() => { disposed = true; generation++; clearTimeout(timer) })
</script>

<template>
<BusinessPage :title="t('站内信','Inbox')" :error="advancedError || error" :busy="advancedWriting || busy">
  <div class="inbox-tools"><h2>{{ t('消息中心','Message center') }}</h2><button class="text-button" :disabled="busy || marking" @click="readAll">{{ t('全部已读','Mark all read') }}</button></div>
  <p class="notice">{{ t('未读','Unread') }} {{ inboxState.unread }} · {{ t('公告与站内信使用同一入口','Announcements and messages in one inbox') }}</p>
  <template v-if="!selected">
    <button v-for="letter in letters" :key="letter.id" class="card letter" :data-message-id="letter.id" :class="{unread:!letter.readAt}" :disabled="advancedWriting" @click="open(letter)">
      <span class="row"><span>{{ typeLabel(letter.type) }}</span><time>{{ supportDate(letter.displayAt) }}</time></span>
      <h2>{{ letter.title }}</h2><p class="message-preview">{{ letter.content }}</p>
    </button>
    <p v-if="!letters.length" class="empty">{{ busy ? t('加载中…','Loading…') : advancedError || error ? t('消息暂不可用','Messages unavailable') : t('暂无消息','No messages yet') }}</p>
    <div class="pages"><button :disabled="page===0 || busy" :aria-label="t('上一页','Previous')" @click="changePage(-1)">‹</button><span>{{ page+1 }} / {{ Math.max(pages,1) }}</span><button :disabled="page+1>=pages || busy" :aria-label="t('下一页','Next')" @click="changePage(1)">›</button></div>
  </template>
  <article v-else class="card"><button class="text-button" @click="clearDetail">{{ t('返回列表','Back to list') }}</button><div class="row"><span>{{ typeLabel(selected.type) }}</span><time>{{ supportDate(selected.displayAt) }}</time></div><h2>{{ selected.title }}</h2><p class="message-body">{{ selected.content }}</p><button v-if="selected.type==='ACTIVITY'" @click="openActivity">{{ t('查看活动','View activity') }}</button></article>
  <button v-if="error || advancedError" @click="load">{{ t('重试','Retry') }}</button>
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

.inbox-tools,.pages{display:flex;justify-content:space-between;align-items:center;gap:12px}.letter{width:100%;text-align:left!important}.letter .row{width:100%}.letter h2{font-weight:500}.message-preview{color:#707780;display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden}.message-body{white-space:pre-wrap;overflow-wrap:anywhere}
</style>
