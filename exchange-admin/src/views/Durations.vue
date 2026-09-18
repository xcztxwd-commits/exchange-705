<template>
  <div class="durations-page">
    <div class="page-header">
      <h2>期限设置</h2>
      <el-button type="primary" :icon="Plus" @click="handleAdd">新增期限</el-button>
    </div>

    <el-table :data="durations" v-loading="loading" border>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="duration" label="时长（秒）" width="120" />
      <el-table-column prop="label" label="显示标签" width="120" />
      <el-table-column prop="profitRate" label="盈利比例" width="120">
        <template #default="{ row }">
          {{ (Number(row.profitRate || 0) * 100).toFixed(2) }}%
        </template>
      </el-table-column>
      <el-table-column prop="lossRate" label="亏损比例" width="120">
        <template #default="{ row }">
          {{ (Number(row.lossRate || 0) * 100).toFixed(2) }}%
        </template>
      </el-table-column>
      <el-table-column prop="minAmount" label="最低购买" width="120">
        <template #default="{ row }">
          {{ row.minAmount ? Number(row.minAmount).toFixed(2) : '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="maxAmount" label="最大购买" width="120">
        <template #default="{ row }">
          {{ row.maxAmount ? Number(row.maxAmount).toFixed(2) : '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="sortOrder" label="排序" width="100" />
      <el-table-column prop="enabled" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'">
            {{ row.enabled ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" size="small" :icon="Edit" @click="handleEdit(row)">编辑</el-button>
          <el-button type="danger" size="small" :icon="Delete" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增/编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="500px"
    >
      <el-form :model="formData" label-width="100px">
        <el-form-item label="时长（秒）" required>
          <el-input-number
            v-model="formData.duration"
            :min="1"
            :precision="0"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="显示标签" required>
          <el-input v-model="formData.label" placeholder="如：30s, 60s" />
        </el-form-item>
        <el-form-item label="盈亏比例（%）" required>
          <el-input-number
            v-model="formData.profitRatePercent"
            :min="0"
            :max="100"
            :precision="2"
            :step="0.01"
            style="width: 100%"
            placeholder="如：80 表示 80%"
          />
        </el-form-item>
        <el-form-item label="亏损比例（%）" required>
          <el-input-number
            v-model="formData.lossRatePercent"
            :min="0"
            :max="100"
            :precision="2"
            :step="0.01"
            style="width: 100%"
            placeholder="如：100 表示 100%（全部亏损）"
          />
        </el-form-item>
        <el-form-item label="最低购买">
          <el-input-number
            v-model="formData.minAmount"
            :min="0"
            :precision="2"
            :step="1"
            style="width: 100%"
            placeholder="如：20"
          />
        </el-form-item>
        <el-form-item label="最大购买">
          <el-input-number
            v-model="formData.maxAmount"
            :min="0"
            :precision="2"
            :step="1"
            style="width: 100%"
            placeholder="如：10000"
          />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number
            v-model="formData.sortOrder"
            :min="0"
            :precision="0"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="formData.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete } from '@element-plus/icons-vue'
import request from '@/utils/request'

const durations = ref<any[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const dialogTitle = ref('新增期限')
const formData = ref<any>({
  id: null,
  duration: 30,
  label: '',
  profitRatePercent: 80, // 前端使用百分比，后端存储为小数
  lossRatePercent: 100, // 前端使用百分比，后端存储为小数（默认100%表示全部亏损）
  minAmount: 20,
  maxAmount: 10000,
  sortOrder: 0,
  enabled: true,
})

const loadDurations = async () => {
  loading.value = true
  try {
    const res: any = await request.get('/admin/durations')
    durations.value = res.list || []
  } catch (e: any) {
    ElMessage.error(e?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

const handleAdd = () => {
  dialogTitle.value = '新增期限'
  formData.value = {
    id: null,
    duration: 30,
    label: '',
    profitRatePercent: 80,
    lossRatePercent: 100,
    minAmount: 20,
    maxAmount: 10000,
    sortOrder: 0,
    enabled: true,
  }
  dialogVisible.value = true
}

const handleEdit = (row: any) => {
  dialogTitle.value = '编辑期限'
  formData.value = {
    id: row.id,
    duration: row.duration,
    label: row.label,
    profitRatePercent: Number(row.profitRate || 0) * 100, // 转换为百分比
    lossRatePercent: Number(row.lossRate || 1) * 100, // 转换为百分比（默认100%）
    minAmount: row.minAmount ? Number(row.minAmount) : 20,
    maxAmount: row.maxAmount ? Number(row.maxAmount) : 10000,
    sortOrder: row.sortOrder,
    enabled: row.enabled,
  }
  dialogVisible.value = true
}

const handleSave = async () => {
  if (!formData.value.duration || formData.value.duration <= 0) {
    ElMessage.error('时长必须大于0')
    return
  }
  if (!formData.value.label || formData.value.label.trim() === '') {
    ElMessage.error('显示标签不能为空')
    return
  }
  if (formData.value.profitRatePercent == null || formData.value.profitRatePercent < 0 || formData.value.profitRatePercent > 100) {
    ElMessage.error('盈亏比例必须在0-100之间')
    return
  }
  if (formData.value.lossRatePercent == null || formData.value.lossRatePercent < 0 || formData.value.lossRatePercent > 100) {
    ElMessage.error('亏损比例必须在0-100之间')
    return
  }

  // 将百分比转换为小数（后端存储为小数）
  const submitData = {
    ...formData.value,
    profitRate: formData.value.profitRatePercent / 100,
    lossRate: formData.value.lossRatePercent / 100,
  }
  delete submitData.profitRatePercent // 删除前端字段
  delete submitData.lossRatePercent // 删除前端字段

  try {
    if (formData.value.id) {
      // 更新
      await request.put(`/admin/durations/${formData.value.id}`, submitData)
      ElMessage.success('更新成功')
    } else {
      // 新增
      await request.post('/admin/durations', submitData)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    loadDurations()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '操作失败')
  }
}

const handleDelete = async (row: any) => {
  try {
    await ElMessageBox.confirm('确定要删除该期限设置吗？', '提示', {
      type: 'warning',
    })
    await request.delete(`/admin/durations/${row.id}`)
    ElMessage.success('删除成功')
    loadDurations()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e?.response?.data?.message || e?.message || '删除失败')
    }
  }
}

onMounted(() => {
  loadDurations()
})
</script>

<style scoped>
.durations-page {
  padding: 20px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-header h2 {
  margin: 0;
}
</style>

