<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import InboxManagement from './InboxManagement.vue'
import { can } from '@/utils/access'
import ActivityManagement from '@/components/ActivityManagement.vue'
import { useAuthStore } from '@/store/auth'
const auth = useAuthStore()
const props = withDefaults(defineProps<{ initialTab?: string }>(), { initialTab: 'ordinary' })
const announcementTab = ref(props.initialTab)
watch(() => props.initialTab, value => { announcementTab.value = value })
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'

interface Announcement {
  id?: number
  title: string
  content: string
  status: string
  countdownSeconds: number
  priority: number
  language?: string
  displayAt?: string
  createdAt?: string
  updatedAt?: string
}

const loading = ref(false)
const announcements = ref<Announcement[]>([])
const dialogVisible = ref(false)
const dialogTitle = ref('新增公告')
const editingAnnouncement = ref<Announcement | null>(null)

// 支持的语言列表（与前端用户端完全一致，共20种语种）
const languageOptions = [
  { label: 'English (en)', value: 'en' },
  { label: '繁體中文 (zh-TW)', value: 'zh-TW' },
  { label: '日本語 (ja)', value: 'ja' },
  { label: '简体中文 (zh-CN)', value: 'zh-CN' },
  { label: 'Français (fr)', value: 'fr' },
  { label: 'Deutsch (de)', value: 'de' },
  { label: 'Русский (ru)', value: 'ru' },
  { label: 'Español (es)', value: 'es' },
  { label: 'Português (pt)', value: 'pt' },
  { label: 'Italiano (it)', value: 'it' },
  { label: 'العربية (ar)', value: 'ar' },
  { label: 'Türkçe (tr)', value: 'tr' },
  { label: 'Bahasa Indonesia (id)', value: 'id' },
  { label: 'မြန်မာ (my)', value: 'my' },
  { label: 'हिन्दी (hi)', value: 'hi' },
  { label: 'Čeština (cs)', value: 'cs' },
  { label: 'Polski (pl)', value: 'pl' },
  { label: '한국어 (ko)', value: 'ko' },
  { label: 'ไทย (th)', value: 'th' },
  { label: 'Tiếng Việt (vi)', value: 'vi' },
]

const formData = ref<Announcement>({
  title: '',
  content: '',
  status: 'PUBLISHED',
  priority: 0,
    countdownSeconds: 2,
  language: 'en',
})

const loadAnnouncements = async () => {
  loading.value = true
  try {
    const res: any = await request.get('/admin/announcement/list')
    announcements.value = res || []
  } catch (e: any) {
    ElMessage.error(e?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

const openDialog = (announcement?: Announcement) => {
  if (announcement) {
    editingAnnouncement.value = announcement
    dialogTitle.value = '编辑公告'
    formData.value = {
      displayAt: announcement.displayAt || announcement.createdAt,
      title: announcement.title,
      content: announcement.content,
      status: announcement.status,
      priority: announcement.priority,
      countdownSeconds: announcement.countdownSeconds ?? 2,
      language: announcement.language || 'en',
    }
  } else {
    editingAnnouncement.value = null
    dialogTitle.value = '新增公告'
    formData.value = {
      title: '',
      content: '',
      status: 'PUBLISHED',
      priority: 0,
    countdownSeconds: 2,
      language: 'en',
    }
  }
  dialogVisible.value = true
}

const closeDialog = () => {
  dialogVisible.value = false
  editingAnnouncement.value = null
  formData.value = {
    title: '',
    content: '',
    status: 'PUBLISHED',
    priority: 0,
    countdownSeconds: 2,
    language: 'en',
  }
}

const saveAnnouncement = async () => {
  if (!formData.value.title || !formData.value.title.trim()) {
    ElMessage.warning('请输入公告标题')
    return
  }
  if (!formData.value.content || !formData.value.content.trim()) {
    ElMessage.warning('请输入公告内容')
    return
  }

  if (!Number.isInteger(formData.value.countdownSeconds) || formData.value.countdownSeconds < 0 || formData.value.countdownSeconds > 2147483647) {
    ElMessage.warning('请输入有效的非负整数秒数')
    return
  }

  loading.value = true
  try {
    if (editingAnnouncement.value?.id) {
      // 更新
      await request.put(`/admin/announcement/${editingAnnouncement.value.id}`, {
        displayAt: formData.value.displayAt || undefined,
        title: formData.value.title.trim(),
        content: formData.value.content.trim(),
        status: formData.value.status,
        priority: formData.value.priority || 0,
        countdownSeconds: formData.value.countdownSeconds,
        language: formData.value.language || 'en',
      })
      ElMessage.success('更新成功')
    } else {
      // 新增
      await request.post('/admin/announcement/create', {
        displayAt: formData.value.displayAt || undefined,
        title: formData.value.title.trim(),
        content: formData.value.content.trim(),
        status: formData.value.status,
        priority: formData.value.priority || 0,
        countdownSeconds: formData.value.countdownSeconds,
        language: formData.value.language || 'en',
      })
      ElMessage.success('创建成功')
    }
    closeDialog()
    loadAnnouncements()
  } catch (e: any) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    loading.value = false
  }
}

const deleteAnnouncement = async (id: number) => {
  try {
    await ElMessageBox.confirm('确定要删除这条公告吗？', '确认删除', {
      type: 'warning',
    })
    loading.value = true
    try {
      await request.delete(`/admin/announcement/${id}`)
      ElMessage.success('删除成功')
      loadAnnouncements()
    } catch (e: any) {
      ElMessage.error(e?.message || '删除失败')
    } finally {
      loading.value = false
    }
  } catch {
    // 用户取消
  }
}

const getStatusLabel = (status: string) => {
  const statusMap: Record<string, string> = {
    PUBLISHED: '已发布',
    DRAFT: '草稿',
    HIDDEN: '已隐藏',
  }
  return statusMap[status] || status
}

const getStatusType = (status: string) => {
  const typeMap: Record<string, string> = {
    PUBLISHED: 'success',
    DRAFT: 'info',
    HIDDEN: 'warning',
  }
  return typeMap[status] || 'info'
}

const formatDate = (dateString?: string) => {
  if (!dateString) return '-'
  try {
    const date = new Date(dateString)
    return date.toLocaleString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    })
  } catch {
    return dateString
  }
}

onMounted(() => {
  if (can('announcement:view')) loadAnnouncements()
})
</script>

<template>
  <div class="announcement-management">
    <h1>消息与公告管理</h1>
    <el-radio-group v-model="announcementTab" style="margin-bottom:20px">
      <el-radio-button v-if="can('announcement:view')" value="ordinary">普通公告</el-radio-button>
      <el-radio-button v-if="can('inbox:view')" value="letters">个人站内信</el-radio-button>
      <el-radio-button v-if="can('announcement:view') && auth.user?.userType !== 'agent'" value="activity">活动公告 · 体验金</el-radio-button>
    </el-radio-group>
    <InboxManagement v-if="announcementTab === 'letters' && can('inbox:view')" />
    <ActivityManagement v-if="announcementTab === 'activity' && can('announcement:view') && auth.user?.userType !== 'agent'" />
    <el-card v-if="can('announcement:view')" v-show="announcementTab === 'ordinary'" shadow="never">
      <template #header>
        <div class="card-header">
          <span>公告管理</span>
          <el-button v-permission="'announcement:create'" type="primary" @click="openDialog()">新增公告</el-button>
        </div>
      </template>

      <admin-table table-key="AnnouncementManagement.1" v-loading="loading" :data="announcements" border stripe>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="content" label="内容" min-width="300" show-overflow-tooltip>
          <template #default="{ row }">
            <div style="max-height: 60px; overflow: hidden; text-overflow: ellipsis;">
              {{ row.content }}
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)">{{ getStatusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="language" label="语言" width="120">
          <template #default="{ row }">
            <el-tag>{{ row.language || 'en' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="countdownSeconds" label="不可点击倒计时（秒）" width="180" />
        <el-table-column prop="priority" label="优先级" width="100" />
        <el-table-column prop="displayAt" label="显示时间（UTC）" width="170">
          <template #default="{ row }">{{ formatDate(row.displayAt || row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间（审计）" width="170">
          <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column column-key="actions" label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'announcement:edit'" type="primary" size="small" @click="openDialog(row)">编辑</el-button>
            <el-button v-permission="'announcement:delete'" type="danger" size="small" @click="deleteAnnouncement(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </admin-table>
    </el-card>

    <!-- 编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="700px" @close="closeDialog">
      <el-form :model="formData" label-width="100px">
        <el-form-item label="标题" required>
          <el-input v-model="formData.title" placeholder="请输入公告标题" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input
            v-model="formData.content"
            type="textarea"
            :rows="8"
            placeholder="请输入公告内容"
            maxlength="5000"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="语言" required>
          <el-select v-model="formData.language" placeholder="请选择语言" style="width: 100%">
            <el-option
              v-for="lang in languageOptions"
              :key="lang.value"
              :label="lang.label"
              :value="lang.value"
            />
          </el-select>
          <div style="font-size: 12px; color: #999; margin-top: 4px">选择公告显示的语言</div>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="formData.status" style="width: 100%">
            <el-option label="已发布" value="PUBLISHED" />
            <el-option label="草稿" value="DRAFT" />
            <el-option label="已隐藏" value="HIDDEN" />
          </el-select>
        </el-form-item>
        <el-form-item label="显示时间">
          <el-date-picker v-model="formData.displayAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="默认创建时间（UTC）" :clearable="!editingAnnouncement" />
          <div style="font-size:12px;color:#777">用户看到的公告日期（UTC），不修改创建时间或弹窗倒计时。</div>
        </el-form-item>
        <el-form-item label="不可点击秒数">
          <el-input-number v-model="formData.countdownSeconds" :min="0" :max="2147483647" :precision="0" :step="1" step-strictly />
          <div style="font-size: 12px; color: #999; margin-top: 4px">0 秒可立即点击；倒计时结束后手动点击关闭</div>
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="formData.priority" :min="0" :max="999" style="width: 100%" />
          <div style="font-size: 12px; color: #999; margin-top: 4px">数字越大，显示优先级越高</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button v-permission="'session:close'" @click="closeDialog">取消</el-button>
        <el-button v-permission="editingAnnouncement ? 'announcement:edit' : 'announcement:create'" type="primary" :loading="loading" @click="saveAnnouncement">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.announcement-management {
  padding: 0;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>



