<template>
  <div class="financial-products-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>理财产品管理</span>
          <el-button type="primary" @click="handleAdd">添加理财产品</el-button>
        </div>
      </template>

      <el-table :data="productsList" style="width: 100%" v-loading="loading">
        <el-table-column prop="name" label="产品名称" width="150" />
        <el-table-column prop="currency" label="货币" width="80" />
        <el-table-column prop="dailyYieldRate" label="日产率(%)" width="120">
          <template #default="{ row }">
            {{ formatPercent(row.dailyYieldRate) }}
          </template>
        </el-table-column>
        <el-table-column prop="rentalFee" label="矿机租金" width="120">
          <template #default="{ row }">
            {{ formatMoney(row.rentalFee) }}
          </template>
        </el-table-column>
        <el-table-column prop="minPurchase" label="最小申购" width="120">
          <template #default="{ row }">
            {{ formatMoney(row.minPurchase) }}
          </template>
        </el-table-column>
        <el-table-column prop="maxPurchase" label="最大申购" width="120">
          <template #default="{ row }">
            {{ formatMoney(row.maxPurchase) }}
          </template>
        </el-table-column>
        <el-table-column prop="termDays" label="期限(天)" width="100" />
        <el-table-column prop="penaltyRate" label="违约费率(%)" width="120">
          <template #default="{ row }">
            {{ formatPercent(row.penaltyRate) }}
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
        <el-table-column label="操作" width="150" fixed="right">
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
      :title="dialogTitle"
      width="600px"
    >
      <el-form :model="formData" label-width="120px">
        <el-form-item label="产品名称">
          <el-input v-model="formData.name" placeholder="如：180MH/S" />
        </el-form-item>
        <el-form-item label="产品图片">
          <el-upload
            class="avatar-uploader"
            :http-request="handleImageUpload"
            :show-file-list="false"
            :before-upload="beforeUpload"
          >
            <img v-if="formData.imageUrl" :src="getImageUrl(formData.imageUrl)" class="avatar" />
            <el-icon v-else class="avatar-uploader-icon"><Plus /></el-icon>
          </el-upload>
        </el-form-item>
        <el-form-item label="货币类型">
          <el-input v-model="formData.currency" placeholder="如：USD" />
        </el-form-item>
        <el-form-item label="日产率(%)">
          <el-input-number v-model="formData.dailyYieldRate" :precision="6" :step="0.01" :min="0" />
        </el-form-item>
        <el-form-item label="矿机租金">
          <el-input-number v-model="formData.rentalFee" :precision="2" :step="1" :min="0" />
        </el-form-item>
        <el-form-item label="最小申购">
          <el-input-number v-model="formData.minPurchase" :precision="2" :step="1" :min="0" />
        </el-form-item>
        <el-form-item label="最大申购">
          <el-input-number v-model="formData.maxPurchase" :precision="2" :step="1" :min="0" />
        </el-form-item>
        <el-form-item label="理财期限(天)">
          <el-input-number v-model="formData.termDays" :step="1" :min="1" />
        </el-form-item>
        <el-form-item label="违约费率(%)">
          <el-input-number v-model="formData.penaltyRate" :precision="6" :step="0.01" :min="0" />
        </el-form-item>
        <el-form-item label="产品介绍">
          <el-input v-model="formData.description" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="formData.sortOrder" :step="1" :min="0" />
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
import { Plus } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { getImageUrl } from '@/utils/imageUrl'

const productsList = ref<any[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const dialogTitle = ref('添加理财产品')
const formData = ref<any>({
  name: '',
  imageUrl: '',
  currency: 'USD',
  dailyYieldRate: 0.3,
  rentalFee: 100,
  minPurchase: 100,
  maxPurchase: 9999,
  termDays: 3,
  penaltyRate: 30,
  description: '',
  enabled: true,
  sortOrder: 0
})

function formatMoney(v: number | string | undefined | null) {
  const n = Number(v || 0)
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function formatPercent(v: number | string | undefined | null) {
  const n = Number(v || 0)
  return n.toFixed(2)
}

async function loadList() {
  loading.value = true
  try {
    const res: any = await request.get('/admin/financial/products')
    if (res && res.success) {
      productsList.value = res.list || []
    }
  } catch (e: any) {
    ElMessage.error('加载失败: ' + (e.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

function handleAdd() {
  formData.value = {
    name: '',
    imageUrl: '',
    currency: 'USD',
    dailyYieldRate: 0.3,
    rentalFee: 100,
    minPurchase: 100,
    maxPurchase: 9999,
    termDays: 3,
    penaltyRate: 30,
    description: '',
    enabled: true,
    sortOrder: 0
  }
  dialogTitle.value = '添加理财产品'
  dialogVisible.value = true
}

function handleEdit(row: any) {
  formData.value = { ...row }
  dialogTitle.value = '编辑理财产品'
  dialogVisible.value = true
}

async function handleSubmit() {
  try {
    const url = formData.value.id 
      ? `/admin/financial/products/${formData.value.id}`
      : '/admin/financial/products'
    const method = formData.value.id ? 'put' : 'post'
    
    const res: any = await request[method](url, formData.value)
    if (res && res.success) {
      ElMessage.success('操作成功')
      dialogVisible.value = false
      loadList()
    }
  } catch (e: any) {
    ElMessage.error('操作失败: ' + (e.message || '未知错误'))
  }
}

async function handleUpdate(row: any) {
  try {
    const res: any = await request.put(`/admin/financial/products/${row.id}`, row)
    if (res && res.success) {
      ElMessage.success('更新成功')
    }
  } catch (e: any) {
    ElMessage.error('更新失败: ' + (e.message || '未知错误'))
    loadList()
  }
}

function handleDelete(row: any) {
  ElMessageBox.confirm('确定要删除这个理财产品吗？', '确认删除', {
    type: 'warning',
  })
    .then(() => {
      return request.delete(`/admin/financial/products/${row.id}`)
    })
    .then((res: any) => {
      if (res && res.success) {
        ElMessage.success('删除成功')
        loadList()
      }
    })
    .catch(() => {})
}

// 自定义图片上传
async function handleImageUpload(options: any) {
  try {
    const uploadFormData = new FormData()
    uploadFormData.append('file', options.file)
    
    const res: any = await request.post('/upload/image', uploadFormData, {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    })
    
    console.log('上传响应:', res)
    
    // 尝试多种可能的响应格式
    const imageUrl = res.url || res.data?.url || res.data
    if (imageUrl) {
      formData.value.imageUrl = imageUrl
      ElMessage.success('图片上传成功')
    } else {
      console.error('无法获取图片URL，响应:', res)
      ElMessage.error('图片上传失败：无法获取图片URL')
    }
  } catch (e: any) {
    console.error('图片上传失败:', e)
    ElMessage.error(e.response?.data?.message || e.message || '图片上传失败')
  }
}

function beforeUpload(file: File) {
  const isImage = file.type.startsWith('image/')
  const isLt5M = file.size / 1024 / 1024 < 5

  if (!isImage) {
    ElMessage.error('只能上传图片文件!')
    return false
  }
  if (!isLt5M) {
    ElMessage.error('图片大小不能超过5MB!')
    return false
  }
  return true
}

onMounted(() => {
  loadList()
})
</script>

<style scoped>
.financial-products-page {
  height: 100%;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.avatar-uploader {
  width: 100px;
  height: 100px;
  border: 1px dashed #d9d9d9;
  border-radius: 6px;
  cursor: pointer;
  position: relative;
  overflow: hidden;
}

.avatar-uploader:hover {
  border-color: #409eff;
}

.avatar-uploader-icon {
  font-size: 28px;
  color: #8c939d;
  width: 100px;
  height: 100px;
  line-height: 100px;
  text-align: center;
}

.avatar {
  width: 100px;
  height: 100px;
  display: block;
  object-fit: cover;
}
</style>

