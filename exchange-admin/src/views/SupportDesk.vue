<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { access, can } from '@/utils/access'
import { useAuthStore } from '@/store/auth'
import request from '@/utils/request'
import { supportDate, type Conversation } from '@/utils/support'
import SupportThread from '@/components/SupportThread.vue'
const auth = useAuthStore(),
  scope = ref('mine'),
  rows = ref<Conversation[]>([]),
  selected = ref<Conversation>(),
  accepting = ref(false)
const page = ref(0),
  busy = ref(false),
  error = ref(''),
  agents = ref<any[]>([]),
  target = ref<number>(),
  config = ref<any>()
const owned = computed(() => selected.value?.adminId === Number(auth.user?.id))
const active = computed(() => selected.value?.status === 'ACTIVE')
let timer: ReturnType<typeof setTimeout> | undefined,
  disposed = false,
  pending = false
async function load() {
  if (pending || disposed) return
  pending = true
  try {
    const data: any = await request.get('/admin/support/sessions', {
      params: { scope: scope.value, page: page.value },
    })
    const state: any = await request.get('/user/support/config')
    if (disposed) return
    rows.value = data
    config.value = state
    if (accepting.value && can('support:claim'))
      await request.post('/admin/support/presence', { accepting: true })
    if (can('support:transfer')) {
      const online: any = await request.get('/admin/support/agents')
      agents.value = online.filter((a: any) => a.id !== selected.value?.adminId)
    }
    error.value = ''
  } catch (e: any) {
    error.value = e.message
  } finally {
    pending = false
    if (!disposed) {
      clearTimeout(timer)
      timer = setTimeout(load, 5000)
    }
  }
}
async function presence() {
  try {
    await request.post('/admin/support/presence', { accepting: !accepting.value })
    accepting.value = !accepting.value
    await load()
  } catch (e: any) {
    ElMessage.error(e.message)
  }
}
async function claim(row: Conversation) {
  if (busy.value) return
  busy.value = true
  try {
    const result: any = await request.post(`/admin/support/sessions/${row.id}/claim`)
    scope.value = 'mine'
    await nextTick()
    selected.value = result
    await load()
  } catch (e: any) {
    ElMessage.error(e.message)
    await load()
  } finally {
    busy.value = false
  }
}
async function close() {
  if (!selected.value) return
  try {
    await ElMessageBox.confirm('结束会话后不能继续回复，聊天记录将保留。', '结束会话', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
    })
    await request.post(`/admin/support/sessions/${selected.value.id}/close`)
    selected.value = { ...selected.value, status: 'CLOSED' }
    await load()
  } catch (e: any) {
    if (e instanceof Error) ElMessage.error(e.message)
  }
}
async function transfer() {
  if (!selected.value || !target.value) return
  try {
    await request.post(`/admin/support/sessions/${selected.value.id}/transfer`, { target: target.value })
    selected.value = undefined
    target.value = undefined
    await load()
    ElMessage.success('转接成功')
  } catch (e: any) {
    ElMessage.error(e.message)
  }
}
async function exportChat() {
  if (!selected.value) return
  try {
    const data: any = await request.get(`/admin/support/sessions/${selected.value.id}/export`)
    const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }))
    const a = document.createElement('a')
    a.href = url
    a.download = `conversation-${selected.value.id}.json`
    a.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (e: any) {
    ElMessage.error(e.message)
  }
}
watch(scope, () => {
  page.value = 0
  selected.value = undefined
  void load()
})
onMounted(load)
onUnmounted(() => {
  disposed = true
  clearTimeout(timer)
  if (accepting.value) void request.post('/admin/support/presence', { accepting: false }).catch(() => {})
})
const status = (row: Conversation) => ({ WAITING: '排队中', ACTIVE: '接待中', CLOSED: '已结束' })[row.status]
function changePage(delta: number) {
  page.value += delta
  void load()
}
</script>
<template>
  <section class="desk">
    <header class="desk-header">
      <div>
        <span class="eyebrow">CUSTOMER CARE</span>
        <h1>客服工作台</h1>
        <p>专注每一次沟通，让服务井然有序。</p>
      </div>
      <div class="header-actions">
        <span :class="['presence', { online: accepting }]">{{ accepting ? '在线接待' : '离线' }}</span
        ><el-button
          v-permission="'support:claim'"
          v-if="can('support:claim')"
          :type="accepting ? 'default' : 'primary'"
          @click="presence"
          >{{ accepting ? '暂停接待' : '开始接待' }}</el-button
        >
      </div>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert
      v-if="config && config.mode !== 'internal'"
      title="站内客服未开启。可查看记录，不能接入或回复新消息。"
      type="warning"
      :closable="false"
    />
    <div class="desk-grid">
      <aside class="session-list">
        <div class="scope-tabs">
          <button v-permission="'support:view'" :class="{ chosen: scope === 'mine' }" @click="scope = 'mine'">
            我的会话</button
          ><button
            v-permission="'support:claim'"
            v-if="can('support:claim')"
            :class="{ chosen: scope === 'queue' }"
            @click="scope = 'queue'"
          >
            等待接待</button
          ><button
            v-permission="'support:audit'"
            v-if="access.superAdmin && can('support:audit')"
            :class="{ chosen: scope === 'all' }"
            @click="scope = 'all'"
          >
            监督全部
          </button>
        </div>
        <div class="list-caption">
          {{ scope === 'queue' ? '按进入顺序排列，点击接待' : '会话记录 · 最近更新'
          }}<button v-permission="'support:view'" @click="load" aria-label="刷新">↻</button>
        </div>
        <div
          v-for="row in rows"
          :key="row.id"
          class="session-card"
          :class="{ selected: selected?.id === row.id }"
        >
          <button
            v-permission="'support:detail'"
            class="session-main"
            :disabled="scope === 'queue'"
            @click="selected = row"
          >
            <span class="avatar">{{ String(row.userId).slice(-2) }}</span
            ><span class="session-info"
              ><strong>客户 {{ row.userId }}</strong
              ><small>#{{ row.id }} · {{ supportDate(row.updatedAt) }}</small></span
            ><span class="status" :class="row.status.toLowerCase()">{{ status(row) }}</span>
          </button>
          <div class="queue-meta" v-if="scope === 'queue'">
            <span>{{ row.clientIp }}</span
            ><el-button
              v-permission="'support:claim'"
              size="small"
              :disabled="!accepting || busy || config?.mode !== 'internal'"
              @click="claim(row)"
              >接待</el-button
            >
          </div>
        </div>
        <p v-if="!rows.length" class="empty-list">
          {{ scope === 'queue' ? '暂时没有排队客户' : '暂无会话' }}
        </p>
        <div class="pages">
          <el-button v-permission="'support:view'" size="small" :disabled="page === 0" @click="changePage(-1)"
            >上一页</el-button
          ><span>{{ page + 1 }}</span
          ><el-button
            v-permission="'support:view'"
            size="small"
            :disabled="rows.length < 30"
            @click="changePage(1)"
            >下一页</el-button
          >
        </div>
      </aside>
      <div class="chat-panel">
        <template v-if="selected"
          ><div class="chat-heading">
            <div>
              <strong>客户 {{ selected.userId }}</strong
              ><small
                >会话 #{{ selected.id }} · {{ selected.clientIp }} · 客服
                {{ selected.adminId || '待分配' }}</small
              >
            </div>
            <el-button
              v-permission="'support:close'"
              v-if="can('support:close') && selected.status !== 'CLOSED'"
              size="small"
              @click="close"
              >结束</el-button
            ><el-button
              v-permission="'support:export'"
              v-if="access.superAdmin && can('support:export')"
              size="small"
              @click="exportChat"
              >导出证据</el-button
            >
          </div>
          <div v-if="active && can('support:transfer')" class="transfer-row">
            <el-select v-model="target" placeholder="转接给在线客服" size="small"
              ><el-option
                v-for="a in agents"
                :key="a.id"
                :label="`${a.name} #${a.id}`"
                :value="a.id" /></el-select
            ><el-button v-permission="'support:transfer'" size="small" :disabled="!target" @click="transfer"
              >转接</el-button
            ><small v-if="!owned">监督模式 · 只读</small>
          </div>
          <SupportThread
            :id="selected.id"
            admin
            :can-reply="owned && can('support:reply')"
            :can-image="can('support:image')"
            :disabled="config?.mode !== 'internal'"
            @updated="selected = $event"
        /></template>
        <div v-else class="desk-empty">
          <div>◌</div>
          <h2>开始一段有温度的对话</h2>
          <p>从左侧选择会话，或上线接待排队客户。</p>
          <small>聊天记录只追加、不覆盖；跨客服监督仅限超级管理员。</small>
        </div>
      </div>
    </div>
  </section>
</template>
<style scoped>
.desk {
  color: #263449;
}
.desk-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 25px;
}
.eyebrow {
  font-size: 10px;
  letter-spacing: 2px;
  color: #8494ad;
}
.desk h1 {
  font-size: 26px;
  font-weight: 600;
  margin: 8px 0;
}
.desk-header p {
  font-size: 12px;
  color: #94a0b2;
  margin: 0;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 16px;
}
.presence {
  font-size: 12px;
  color: #94a0b2;
}
.presence:before {
  content: '';
  width: 7px;
  height: 7px;
  background: #b8c1cc;
  display: inline-block;
  border-radius: 50%;
  margin-right: 6px;
}
.presence.online:before {
  background: #64b485;
}
.desk-grid {
  display: grid;
  grid-template-columns: 320px 1fr;
  margin-top: 18px;
  border: 1px solid #e8edf3;
  border-radius: 16px;
  overflow: hidden;
  background: white;
  height: calc(100vh - 225px);
  min-height: 530px;
}
.session-list {
  border-right: 1px solid #edf0f5;
  overflow: auto;
}
.scope-tabs {
  display: flex;
  padding: 18px 12px 12px;
  gap: 5px;
}
.scope-tabs button {
  padding: 8px 10px;
  border: 0;
  background: transparent;
  border-radius: 7px;
  color: #8490a3;
  font-size: 12px;
  cursor: pointer;
}
.scope-tabs .chosen {
  color: #53739d;
  background: #eef3f9;
  font-weight: 600;
}
.list-caption {
  padding: 4px 20px 16px;
  color: #a4adba;
  font-size: 10px;
  display: flex;
  justify-content: space-between;
}
.list-caption button {
  background: none;
  border: 0;
  color: #8290a4;
  cursor: pointer;
}
.session-card {
  padding: 14px;
  border-bottom: 1px solid #f3f5f8;
}
.session-card.selected {
  background: #f6f9fc;
}
.session-main {
  display: flex;
  align-items: center;
  text-align: left;
  gap: 10px;
  border: 0;
  background: none;
  width: 100%;
  cursor: pointer;
  padding: 0;
  color: inherit;
}
.session-main:disabled {
  cursor: default;
}
.avatar {
  display: grid;
  place-items: center;
  flex-shrink: 0;
  width: 37px;
  height: 37px;
  background: #edf2f8;
  border-radius: 12px;
  color: #7c93af;
  font-size: 12px;
}
.session-info {
  flex: 1;
  min-width: 0;
}
.session-info strong {
  font-size: 12px;
  font-weight: 600;
}
.session-info small {
  display: block;
  font-size: 10px;
  color: #a0abba;
  margin-top: 6px;
}
.status {
  font-size: 10px;
  white-space: nowrap;
  color: #a0adbd;
}
.status.active {
  color: #609d7b;
}
.status.waiting {
  color: #c09b52;
}
.queue-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 10px 0 0 47px;
  font-size: 10px;
  color: #9ba7b7;
}
.chat-panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}
.chat-panel :deep(.support-thread) {
  flex: 1;
  min-height: 0;
}
.chat-heading {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 19px 22px;
  border-bottom: 1px solid #edf0f5;
}
.chat-heading > div {
  flex: 1;
}
.chat-heading strong {
  font-size: 14px;
}
.chat-heading small {
  display: block;
  margin-top: 6px;
  font-size: 10px;
  color: #9ca7b7;
}
.transfer-row {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 8px 22px;
  background: #fafbfd;
}
.transfer-row .el-select {
  width: 190px;
}
.transfer-row small {
  margin-left: auto;
  color: #97a2b2;
  font-size: 10px;
}
.desk-empty {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 30px;
}
.desk-empty > div {
  font-size: 82px;
  color: #b8c9dc;
}
.desk-empty h2 {
  font-size: 19px;
  font-weight: 500;
  color: #647890;
  margin: 20px 0 10px;
}
.desk-empty p {
  font-size: 12px;
  color: #9ca9b9;
}
.desk-empty small {
  font-size: 10px;
  color: #b2bac7;
  margin-top: 35px;
}
.empty-list {
  text-align: center;
  font-size: 12px;
  color: #a4afbd;
  padding: 40px 10px;
}
.pages {
  display: flex;
  justify-content: center;
  gap: 12px;
  align-items: center;
  padding: 16px;
  font-size: 12px;
  color: #9aa6b6;
}
@media (max-width: 1000px) {
  .desk-grid {
    grid-template-columns: 260px 1fr;
  }
  .chat-heading {
    flex-wrap: wrap;
  }
}
@media (max-width: 700px) {
  .desk-grid {
    display: flex;
    flex-direction: column;
    height: auto;
  }
  .session-list {
    max-height: 280px;
  }
  .chat-panel {
    height: 650px;
  }
  .desk-header {
    align-items: flex-start;
  }
  .header-actions {
    flex-direction: column;
    gap: 8px;
  }
}
</style>
