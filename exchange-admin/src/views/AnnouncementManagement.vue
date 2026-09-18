<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'

interface Announcement {
  id?: number
  title: string
  content: string
  status: string
  priority: number
  language?: string
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
  { label: '日本語 (ja)', value: 'ja' },
  { label: '한국어 (ko)', value: 'ko' },
  { label: 'ไทย (th)', value: 'th' },
  { label: 'Tiếng Việt (vi)', value: 'vi' },
]

const formData = ref<Announcement>({
  title: '',
  content: '',
  status: 'PUBLISHED',
  priority: 0,
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
      title: announcement.title,
      content: announcement.content,
      status: announcement.status,
      priority: announcement.priority,
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

  loading.value = true
  try {
    if (editingAnnouncement.value?.id) {
      // 更新
      await request.put(`/admin/announcement/${editingAnnouncement.value.id}`, {
        title: formData.value.title.trim(),
        content: formData.value.content.trim(),
        status: formData.value.status,
        priority: formData.value.priority || 0,
        language: formData.value.language || 'en',
      })
      ElMessage.success('更新成功')
    } else {
      // 新增
      await request.post('/admin/announcement/create', {
        title: formData.value.title.trim(),
        content: formData.value.content.trim(),
        status: formData.value.status,
        priority: formData.value.priority || 0,
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
  loadAnnouncements()
})
</script>

<template>
  <div class="announcement-management">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>公告管理</span>
          <el-button type="primary" @click="openDialog()">新增公告</el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="announcements" border stripe>
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
        <el-table-column prop="priority" label="优先级" width="100" />
        <el-table-column prop="createdAt" label="创建时间" width="160">
          <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" size="small" @click="openDialog(row)">编辑</el-button>
            <el-button type="danger" size="small" @click="deleteAnnouncement(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
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
        <el-form-item label="优先级">
          <el-input-number v-model="formData.priority" :min="0" :max="999" style="width: 100%" />
          <div style="font-size: 12px; color: #999; margin-top: 4px">数字越大，显示优先级越高</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeDialog">取消</el-button>
        <el-button type="primary" :loading="loading" @click="saveAnnouncement">保存</el-button>
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



