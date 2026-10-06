<script setup lang="ts">
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { can } from '@/utils/access'
import { useAccountTable } from '@/utils/useAccountTable'
import { accountTableRequest } from '@/utils/accountTableRequest'
import AccountTypeFilter from '@/components/AccountTypeFilter.vue'
const accountTable = useAccountTable(), accountModes = accountTable.modes
const request = accountTableRequest(accountTable)
import { requestId, supportDate } from '@/utils/support'
type Recipient = { id: number; email: string }
const recipients = ref<Recipient[]>([]),
  title = ref(''),
  content = ref(''),
  rows = ref<any[]>([]),
  page = ref(0),
  busy = ref(false),
  enabled = ref(false),
  error = ref('')
const historyEmail = ref('')
function searchHistory() { page.value = 0; void load() }
const recipientQuery = ref(''), matches = ref<Recipient[]>([]), searching = ref(false), searchError = ref('')
let searchTimer: ReturnType<typeof setTimeout> | undefined, searchVersion = 0
watch(recipientQuery, (value) => {
  clearTimeout(searchTimer)
  const version = ++searchVersion, query = value.trim()
  matches.value = []
  searchError.value = ''
  searching.value = !!query
  if (!query) return
  searchTimer = setTimeout(async () => {
    try {
      const data: Recipient[] = await request.get('/admin/support/inbox/recipients', { params: { query } })
      if (version === searchVersion) matches.value = data
    } catch (e: any) {
      if (version === searchVersion) searchError.value = e.message || '搜索失败，请重新输入重试'
    } finally {
      if (version === searchVersion) searching.value = false
    }
  }, 300)
}, { flush: 'sync' })
function addRecipient(user: Recipient) {
  if (busy.value || !enabled.value || recipients.value.some(item => item.id === user.id)) return
  if (recipients.value.length >= 200) { ElMessage.warning('最多添加 200 位收件人'); return }
  recipients.value.push(user)
}
function removeRecipient(id: number) {
  if (!busy.value && enabled.value) recipients.value = recipients.value.filter(user => user.id !== id)
}
onBeforeUnmount(() => { clearTimeout(searchTimer); ++searchVersion })
let retryId = '',
  signature = ''
async function load() {
  rows.value=[]
  try {
    const config: any = await request.get('/user/support/config')
    enabled.value = config.inboxEnabled
    const data: any = await request.get('/admin/support/inbox', { params: { page: page.value, userEmail: historyEmail.value.trim() || undefined } })
    rows.value = data
    error.value = ''
  } catch (e: any) {
    error.value = e.message
  }
}
async function send() {
  if (busy.value) return
  const users = recipients.value.map(user => user.id)
  if (!users.length || users.length > 200 || !title.value.trim() || !content.value.trim()) {
    ElMessage.warning('请填写标题、内容并选择 1–200 位收件人')
    return
  }
  const body = { users, title: title.value, content: content.value }
  const value = JSON.stringify(body)
  if (signature !== value) {
    signature = value
    retryId = requestId()
  }
  try {
    await ElMessageBox.confirm(`即将向 ${users.length} 位用户发送站内信，发送后不可撤回。`, '确认发送', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  busy.value = true
  try {
    await request.post('/admin/support/inbox', { ...body, requestId: retryId })
    ElMessage.success('站内信已发送')
    title.value = ''
    content.value = ''
    recipients.value = []
    recipientQuery.value = ''
    signature = ''
    retryId = ''
    page.value = 0
    await load()
  } catch (e: any) {
    ElMessage.error(e.message)
  } finally {
    busy.value = false
  }
}
onMounted(load)
function changePage(delta: number) {
  page.value += delta
  void load()
}
</script>
<template>
  <section class="inbox-admin">
    <header>
      <small>MESSAGE CENTER</small>
      <h1>站内信管理</h1>
      <p>定向通知客户，发送与已读状态一目了然。</p>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" /><el-alert
      v-if="!enabled"
      title="站内信已关闭，请在「客服与消息设置」开启。历史发送记录仍保留。"
      type="warning"
      :closable="false"
    />
    <div class="inbox-columns">
      <el-card v-if="can('inbox:send')" shadow="never" class="compose-card"
        ><template #header><strong>发送新消息</strong></template
        ><el-form label-position="top" :disabled="!enabled || busy" @submit.prevent="send"
          ><el-form-item label="收件用户"
            ><el-input v-model="recipientQuery" clearable maxlength="128" aria-label="搜索收件用户 ID 或邮箱"
              placeholder="输入用户 ID 或邮箱实时搜索" @keydown.enter.prevent />
            <div class="recipient-status" role="status" aria-live="polite">
              <span v-if="searching">搜索中…</span>
              <span v-else-if="searchError">{{ searchError }}</span>
              <span v-else-if="recipientQuery.trim() && !matches.length">未找到匹配用户</span>
              <span v-else-if="matches.length">最多显示 20 位，输入更完整的邮箱可缩小范围</span>
            </div>
            <ul v-if="matches.length" class="recipient-results" aria-label="搜索结果">
              <li v-for="user in matches" :key="user.id">
                <span class="recipient-identity">ID {{ user.id }}<small>{{ user.email || '未设置邮箱' }}</small></span>
                <el-button v-permission="'inbox:send'" size="small" :disabled="recipients.length >= 200 || recipients.some(item => item.id === user.id)"
                  :aria-label="`添加用户 ${user.id}`" @click="addRecipient(user)">
                  {{ recipients.some(item => item.id === user.id) ? '已添加' : '添加' }}
                </el-button>
              </li>
            </ul>
            <div class="recipient-selected" aria-label="已选收件人">
              <span class="hint">已选 {{ recipients.length }} / 200 位，可继续搜索添加；× 仅移除收件人。</span>
              <el-tag v-for="user in recipients" :key="user.id" :closable="enabled && !busy"
                @close="removeRecipient(user.id)">ID {{ user.id }} · {{ user.email || '未设置邮箱' }}</el-tag>
            </div>
          </el-form-item
          ><el-form-item label="标题"
            ><el-input v-model="title" maxlength="120" show-word-limit /></el-form-item
          ><el-form-item label="正文"
            ><el-input v-model="content" type="textarea" :rows="8" maxlength="4000" show-word-limit
          /></el-form-item>
          <p class="hint">仅发送纯文本，不执行 HTML。收件人校验失败将整批取消；失败重试不会重复投递。</p>
          <el-button v-permission="'inbox:send'" type="primary" native-type="submit" :loading="busy"
             :disabled="accountModes.includes('DEMO') || !accountModes.length">发送站内信</el-button
          ></el-form
        ></el-card
      >
      <el-card shadow="never" class="history-card"
        ><template #header
          ><div class="history-heading">
            <strong>发送记录</strong
            ><el-button v-permission="'inbox:view'" size="small" @click="load">刷新</el-button>
          </div></template
        ><el-form inline @submit.prevent="searchHistory"><el-form-item label="用户邮箱"><el-input v-model="historyEmail" clearable maxlength="254" placeholder="用户邮箱" @clear="searchHistory" /></el-form-item><el-button v-permission="'inbox:view'" native-type="submit">搜索</el-button></el-form><AccountTypeFilter v-model="accountModes" @change="page=0;load()" /><admin-table :row-key="(row: any) => `${row.accountMode || 'REAL'}:${row.id ?? row.userId}:${row.type || ''}`" table-key="InboxManagement.1" :data="rows" style="width: 100%"
          ><el-table-column prop="accountModeLabel" label="账户类型" width="110" /><el-table-column prop="userId" label="收件用户" width="100" /><el-table-column prop="userEmail" label="用户邮箱" min-width="200" show-overflow-tooltip /><el-table-column prop="userRemark" label="用户备注" min-width="150" show-overflow-tooltip><template #default="{ row }">{{ row.userRemark || '-' }}</template></el-table-column><el-table-column
            prop="title"
            label="标题"
            min-width="150"
            show-overflow-tooltip
          /><el-table-column column-key="status" label="状态" width="80"
            ><template #default="{ row }"
              ><el-tag :type="row.readAt ? 'success' : 'info'" size="small">{{
                row.readAt ? '已读' : '未读'
              }}</el-tag></template
            ></el-table-column
          ><el-table-column column-key="sentTime" label="发送时间" width="150"
            ><template #default="{ row }">{{ supportDate(row.createdAt) }}</template></el-table-column
          ><el-table-column column-key="expand" type="expand"
            ><template #default="{ row }"
              ><div class="letter-detail">
                <p>{{ row.content }}</p>
                <small
                  >发送人 #{{ row.adminId }} ·
                  {{ row.readAt ? `阅读时间 ${supportDate(row.readAt)}` : '尚未阅读' }}</small
                >
              </div></template
            ></el-table-column
          ></admin-table
        >
        <div class="pages">
          <el-button v-permission="'inbox:view'" :disabled="page === 0" @click="changePage(-1)"
            >上一页</el-button
          ><span>{{ page + 1 }}</span
          ><el-button v-permission="'inbox:view'" :disabled="rows.length < 30" @click="changePage(1)"
            >下一页</el-button
          >
        </div></el-card
      >
    </div>
  </section>
</template>
<style scoped>
.inbox-admin header small {
  font-size: 10px;
  color: #91a0b4;
  letter-spacing: 2px;
}
.inbox-admin h1 {
  font-size: 25px;
  color: #29374b;
  font-weight: 600;
  margin: 10px 0;
}
.inbox-admin header p {
  font-size: 12px;
  color: #95a0b0;
  margin-bottom: 25px;
}
.inbox-columns {
  display: flex;
  gap: 20px;
  margin-top: 20px;
  align-items: flex-start;
}
.compose-card {
  width: 350px;
  flex-shrink: 0;
}
.history-card {
  flex: 1;
  min-width: 0;
}
.inbox-columns :deep(.el-card) {
  border-radius: 14px;
  border-color: #e7edf4;
}
.inbox-columns strong {
  font-size: 14px;
  color: #536b87;
}
.hint {
  color: #99a4b3;
  font-size: 11px;
  line-height: 1.8;
}
.recipient-status { width: 100%; color: #8391a4; font-size: 12px; line-height: 1.6; margin-top: 6px; }
.recipient-results { width: 100%; max-height: 240px; overflow-y: auto; padding: 0; margin: 6px 0; list-style: none; }
.recipient-results li { display: flex; align-items: center; gap: 10px; padding: 8px 0; border-bottom: 1px solid #e7edf4; }
.recipient-identity { flex: 1; min-width: 0; line-height: 1.5; overflow-wrap: anywhere; }
.recipient-identity small { display: block; color: #8391a4; }
.recipient-selected { display: flex; flex-wrap: wrap; gap: 6px; width: 100%; max-height: 220px; overflow-y: auto; }
.recipient-selected .hint { width: 100%; }
.recipient-selected :deep(.el-tag) { max-width: 100%; height: auto; min-height: 24px; }
.recipient-selected :deep(.el-tag__content) { white-space: normal; overflow-wrap: anywhere; min-width: 0; }
.history-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.pages {
  display: flex;
  gap: 15px;
  align-items: center;
  justify-content: flex-end;
  margin-top: 20px;
  font-size: 12px;
  color: #8391a4;
}
.letter-detail {
  padding: 12px 25px;
  color: #67768b;
}
.letter-detail p {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 13px;
  line-height: 1.8;
}
.letter-detail small {
  font-size: 11px;
  color: #a0aabb;
}
@media (max-width: 1050px) {
  .inbox-columns {
    flex-direction: column;
  }
  .compose-card,
  .history-card {
    width: 100%;
    box-sizing: border-box;
  }
}
</style>
