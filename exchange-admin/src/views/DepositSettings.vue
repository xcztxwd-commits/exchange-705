<template>
  <div class="deposit-settings-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>充值设置</span>
          <el-button type="primary" @click="handleAdd">添加充值方式</el-button>
        </div>
      </template>

      <el-table :data="settingsList" style="width: 100%">
        <el-table-column prop="type" label="类型" width="100">
          <template #default="{ row }">
            <el-tag :type="row.type === 'bank' ? 'success' : 'info'">
              {{ row.type === 'bank' ? '银行卡' : '数字货币' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="network" label="网络/币种" width="150">
          <template #default="{ row }">
            {{ row.network || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="address" label="充值地址/银行卡号" min-width="200">
          <template #default="{ row }">
            <el-input 
              v-if="row.type === 'digital'"
              v-model="row.address" 
              @blur="handleUpdate(row)" 
            />
            <span v-else>{{ row.bankAccount || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="false" prop="bankName" label="开户银行" width="120">
          <template #default="{ row }">
            {{ row.bankName || '-' }}
          </template>
        </el-table-column>
        <el-table-column v-if="false" prop="accountName" label="户名" width="120">
          <template #default="{ row }">
            {{ row.accountName || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="qrCode" label="二维码" width="120">
          <template #default="{ row }">
            <el-upload
              v-if="row.type === 'digital'"
              :http-request="(options: any) => handleUpload(options, row)"
              :show-file-list="false"
              :before-upload="beforeUpload"
            >
              <img v-if="row.qrCode" :src="getImageUrl(row.qrCode)" class="qr-image" />
              <el-button v-else size="small" type="primary">上传</el-button>
            </el-upload>
            <span v-else>-</span>
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
      :title="editingItem ? '编辑充值方式' : '添加充值方式'"
      width="500px"
    >
      <el-form :model="formData" label-width="100px">
        <el-form-item label="类型" required>
          <el-radio-group v-model="formData.type">
            <el-radio label="digital">数字货币</el-radio>
            <el-radio label="bank">银行卡</el-radio>
          </el-radio-group>
        </el-form-item>
        
        <!-- 数字货币字段 -->
        <template v-if="formData.type === 'digital'">
          <el-form-item label="网络/币种" required>
            <el-input v-model="formData.network" placeholder="例如: USDC-ERC20" />
          </el-form-item>
          <el-form-item label="充值地址" required>
            <el-input v-model="formData.address" placeholder="请输入充值地址" />
          </el-form-item>
          <el-form-item label="二维码">
            <el-upload
              :http-request="(options: any) => handleFormUpload(options)"
              :show-file-list="false"
              :before-upload="beforeUpload"
            >
              <img v-if="formData.qrCode" :src="getImageUrl(formData.qrCode)" class="qr-image" />
              <el-button v-else size="small" type="primary">上传二维码</el-button>
            </el-upload>
          </el-form-item>
        </template>
        
        <!-- 银行卡字段 -->
        <template v-else>
          <el-form-item label="开户银行" required>
            <el-input v-model="formData.bankName" placeholder="请输入开户银行" />
          </el-form-item>
          <el-form-item label="银行卡号" required>
            <el-input v-model="formData.bankAccount" placeholder="请输入银行卡号" />
          </el-form-item>
          <el-form-item label="户名" required>
            <el-input v-model="formData.accountName" placeholder="请输入户名" />
          </el-form-item>
        </template>
        
        <el-form-item label="启用状态">
          <el-switch v-model="formData.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
import { useAuthStore } from '@/store/auth'
import { getImageUrl } from '@/utils/imageUrl'

const auth = useAuthStore()
auth.load()

const settingsList = ref<any[]>([])
const dialogVisible = ref(false)
const editingItem = ref<any>(null)

const formData = ref({
  type: 'digital',
  network: '',
  address: '',
  qrCode: '',
  bankName: '',
  bankAccount: '',
  accountName: '',
  enabled: true,
})


// 加载设置列表
async function loadSettings() {
  try {
    const res: any = await request.get('/admin/deposit/settings')
    if (res && res.success !== false) {
      settingsList.value = res.list || res.data || []
    }
  } catch (e: any) {
    console.error('加载充值设置失败:', e)
    ElMessage.error('加载失败')
  }
}

// 添加
function handleAdd() {
  editingItem.value = null
  formData.value = {
    type: 'digital',
    network: '',
    address: '',
    qrCode: '',
    bankName: '',
    bankAccount: '',
    accountName: '',
    enabled: true,
  }
  dialogVisible.value = true
}

// 编辑
function handleEdit(row: any) {
  editingItem.value = row
  formData.value = {
    type: row.type || 'digital',
    network: row.network || '',
    address: row.address || '',
    qrCode: row.qrCode || '',
    bankName: row.bankName || '',
    bankAccount: row.bankAccount || '',
    accountName: row.accountName || '',
    enabled: row.enabled !== undefined ? row.enabled : true,
  }
  dialogVisible.value = true
}


// 更新
async function handleUpdate(row: any) {
  try {
    const res: any = await request.put(`/admin/deposit/settings/${row.id}`, row)
    if (res && res.success !== false) {
      ElMessage.success('更新成功')
      loadSettings()
    } else {
      ElMessage.error(res.message || '更新失败')
    }
  } catch (e: any) {
    console.error('更新失败:', e)
    ElMessage.error(e.response?.data?.message || '更新失败')
  }
}

// 删除
async function handleDelete(row: any) {
  try {
    await ElMessageBox.confirm('确定要删除这个充值方式吗？', '提示', {
      type: 'warning'
    })
    
    const res: any = await request.delete(`/admin/deposit/settings/${row.id}`)
    if (res && res.success !== false) {
      ElMessage.success('删除成功')
      loadSettings()
    } else {
      ElMessage.error(res.message || '删除失败')
    }
  } catch (e: any) {
    if (e !== 'cancel') {
      console.error('删除失败:', e)
      ElMessage.error(e.response?.data?.message || '删除失败')
    }
  }
}

// 提交表单
async function handleSubmit() {
  if (formData.value.type === 'digital') {
    if (!formData.value.network || !formData.value.address) {
      ElMessage.warning('请填写完整信息')
      return
    }
  } else if (formData.value.type === 'bank') {
    if (!formData.value.bankName || !formData.value.bankAccount || !formData.value.accountName) {
      ElMessage.warning('请填写完整信息')
      return
    }
  }
  
  try {
    let res: any
    if (editingItem.value) {
      res = await request.put(`/admin/deposit/settings/${editingItem.value.id}`, formData.value)
    } else {
      res = await request.post('/admin/deposit/settings', formData.value)
    }
    
    if (res && res.success !== false) {
      ElMessage.success(editingItem.value ? '更新成功' : '添加成功')
      dialogVisible.value = false
      loadSettings()
    } else {
      ElMessage.error(res.message || '操作失败')
    }
  } catch (e: any) {
    console.error('操作失败:', e)
    ElMessage.error(e.response?.data?.message || '操作失败')
  }
}

// getImageUrl 函数已从 @/utils/imageUrl 导入

// 图片上传前验证
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

// 表格中图片上传（自定义上传方法）
async function handleUpload(options: any, row: any) {
  try {
    const formData = new FormData()
    formData.append('file', options.file)
    
    const res: any = await request.post('/upload/image', formData, {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    })
    
    const imageUrl = res.url || res.data?.url
    if (imageUrl) {
      row.qrCode = imageUrl
      handleUpdate(row)
      ElMessage.success('图片上传成功')
    } else {
      ElMessage.error('图片上传失败')
    }
  } catch (e: any) {
    console.error('图片上传失败:', e)
    ElMessage.error(e.message || '图片上传失败')
  }
}

// 表单中图片上传（自定义上传方法）
async function handleFormUpload(options: any) {
  try {
    const uploadFormData = new FormData()
    uploadFormData.append('file', options.file)
    
    const res: any = await request.post('/upload/image', uploadFormData, {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    })
    
    const imageUrl = res.url || res.data?.url
    if (imageUrl) {
      formData.value.qrCode = imageUrl
      ElMessage.success('图片上传成功')
    } else {
      ElMessage.error('图片上传失败')
    }
  } catch (e: any) {
    console.error('图片上传失败:', e)
    ElMessage.error(e.message || '图片上传失败')
  }
}

onMounted(() => {
  loadSettings()
})
</script>

<style scoped>
.deposit-settings-page {
  padding: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.qr-image {
  width: 80px;
  height: 80px;
  object-fit: cover;
  border-radius: 4px;
}
</style>

