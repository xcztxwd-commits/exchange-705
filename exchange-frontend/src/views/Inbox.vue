<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import request from '@/utils/request'
import { supportText as t, supportDate } from '@/utils/support'
function openActivities() {
  window.dispatchEvent(new Event('activity-inbox'))
}
const router = useRouter(),
  letters = ref<any[]>([]),
  selected = ref<any>(),
  page = ref(0),
  error = ref(''),
  busy = ref(false),
  enabled = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined,
  disposed = false
async function load() {
  if (busy.value || disposed) return
  busy.value = true
  try {
    const config: any = await request.get('/user/support/config')
    enabled.value = config.inboxEnabled
    if (enabled.value) {
      const data: any = await request.get('/user/support/inbox', { params: { page: page.value } })
      if (!disposed) letters.value = data
    } else {
      letters.value = []
      selected.value = undefined
    }
    error.value = ''
  } catch (e: any) {
    error.value = e.message
  } finally {
    busy.value = false
    if (!disposed) {
      clearTimeout(timer)
      timer = setTimeout(load, 10000)
    }
  }
}
async function open(letter: any) {
  selected.value = letter
  try {
    await request.post(`/user/support/inbox/${letter.id}/read`)
    letter.readAt ||= new Date().toISOString()
  } catch (e: any) {
    error.value = e.message
  }
}
async function readAll() {
  try {
    await request.post('/user/support/inbox/read-all')
    await load()
  } catch (e: any) {
    error.value = e.message
  }
}
onMounted(load)
onUnmounted(() => {
  disposed = true
  clearTimeout(timer)
})
function changePage(delta: number) {
  page.value += delta
  void load()
}
</script>
<template>
  <main class="inbox-page">
    <header>
      <button @click="router.back()" :aria-label="t('返回', 'Back')">‹</button>
      <div>
        <h1>{{ t('站内信', 'Inbox') }}</h1>
        <p>{{ t('重要消息，不再错过', 'Your important updates, all in one place.') }}</p>
      </div>
      <button v-if="enabled" class="read-all" @click="readAll">{{ t('全部已读', 'Mark all read') }}</button>
    </header>
    <div style="padding: 12px 24px; border-bottom: 1px solid #eef0f3">
      <button @click="openActivities">
        {{ t('活动公告与体验金', 'Activity rewards & trial credit') }} ›
      </button>
    </div>
    <p v-if="error" role="alert" class="inbox-error">{{ error }}</p>
    <div v-if="!enabled && !busy" class="inbox-empty">
      {{ t('站内信暂未开放', 'Inbox is currently disabled') }}
    </div>
    <div v-else class="inbox-grid">
      <aside>
        <button
          v-for="letter in letters"
          :key="letter.id"
          class="letter"
          :class="{ active: selected?.id === letter.id, unread: !letter.readAt }"
          @click="open(letter)"
        >
          <span>{{ letter.title }}</span
          ><time>{{ supportDate(letter.createdAt) }}</time>
          <p>{{ letter.content }}</p>
        </button>
        <p v-if="!letters.length" class="inbox-empty">
          {{ busy ? t('加载中…', 'Loading…') : t('暂无消息', 'No messages yet') }}
        </p>
        <div class="pages">
          <button :disabled="page === 0 || busy" @click="changePage(-1)">‹</button><span>{{ page + 1 }}</span
          ><button :disabled="letters.length < 30 || busy" @click="changePage(1)">›</button>
        </div>
      </aside>
      <article v-if="selected">
        <small>{{ t('平台消息', 'Platform message') }}</small>
        <h2>{{ selected.title }}</h2>
        <time>{{ supportDate(selected.createdAt) }}</time>
        <p>{{ selected.content }}</p>
      </article>
      <article v-else class="inbox-empty">
        {{ t('选择一封站内信查看详情', 'Select a message to read') }}
      </article>
    </div>
  </main>
</template>
<style scoped>
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
