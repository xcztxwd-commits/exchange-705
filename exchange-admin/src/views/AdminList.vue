<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, Edit, Delete, Lock, Unlock } from '@element-plus/icons-vue'
import request from '@/utils/request'

// 管理员列表
const admins = ref<any[]>([])
const loading = ref(false)
const total = ref(0)

// 分页
const currentPage = ref(1)
const pageSize = ref(20)

// 搜索条件
const searchForm = ref({
  id: '',
  keyword: '',
  role: '',
  enabled: null as boolean | null
})

// 添加/编辑对话框
const dialogVisible = ref(false)
const dialogTitle = ref('添加管理员')
const formData = ref({
  id: null as number | null,
  account: '',
  email: '',
  password: '',
  role: 'admin',
  enabled: true
})

// 表单验证规则
const formRules = {
  account: [
    { required: true, message: '请输入登录账号', trigger: 'blur' },
    { min: 3, max: 64, message: '账号长度在3到64个字符', trigger: 'blur' }
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '请输入有效的邮箱地址', trigger: 'blur' }
  ],
  password: [
    { 
      validator: (rule: any, value: string, callback: Function) => {
        // 如果是编辑模式（有id），密码是可选的
        if (formData.value.id) {
          // 编辑时，如果提供了密码，则验证长度
          if (value && value.trim() && value.length < 6) {
            callback(new Error('密码长度至少6个字符'))
          } else {
            callback()
          }
        } else {
          // 新增时，密码必填
          if (!value || !value.trim()) {
            callback(new Error('请输入密码'))
          } else if (value.length < 6) {
            callback(new Error('密码长度至少6个字符'))
          } else {
            callback()
          }
        }
      },
      trigger: 'blur'
    }
  ],
  role: [
    { required: true, message: '请选择角色', trigger: 'change' }
  ]
}

const formRef = ref()

// 获取管理员列表
const fetchAdmins = async () => {
  loading.value = true
  try {
    const params: any = {
      page: currentPage.value - 1, // 后端从0开始
      size: pageSize.value
    }
    
    if (searchForm.value.id) {
      params.id = searchForm.value.id
    }
    if (searchForm.value.keyword) {
      params.keyword = searchForm.value.keyword
    }
    if (searchForm.value.role) {
      params.role = searchForm.value.role
    }
    if (searchForm.value.enabled !== null) {
      params.enabled = searchForm.value.enabled
    }
    
    const res: any = await request.get('/admin/admins', { params })
    if (res && res.success) {
      admins.value = res.list || []
      total.value = res.total || 0
    } else {
      ElMessage.error(res?.message || '获取管理员列表失败')
    }
  } catch (e: any) {
    console.error('获取管理员列表失败:', e)
    ElMessage.error(e?.response?.data?.message || e?.message || '获取管理员列表失败')
  } finally {
    loading.value = false
  }
}

// 搜索
const handleSearch = () => {
  currentPage.value = 1
  fetchAdmins()
}

// 重置
const handleReset = () => {
  searchForm.value = {
    id: '',
    keyword: '',
    role: '',
    enabled: null
  }
  currentPage.value = 1
  fetchAdmins()
}

// 分页变化
const handlePageChange = (page: number) => {
  currentPage.value = page
  fetchAdmins()
}

// 打开添加对话框
const handleAdd = () => {
  dialogTitle.value = '添加管理员'
  formData.value = {
    id: null,
    account: '',
    email: '',
    password: '',
    role: 'admin',
    enabled: true
  }
  dialogVisible.value = true
}

// 打开编辑对话框
const handleEdit = (row: any) => {
  dialogTitle.value = '编辑管理员'
  formData.value = {
    id: row.id,
    account: row.account,
    email: row.email,
    password: '', // 编辑时不显示密码
    role: row.role,
    enabled: row.enabled
  }
  dialogVisible.value = true
}

// 保存管理员
const handleSave = async () => {
  if (!formRef.value) return
  
  await formRef.value.validate(async (valid: boolean) => {
    if (!valid) return
    
    try {
      if (formData.value.id) {
        // 更新
        const updateData: any = {
          account: formData.value.account,
          email: formData.value.email,
          role: formData.value.role,
          enabled: formData.value.enabled
        }
        // 只有提供了新密码才更新密码
        if (formData.value.password && formData.value.password.trim()) {
          updateData.password = formData.value.password
        }
        
        await request.put(`/admin/admins/${formData.value.id}`, updateData)
        ElMessage.success('管理员更新成功')
      } else {
        // 创建
        await request.post('/admin/admins', {
          account: formData.value.account,
          email: formData.value.email,
          password: formData.value.password,
          role: formData.value.role,
          enabled: formData.value.enabled
        })
        ElMessage.success('管理员创建成功')
      }
      
      dialogVisible.value = false
      fetchAdmins()
    } catch (e: any) {
      ElMessage.error(e?.response?.data?.message || e?.message || '操作失败')
    }
  })
}

// 删除管理员
const handleDelete = async (row: any) => {
  try {
    await ElMessageBox.confirm(
      `确定要删除管理员 "${row.account}" 吗？此操作不可恢复！`,
      '确认删除',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
    
    await request.delete(`/admin/admins/${row.id}`)
    ElMessage.success('删除成功')
    fetchAdmins()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e?.response?.data?.message || e?.message || '删除失败')
    }
  }
}

// 启用/禁用管理员
const handleToggleStatus = async (row: any) => {
  try {
    const action = row.enabled ? '禁用' : '启用'
    await ElMessageBox.confirm(
      `确定要${action}管理员 "${row.account}" 吗？`,
      `确认${action}`,
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
    
    await request.put(`/admin/admins/${row.id}/status`, {
      enabled: !row.enabled
    })
    
    ElMessage.success(`${action}成功`)
    fetchAdmins()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e?.response?.data?.message || e?.message || '操作失败')
    } else {
      // 取消操作，恢复原状态
      fetchAdmins()
    }
  }
}

// 格式化日期
const formatDate = (date: string | Date | null) => {
  if (!date) return '-'
  return new Date(date).toLocaleString('zh-CN')
}

// 获取角色显示名称
const getRoleName = (role: string) => {
  const roleMap: Record<string, string> = {
    'super_admin': '超级管理员',
    'admin': '管理员',
    'ops': '运营'
  }
  return roleMap[role] || role
}

onMounted(() => {
  fetchAdmins()
})
</script>

<template>
  <div class="admin-list-page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span class="card-title">管理员列表</span>
          <el-button type="primary" :icon="Plus" @click="handleAdd">
            添加管理员
          </el-button>
        </div>
      </template>

      <!-- 搜索表单 -->
      <el-form :model="searchForm" inline style="margin-bottom: 16px">
        <el-form-item label="关键词">
          <el-input
            v-model="searchForm.keyword"
            placeholder="账号/邮箱"
            clearable
            style="width: 200px"
          />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="searchForm.role" placeholder="全部" clearable style="width: 120px">
            <el-option label="超级管理员" value="super_admin" />
            <el-option label="管理员" value="admin" />
            <el-option label="运营" value="ops" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="searchForm.enabled" placeholder="全部" clearable style="width: 120px">
            <el-option label="启用" :value="true" />
            <el-option label="禁用" :value="false" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>

      <!-- 表格 -->
      <el-table :data="admins" stripe border v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="account" label="登录账号" width="150" />
        <el-table-column prop="email" label="邮箱" width="200" />
        <el-table-column prop="role" label="角色" width="120">
          <template #default="{ row }">
            <el-tag :type="row.role === 'super_admin' ? 'danger' : row.role === 'admin' ? 'primary' : 'info'">
              {{ getRoleName(row.role) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="enabled" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'danger'">
              {{ row.enabled ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180">
          <template #default="{ row }">
            {{ formatDate(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="180">
          <template #default="{ row }">
            {{ formatDate(row.updatedAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button 
              type="primary" 
              size="small" 
              :icon="Edit"
              @click="handleEdit(row)"
            >
              编辑
            </el-button>
            <el-button 
              :type="row.enabled ? 'warning' : 'success'"
              size="small" 
              :icon="row.enabled ? Lock : Unlock"
              @click="handleToggleStatus(row)"
            >
              {{ row.enabled ? '禁用' : '启用' }}
            </el-button>
            <el-button 
              type="danger" 
              size="small" 
              :icon="Delete"
              @click="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination" style="margin-top: 16px">
        <el-pagination
          v-model:current-page="currentPage"
          :page-size="pageSize"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>

    <!-- 添加/编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="500px"
      @close="formRef?.resetFields()"
    >
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="100px"
      >
        <el-form-item label="登录账号" prop="account">
          <el-input
            v-model="formData.account"
            placeholder="请输入登录账号"
            :disabled="!!formData.id"
          />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input
            v-model="formData.email"
            placeholder="请输入邮箱"
            type="email"
          />
        </el-form-item>
        <el-form-item label="密码" prop="password" :required="!formData.id">
          <el-input
            v-model="formData.password"
            type="password"
            :placeholder="formData.id ? '留空则不修改密码' : '请输入密码'"
            show-password
          />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="formData.role" placeholder="请选择角色" style="width: 100%">
            <el-option label="超级管理员" value="super_admin" />
            <el-option label="管理员" value="admin" />
            <el-option label="运营" value="ops" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch
            v-model="formData.enabled"
            active-text="启用"
            inactive-text="禁用"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.admin-list-page {
  padding: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-title {
  font-size: 18px;
  font-weight: 600;
}

.pagination {
  display: flex;
  justify-content: flex-end;
}
</style>

