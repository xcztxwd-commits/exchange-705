<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { can } from '@/utils/access'
import { useAccountTable } from '@/utils/useAccountTable'
import { accountTableRequest } from '@/utils/accountTableRequest'
import AccountTypeFilter from '@/components/AccountTypeFilter.vue'
const accountTable = useAccountTable(), accountModes = accountTable.modes
const request = accountTableRequest(accountTable)
import { requestId, supportDate } from '@/utils/support'
const recipients = ref(''),
  title = ref(''),
  content = ref(''),
  rows = ref<any[]>([]),
  page = ref(0),
  busy = ref(false),
  enabled = ref(false),
  error = ref('')
let retryId = '',
  signature = ''
async function load() {
  rows.value=[]
  try {
    const config: any = await request.get('/user/support/config')
    enabled.value = config.inboxEnabled
    const data: any = await request.get('/admin/support/inbox', { params: { page: page.value } })
    rows.value = data
    error.value = ''
  } catch (e: any) {
    error.value = e.message
  }
}
async function send() {
  if (busy.value) return
  const parts = recipients.value.trim().split(/[\s,，]+/)
  if (
    parts.some((value) => !/^\d+$/.test(value) || !Number.isSafeInteger(Number(value)) || Number(value) < 1)
  ) {
    ElMessage.warning('请输入有效用户 ID')
    return
  }
  const users = [...new Set(parts.map(Number))]
  if (!users.length || users.length > 200 || !title.value.trim() || !content.value.trim()) {
    ElMessage.warning('请填写标题、内容及 1–200 个用户 ID')
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
    recipients.value = ''
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
          ><el-form-item label="收件用户 ID"
            ><el-input
              v-model="recipients"
              type="textarea"
              :rows="2"
              placeholder="多个 ID 用逗号或换行分隔，最多 200 位" /></el-form-item
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
        ><AccountTypeFilter v-model="accountModes" @change="page=0;load()" /><admin-table :row-key="(row: any) => `${row.accountMode || 'REAL'}:${row.id ?? row.userId}:${row.type || ''}`" table-key="InboxManagement.1" :data="rows" style="width: 100%"
          ><el-table-column prop="accountModeLabel" label="账户类型" width="110" /><el-table-column prop="userId" label="收件用户" width="100" /><el-table-column
            prop="title"
            label="标题"
            min-width="150"
            show-overflow-tooltip
          /><el-table-column label="状态" width="80"
            ><template #default="{ row }"
              ><el-tag :type="row.readAt ? 'success' : 'info'" size="small">{{
                row.readAt ? '已读' : '未读'
              }}</el-tag></template
            ></el-table-column
          ><el-table-column label="发送时间" width="150"
            ><template #default="{ row }">{{ supportDate(row.createdAt) }}</template></el-table-column
          ><el-table-column type="expand"
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
