<template>
  <div class="loan-settings-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>贷款设置</span>
          <el-button type="primary" @click="handleAdd">添加贷款设置</el-button>
        </div>
      </template>

      <el-table :data="settingsList" style="width: 100%">
        <el-table-column prop="days" label="贷款期限(天)" width="120" />
        <el-table-column prop="dailyRate" label="日利率(%)" width="120">
          <template #default="{ row }">
            {{ row.dailyRate }}
          </template>
        </el-table-column>
        <el-table-column prop="freeDays" label="免息天数" width="100" />
        <el-table-column prop="overdueRate" label="逾期费率(%)" width="120">
          <template #default="{ row }">
            {{ row.overdueRate || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="minAmount" label="最小金额" width="120">
          <template #default="{ row }">
            {{ row.minAmount ? formatMoney(row.minAmount) : '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="maxAmount" label="最大金额" width="120">
          <template #default="{ row }">
            {{ row.maxAmount ? formatMoney(row.maxAmount) : '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="enabled" label="启用状态" width="100">
          <template #default="{ row }">
            <el-switch
              v-model="row.enabled"
              @change="handleUpdate(row)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="handleEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 添加/编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingItem ? '编辑贷款设置' : '添加贷款设置'"
      width="500px"
    >
      <el-form :model="formData" label-width="120px">
        <el-form-item label="贷款期限(天)" required>
          <el-input-number v-model="formData.days" :min="1" :max="365" style="width: 100%" />
        </el-form-item>
        
        <el-form-item label="日利率(%)" required>
          <el-input-number 
            v-model="formData.dailyRate" 
            :min="0" 
            :max="100" 
            :precision="4"
            style="width: 100%" 
          />
        </el-form-item>

        <el-form-item label="免息天数" required>
          <el-input-number v-model="formData.freeDays" :min="0" :max="365" style="width: 100%" />
        </el-form-item>

        <el-form-item label="逾期费率(%)">
          <el-input-number 
            v-model="formData.overdueRate" 
            :min="0" 
            :max="100" 
            :precision="4"
            style="width: 100%" 
          />
        </el-form-item>

        <el-form-item label="最小金额">
          <el-input-number 
            v-model="formData.minAmount" 
            :min="0" 
            :precision="2"
            style="width: 100%" 
          />
        </el-form-item>

        <el-form-item label="最大金额">
          <el-input-number 
            v-model="formData.maxAmount" 
            :min="0" 
            :precision="2"
            style="width: 100%" 
          />
        </el-form-item>

        <el-form-item label="启用状态">
          <el-switch v-model="formData.enabled" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'

const settingsList = ref<any[]>([])
const dialogVisible = ref(false)
const editingItem = ref<any>(null)

const formData = ref({
  days: 15,
  dailyRate: 0.18,
  freeDays: 5,
  overdueRate: 0.25,
  minAmount: null as number | null,
  maxAmount: null as number | null,
  enabled: true,
})

function formatMoney(v: number | string | undefined | null) {
  const n = Number(v || 0)
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

async function loadSettings() {
  try {
    const res: any = await request.get('/admin/loan/settings')
    if (res && res.success) {
      settingsList.value = res.list || []
    }
  } catch (e: any) {
    ElMessage.error('加载失败: ' + (e.message || '未知错误'))
  }
}

function handleAdd() {
  editingItem.value = null
  formData.value = {
    days: 15,
    dailyRate: 0.18,
    freeDays: 5,
    overdueRate: 0.25,
    minAmount: null,
    maxAmount: null,
    enabled: true,
  }
  dialogVisible.value = true
}

function handleEdit(row: any) {
  editingItem.value = row
  formData.value = {
    days: row.days,
    dailyRate: row.dailyRate ? Number(row.dailyRate) : 0.18,
    freeDays: row.freeDays || 0,
    overdueRate: row.overdueRate ? Number(row.overdueRate) : 0.25,
    minAmount: row.minAmount ? Number(row.minAmount) : null,
    maxAmount: row.maxAmount ? Number(row.maxAmount) : null,
    enabled: row.enabled !== undefined ? row.enabled : true,
  }
  dialogVisible.value = true
}

function handleUpdate(row: any) {
  request
    .put(`/admin/loan/settings/${row.id}`, row)
    .then((res: any) => {
      if (res && res.success) {
        ElMessage.success('更新成功')
        loadSettings()
      }
    })
    .catch((e: any) => {
      ElMessage.error('更新失败: ' + (e.message || '未知错误'))
      loadSettings()
    })
}

function handleDelete(row: any) {
  ElMessageBox.confirm('确定要删除这条贷款设置吗？', '确认删除', {
    type: 'warning',
  })
    .then(() => {
      return request.delete(`/admin/loan/settings/${row.id}`)
    })
    .then((res: any) => {
      if (res && res.success) {
        ElMessage.success('删除成功')
        loadSettings()
      }
    })
    .catch((e: any) => {
      if (e !== 'cancel') {
        ElMessage.error('删除失败: ' + (e.message || '未知错误'))
      }
    })
}

function handleSubmit() {
  if (editingItem.value) {
    request
      .put(`/admin/loan/settings/${editingItem.value.id}`, formData.value)
      .then((res: any) => {
        if (res && res.success) {
          ElMessage.success('更新成功')
          dialogVisible.value = false
          loadSettings()
        }
      })
      .catch((e: any) => {
        ElMessage.error('更新失败: ' + (e.message || '未知错误'))
      })
  } else {
    request
      .post('/admin/loan/settings', formData.value)
      .then((res: any) => {
        if (res && res.success) {
          ElMessage.success('添加成功')
          dialogVisible.value = false
          loadSettings()
        }
      })
      .catch((e: any) => {
        ElMessage.error('添加失败: ' + (e.message || '未知错误'))
      })
  }
}

onMounted(() => {
  loadSettings()
})
</script>

<style scoped>
.loan-settings-page {
  height: 100%;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>



