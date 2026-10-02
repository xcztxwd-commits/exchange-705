<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useLocaleStore } from '@/store/locale'
import { useAuthStore } from '@/store/auth'
import request from '@/utils/request'
import { supportText as t, supportDate } from '@/utils/support'
import { inboxState, refreshInbox } from '../utils/unifiedInbox'
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
  <main class="inbox-page">
    <header>
      <button @click="router.back()" :aria-label="t('返回', 'Back')">‹</button>
      <div><h1>{{ t('站内信', 'Inbox') }}</h1><p>{{ t('公告、个人信和活动消息', 'Announcements, personal messages and activities') }}</p></div>
      <button class="read-all" :disabled="busy || marking || !inboxState.unread" @click="readAll">{{ t('全部已读', 'Mark all read') }}</button>
    </header>
    <div class="inbox-tools"><span>{{ t('未读', 'Unread') }}: {{ inboxState.unread }}</span><span id="inbox-sound"></span></div>
    <p v-if="error" role="alert" class="inbox-error">{{ error }}</p>
    <div class="inbox-grid" :class="{ reading: selected }">
      <aside>
        <button v-for="letter in letters" :key="letter.id" class="letter" :data-message-id="letter.id" :class="{ active: selected?.id === letter.id, unread: !letter.readAt }" @click="open(letter)">
          <small class="message-type">{{ typeLabel(letter.type) }}</small><span>{{ letter.title }}</span>
          <time>{{ supportDate(letter.displayAt) }}</time><p>{{ letter.content }}</p>
        </button>
        <p v-if="!letters.length" class="inbox-empty">{{ busy ? t('加载中…', 'Loading…') : t('暂无消息', 'No messages yet') }}</p>
        <div class="pages"><button :disabled="page === 0 || busy" :aria-label="t('上一页', 'Previous')" @click="changePage(-1)">‹</button><span>{{ page + 1 }} / {{ Math.max(pages, 1) }}</span><button :disabled="page + 1 >= pages || busy" :aria-label="t('下一页', 'Next')" @click="changePage(1)">›</button></div>
      </aside>
      <article v-if="selected">
        <button class="detail-back" @click="clearDetail">{{ t('返回列表', 'Back to list') }}</button>
        <small class="message-type">{{ typeLabel(selected.type) }}</small><h2>{{ selected.title }}</h2><time>{{ supportDate(selected.displayAt) }}</time><p>{{ selected.content }}</p>
        <button v-if="selected.type === 'ACTIVITY'" @click="openActivity">{{ t('查看活动', 'View activity') }}</button>
      </article>
      <article v-else class="inbox-empty">{{ t('选择一封站内信查看详情', 'Select a message to read') }}</article>
    </div>
  </main>
</template>
<style scoped>
.inbox-tools{display:flex;justify-content:flex-end;align-items:center;gap:12px;padding:12px 24px;flex-wrap:wrap;font-size:12px}
.inbox-page .message-type{display:block;color:#718050;font-size:11px;margin-bottom:6px}
.inbox-page .detail-back{display:none}
.inbox-page header>div{min-width:0}
@media(max-width:600px){.inbox-grid.reading aside{display:none}.inbox-page .detail-back{display:inline-block}.inbox-page header{flex-wrap:wrap}.inbox-page .read-all{margin-left:0}.inbox-grid article{min-width:0}}

.inbox-page {
  max-width: 1000px;
  min-height: 100dvh;
  margin: auto;
  background: #fff;
  color: #273247;
}
.inbox-page header {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 25px;
  border-bottom: 1px solid #eef0f3;
}
.inbox-page button {
  cursor: pointer;
  border: 1px solid #e5eade;
  border-radius: 8px;
  background: white;
  color: #647644;
  padding: 8px 13px;
  font: inherit;
}
.inbox-page button:disabled {
  opacity: 0.4;
}
.inbox-page h1 {
  font-size: 21px;
  margin: 0;
}
.inbox-page header p {
  font-size: 12px;
  color: #8c94a1;
  margin: 5px 0 0;
}
.inbox-page .read-all {
  margin-left: auto;
  font-size: 12px;
}
.inbox-grid {
  display: grid;
  grid-template-columns: 340px 1fr;
  min-height: 65vh;
}
.inbox-grid aside {
  padding: 14px;
  border-right: 1px solid #eef0f3;
}
.inbox-page .letter {
  display: block;
  text-align: left;
  width: 100%;
  border: 0;
  padding: 17px;
  color: #344055;
  margin-bottom: 4px;
}
.letter.active {
  background: #f1f6ea;
}
.letter.unread > span {
  font-weight: 700;
}
.letter.unread > span:before {
  content: '';
  display: inline-block;
  width: 6px;
  height: 6px;
  background: #78a538;
  border-radius: 50%;
  margin-right: 7px;
}
.letter span {
  font-size: 14px;
}
.letter time {
  display: block;
  font-size: 10px;
  color: #98a1af;
  margin: 7px 0;
}
.letter p {
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: #8a94a4;
  margin: 0;
}
.inbox-grid article {
  padding: 40px;
  overflow-wrap: anywhere;
}
.inbox-grid article > small {
  color: #79a338;
  font-size: 11px;
}
.inbox-grid article h2 {
  font-size: 22px;
  font-weight: 600;
}
.inbox-grid article time {
  font-size: 11px;
  color: #9ba4af;
}
.inbox-grid article p {
  white-space: pre-wrap;
  line-height: 1.9;
  font-size: 14px;
  margin-top: 30px;
}
.inbox-empty {
  text-align: center;
  color: #9aa3b0;
  font-size: 13px;
  padding: 60px 15px;
}
.pages {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 18px;
  margin-top: 20px;
  font-size: 12px;
}
.inbox-error {
  background: #fff2f0;
  color: #a94434;
  padding: 12px;
}
@media (max-width: 600px) {
  .inbox-grid {
    grid-template-columns: 1fr;
  }
  .inbox-grid aside {
    border: 0;
    border-bottom: 1px solid #eee;
  }
  .inbox-grid article {
    padding: 25px;
  }
  .inbox-page header {
    padding: 18px;
    gap: 12px;
  }
  .inbox-page header p {
    max-width: 190px;
  }
  .inbox-page .read-all {
    font-size: 10px;
  }
}
</style>
