<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import axios from 'axios'
import { usePermissions } from '@/composables/usePermissions'

// 开发环境使用空字符串，让Vite代理处理；生产环境使用生产API域名（不包含/api后缀）
const getApiBase = () => {
  if (import.meta.env.DEV) {
    return ''
  }
  const baseUrl = import.meta.env.VITE_API_BASE_URL || 'https://api1.m.ydgggd.com'
  // 确保不包含 /api 后缀，因为路径中会添加 /api
  return baseUrl.replace(/\/api\/?$/, '')
}
const API_BASE = getApiBase()

const { hasPermission } = usePermissions()
const canEditRole = ref(true)
const canAssignPermission = ref(true)
const canDeleteRole = ref(true)

const loadPermissions = async () => {
  canEditRole.value = await hasPermission('roles', 'edit_role')
  canAssignPermission.value = await hasPermission('roles', 'assign_permission')
  canDeleteRole.value = await hasPermission('roles', 'delete_role')
}

// 角色列表
const roles = ref<any[]>([])
const loading = ref(false)

// 对话框控制
const dialogVisible = ref(false)
const dialogTitle = ref('')
const isEdit = ref(false)

// 表单数据
const formData = ref({
  id: null as number | null,
  roleName: '',
  roleCode: '',
  description: '',
  status: 'active',
})

// 权限分配对话框
const permissionDialogVisible = ref(false)
const currentRoleId = ref<number | null>(null)
const currentRoleName = ref('')
const allMenus = ref<any[]>([])
const checkedMenuIds = ref<number[]>([])

// 获取角色列表
const fetchRoles = async () => {
  loading.value = true
  try {
    const response = await axios.get(`${API_BASE}/api/admin/roles`)
    if (response.data.success) {
      roles.value = response.data.list
    } else {
      ElMessage.error(response.data.message || '获取角色列表失败')
    }
  } catch (error: any) {
    console.error('获取角色列表失败:', error)
    ElMessage.error(error.response?.data?.message || '获取角色列表失败')
  } finally {
    loading.value = false
  }
}

// 添加角色
const handleAdd = () => {
  dialogTitle.value = '新增角色'
  isEdit.value = false
  formData.value = {
    id: null,
    roleName: '',
    roleCode: '',
    description: '',
    status: 'active',
  }
  dialogVisible.value = true
}

// 编辑角色
const handleEdit = (row: any) => {
  if (row.isSuper) {
    ElMessage.warning('超级管理员角色不能编辑')
    return
  }
  
  dialogTitle.value = '编辑角色'
  isEdit.value = true
  formData.value = {
    id: row.id,
    roleName: row.roleName,
    roleCode: row.roleCode,
    description: row.description,
    status: row.status,
  }
  dialogVisible.value = true
}

// 保存角色
const handleSave = async () => {
  // 验证
  if (!formData.value.roleName) {
    ElMessage.warning('请输入角色名称')
    return
  }
  if (!formData.value.roleCode) {
    ElMessage.warning('请输入角色代码')
    return
  }
  
  loading.value = true
  try {
    let response
    if (isEdit.value) {
      // 更新
      response = await axios.put(`${API_BASE}/api/admin/roles/${formData.value.id}`, formData.value)
    } else {
      // 创建
      response = await axios.post(`${API_BASE}/api/admin/roles`, formData.value)
    }
    
    if (response.data.success) {
      ElMessage.success(response.data.message || '操作成功')
      dialogVisible.value = false
      fetchRoles()
    } else {
      ElMessage.error(response.data.message || '操作失败')
    }
  } catch (error: any) {
    console.error('保存角色失败:', error)
    ElMessage.error(error.response?.data?.message || '保存角色失败')
  } finally {
    loading.value = false
  }
}

// 删除角色
const handleDelete = (row: any) => {
  if (row.isSuper) {
    ElMessage.warning('超级管理员角色不能删除')
    return
  }
  
  ElMessageBox.confirm(
    `确定要删除角色 "${row.roleName}" 吗？`,
    '删除确认',
    {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    }
  ).then(async () => {
    loading.value = true
    try {
      const response = await axios.delete(`${API_BASE}/api/admin/roles/${row.id}`)
      if (response.data.success) {
        ElMessage.success(response.data.message || '删除成功')
        fetchRoles()
      } else {
        ElMessage.error(response.data.message || '删除失败')
      }
    } catch (error: any) {
      console.error('删除角色失败:', error)
      ElMessage.error(error.response?.data?.message || '删除角色失败')
    } finally {
      loading.value = false
    }
  }).catch(() => {
    // 取消删除
  })
}

// 分配权限
const handlePermission = async (row: any) => {
  if (row.isSuper) {
    ElMessage.warning('超级管理员权限不能修改')
    return
  }
  
  currentRoleId.value = row.id
  currentRoleName.value = row.roleName
  
  try {
    // 获取所有菜单
    const menusResponse = await axios.get(`${API_BASE}/api/admin/menus`)
    if (menusResponse.data.success) {
      allMenus.value = menusResponse.data.list
    }
    
    // 获取当前角色的菜单权限
    const roleMenusResponse = await axios.get(`${API_BASE}/api/admin/roles/${row.id}/menus`)
    if (roleMenusResponse.data.success) {
      checkedMenuIds.value = roleMenusResponse.data.menuIds || []
    }
    
    permissionDialogVisible.value = true
  } catch (error: any) {
    console.error('获取菜单数据失败:', error)
    ElMessage.error(error.response?.data?.message || '获取菜单数据失败')
  }
}

// 保存权限
const handleSavePermission = async () => {
  if (!currentRoleId.value) return
  
  loading.value = true
  try {
    const response = await axios.post(
      `${API_BASE}/api/admin/roles/${currentRoleId.value}/menus`,
      { menuIds: checkedMenuIds.value }
    )
    
    if (response.data.success) {
      ElMessage.success(response.data.message || '权限分配成功')
      permissionDialogVisible.value = false
      fetchRoles()
    } else {
      ElMessage.error(response.data.message || '权限分配失败')
    }
  } catch (error: any) {
    console.error('保存权限失败:', error)
    ElMessage.error(error.response?.data?.message || '保存权限失败')
  } finally {
    loading.value = false
  }
}

// 页面加载时获取数据
onMounted(() => {
  fetchRoles()
  loadPermissions()
})
</script>

<template>
  <div class="roles-page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span class="card-title">角色管理</span>
          <el-button type="primary" @click="handleAdd">新增角色</el-button>
        </div>
      </template>

      <!-- 表格 -->
      <el-table :data="roles" stripe border v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="roleName" label="角色名称" width="150" />
        <el-table-column prop="roleCode" label="角色代码" width="150" />
        <el-table-column prop="description" label="描述" min-width="200" />
        <el-table-column label="超级管理员" width="120" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.isSuper" type="danger" size="small">是</el-tag>
            <el-tag v-else type="info" size="small">否</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="菜单权限数" width="120" align="center">
          <template #default="{ row }">
            <el-tag type="primary" size="small">{{ row.menuCount }}个</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.status === 'active'" type="success" size="small">启用</el-tag>
            <el-tag v-else type="info" size="small">禁用</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button 
              v-if="canEditRole"
              link 
              type="primary" 
              size="small" 
              @click="handleEdit(row)"
            >
              编辑
            </el-button>
            <el-button 
              v-if="canAssignPermission"
              link 
              type="warning" 
              size="small" 
              @click="handlePermission(row)"
            >
              分配权限
            </el-button>
            <el-button 
              v-if="canDeleteRole"
              link 
              type="danger" 
              size="small" 
              :disabled="row.isSuper"
              @click="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增/编辑对话框 -->
    <el-dialog 
      v-model="dialogVisible" 
      :title="dialogTitle" 
      width="500px"
    >
      <el-form :model="formData" label-width="100px">
        <el-form-item label="角色名称" required>
          <el-input v-model="formData.roleName" placeholder="请输入角色名称" />
        </el-form-item>
        <el-form-item label="角色代码" required>
          <el-input 
            v-model="formData.roleCode" 
            placeholder="请输入角色代码（英文）"
            :disabled="isEdit"
          />
        </el-form-item>
        <el-form-item label="角色描述">
          <el-input 
            v-model="formData.description" 
            type="textarea"
            :rows="3"
            placeholder="请输入角色描述"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="formData.status">
            <el-radio label="active">启用</el-radio>
            <el-radio label="inactive">禁用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave" :loading="loading">保存</el-button>
      </template>
    </el-dialog>

    <!-- 权限分配对话框 -->
    <el-dialog 
      v-model="permissionDialogVisible" 
      title="分配菜单权限" 
      width="500px"
    >
      <div style="margin-bottom: 15px; color: #606266;">
        为角色 <strong>{{ currentRoleName }}</strong> 分配菜单权限：
      </div>
      
      <el-checkbox-group v-model="checkedMenuIds">
        <div v-for="menu in allMenus" :key="menu.id" style="margin-bottom: 10px;">
          <el-checkbox :label="menu.id">
            {{ menu.menuName }} <span style="color: #909399;">({{ menu.menuCode }})</span>
          </el-checkbox>
        </div>
      </el-checkbox-group>
      
      <template #footer>
        <el-button @click="permissionDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSavePermission" :loading="loading">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.roles-page {
  padding: 0;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-title {
  font-size: 18px;
  font-weight: bold;
}
</style>
