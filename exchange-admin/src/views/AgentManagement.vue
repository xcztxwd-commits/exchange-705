<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { rawRequest as axios } from '@/utils/request'
import request from '@/utils/request'

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

// 代理列表
const agents = ref<any[]>([])
const loading = ref(false)
const total = ref(0)

// 分页
const currentPage = ref(1)
const pageSize = ref(20)

// 搜索条件
const searchForm = ref({
  keyword: '',
  status: ''
})

// 权限分配对话框
const permissionDialogVisible = ref(false)
const currentAgentId = ref<number | null>(null)
const currentAgentName = ref('')
const allMenus = ref<any[]>([])
const checkedMenuIds = ref<number[]>([])
const menuActionsMap = ref<Map<number, any[]>>(new Map()) // 菜单ID -> 操作列表
const checkedActionsMap = ref<Map<number, string[]>>(new Map()) // 菜单ID -> 选中的操作代码列表
const expandedMenus = ref<Set<number>>(new Set()) // 展开的菜单ID集合

// 查看下级代理对话框
const subordinatesDialogVisible = ref(false)
const subordinates = ref<any[]>([])
const subordinatesLoading = ref(false)
const currentSubordinateAgentId = ref<number | null>(null)
const currentSubordinateAgentName = ref('')

// 编辑备注对话框
const remarkDialogVisible = ref(false)
const remarkForm = ref({
  userId: 0,
  nickname: '',
  currentRemark: '',
  newRemark: '',
})

// 默认权限配置对话框
const defaultPermissionDialogVisible = ref(false)
const defaultCheckedMenuIds = ref<number[]>([])
const defaultCheckedActionsMap = ref<Map<number, string[]>>(new Map())

// 获取代理列表
const fetchAgents = async () => {
  loading.value = true
  try {
    const params = {
      page: currentPage.value,
      size: pageSize.value,
      userType: 'agent', // 只查询代理用户
      keyword: searchForm.value.keyword || undefined,
      status: searchForm.value.status || undefined
    }
    
    const response = await axios.get(`${API_BASE}/api/admin/users`, { params })
    if (response.data.success) {
      agents.value = response.data.list || []
      total.value = response.data.total || 0
    } else {
      ElMessage.error(response.data.message || '获取代理列表失败')
    }
  } catch (error: any) {
    console.error('获取代理列表失败:', error)
    // ElMessage.error(error.response?.data?.message || '获取代理列表失败')
  } finally {
    loading.value = false
  }
}

// 搜索
const handleSearch = () => {
  currentPage.value = 1
  fetchAgents()
}

// 重置
const handleReset = () => {
  searchForm.value = {
    keyword: '',
    status: ''
  }
  currentPage.value = 1
  fetchAgents()
}

// 分页变化
const handlePageChange = (page: number) => {
  currentPage.value = page
  fetchAgents()
}

// 修改状态
const handleStatusChange = async (row: any) => {
  try {
    const response = await axios.put(`${API_BASE}/api/admin/users/${row.id}/status`, {
      status: row.status
    })
    if (response.data.success) {
      ElMessage.success('状态修改成功')
      fetchAgents() // 重新加载数据
    } else {
      ElMessage.error(response.data.message || '状态修改失败')
      fetchAgents() // 重新加载以恢复原状态
    }
  } catch (error: any) {
    console.error('状态修改失败:', error)
    ElMessage.error(error.response?.data?.message || '状态修改失败')
    // 恢复原状态
    row.status = row.status === 'active' ? 'disabled' : 'active'
    fetchAgents() // 重新加载以恢复原状态
  }
}

// 查看下级代理
const handleViewSubAgents = async (row: any) => {
  currentSubordinateAgentId.value = row.id
  currentSubordinateAgentName.value = row.nickname || row.email
  subordinatesDialogVisible.value = true
  await loadSubordinates()
}

// 加载下级用户列表
const loadSubordinates = async () => {
  if (!currentSubordinateAgentId.value) return
  
  subordinatesLoading.value = true
  try {
    const response = await axios.get(`${API_BASE}/api/admin/users/${currentSubordinateAgentId.value}/subordinates`)
    if (response.data.list) {
      subordinates.value = response.data.list
    } else if (Array.isArray(response.data)) {
      subordinates.value = response.data
    } else {
      subordinates.value = []
    }
  } catch (error: any) {
    console.error('获取下级用户失败:', error)
    ElMessage.error(error.response?.data?.message || '获取下级用户失败')
    subordinates.value = []
  } finally {
    subordinatesLoading.value = false
  }
}

// 查看业绩
const handleViewPerformance = (row: any) => {
  window.open(`#/agents/${row.id}/performance`, '_blank')
}

// 分配菜单权限
const handleAssignMenus = async (row: any) => {
  currentAgentId.value = row.id
  currentAgentName.value = row.nickname || row.email
  
  try {
    // 获取所有菜单（扁平列表）
    const menusResponse = await axios.get(`${API_BASE}/api/admin/menus/list`)
    if (menusResponse.data.success) {
      allMenus.value = menusResponse.data.list || []
    } else {
      // 如果失败，尝试获取树形菜单并展开
      const treeResponse = await axios.get(`${API_BASE}/api/admin/menus`)
      if (treeResponse.data.success) {
        const flattenMenus = (menus: any[]): any[] => {
          const result: any[] = []
          menus.forEach((menu: any) => {
            result.push(menu)
            if (menu.children && menu.children.length > 0) {
              result.push(...flattenMenus(menu.children))
            }
          })
          return result
        }
        allMenus.value = flattenMenus(treeResponse.data.list || [])
      }
    }
    
    // 获取当前代理的菜单权限和操作权限
    const agentMenusResponse = await axios.get(`${API_BASE}/api/admin/users/${row.id}/menus`)
    if (agentMenusResponse.data.success) {
      const existingMenuIds = agentMenusResponse.data.menuIds || []
      const existingActions = agentMenusResponse.data.actions || {}
      
      // 如果代理没有权限，使用默认权限
      if (existingMenuIds.length === 0) {
        await loadDefaultPermissions()
        checkedMenuIds.value = [...defaultCheckedMenuIds.value]
        checkedActionsMap.value = new Map(defaultCheckedActionsMap.value)
      } else {
        checkedMenuIds.value = existingMenuIds
        checkedActionsMap.value = new Map()
        for (const [menuIdStr, actionCodes] of Object.entries(existingActions)) {
          const menuId = Number(menuIdStr)
          checkedActionsMap.value.set(menuId, actionCodes as string[])
        }
      }
    } else {
      // 如果获取失败，尝试使用默认权限
      await loadDefaultPermissions()
      checkedMenuIds.value = [...defaultCheckedMenuIds.value]
      checkedActionsMap.value = new Map(defaultCheckedActionsMap.value)
    }
    
    // 加载每个菜单的操作列表
    await loadMenuActions()
    
    permissionDialogVisible.value = true
  } catch (error: any) {
    console.error('获取菜单数据失败:', error)
    ElMessage.error(error.response?.data?.message || '获取菜单数据失败')
  }
}

// 加载菜单的操作列表
const loadMenuActions = async () => {
  menuActionsMap.value = new Map()
  for (const menu of allMenus.value) {
    try {
      const response = await axios.get(`${API_BASE}/api/admin/users/menus/${menu.id}/actions`)
      if (response.data.success && response.data.list) {
        menuActionsMap.value.set(menu.id, response.data.list)
      }
    } catch (error: any) {
      console.error(`加载菜单 ${menu.id} 的操作失败:`, error)
    }
  }
}

// 切换菜单展开/收起
const toggleMenuExpanded = (menuId: number) => {
  if (expandedMenus.value.has(menuId)) {
    expandedMenus.value.delete(menuId)
  } else {
    expandedMenus.value.add(menuId)
  }
}

// 菜单选中状态变化时，自动展开操作选择
const handleMenuCheckChange = (checkedMenuIds: number[]) => {
  // 判断是默认权限对话框还是分配权限对话框
  const isDefaultDialog = defaultPermissionDialogVisible.value
  const currentActionsMap = isDefaultDialog ? defaultCheckedActionsMap.value : checkedActionsMap.value
  
  // 当菜单被选中且有操作时，自动展开
  for (const menuId of checkedMenuIds) {
    const actions = menuActionsMap.value.get(menuId)
    if (actions && actions.length > 0) {
      if (!expandedMenus.value.has(menuId)) {
        expandedMenus.value.add(menuId)
      }
    }
  }
  // 当菜单被取消选中时，收起操作选择
  for (const menuId of allMenus.value.map(m => m.id)) {
    if (!checkedMenuIds.includes(menuId)) {
      expandedMenus.value.delete(menuId)
      // 清除该菜单的操作权限
      currentActionsMap.delete(menuId)
    }
  }
}

// 保存菜单权限和操作权限
const handleSavePermission = async () => {
  if (!currentAgentId.value) return
  
  loading.value = true
  try {
    // 构建操作权限对象 {menuId: [actionCode1, actionCode2, ...]}
    const actions: Record<number, string[]> = {}
    for (const [menuId, actionCodes] of checkedActionsMap.value.entries()) {
      if (actionCodes && actionCodes.length > 0) {
        actions[menuId] = actionCodes
      }
    }
    
    const response = await axios.post(
      `${API_BASE}/api/admin/users/${currentAgentId.value}/menus`,
      { 
        menuIds: checkedMenuIds.value,
        actions: actions
      }
    )
    
    if (response.data.success) {
      ElMessage.success(response.data.message || '权限分配成功')
      permissionDialogVisible.value = false
      // 清除权限缓存，让代理重新登录或刷新页面时重新加载权限
      // 注意：这里不能直接清除，因为当前登录的是管理员，不是代理
      // 代理需要重新登录或刷新页面才能看到新的权限
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

// 加载默认权限配置
const loadDefaultPermissions = async () => {
  try {
    const response = await axios.get(`${API_BASE}/api/admin/config/get?key=agent.default.permissions`)
    if (response.data && response.data.value) {
      try {
        const defaultPerms = JSON.parse(response.data.value)
        defaultCheckedMenuIds.value = defaultPerms.menuIds || []
        defaultCheckedActionsMap.value = new Map()
        if (defaultPerms.actions) {
          for (const [menuIdStr, actionCodes] of Object.entries(defaultPerms.actions)) {
            const menuId = Number(menuIdStr)
            defaultCheckedActionsMap.value.set(menuId, actionCodes as string[])
          }
        }
      } catch (e) {
        console.error('解析默认权限配置失败:', e)
        defaultCheckedMenuIds.value = []
        defaultCheckedActionsMap.value = new Map()
      }
    } else {
      defaultCheckedMenuIds.value = []
      defaultCheckedActionsMap.value = new Map()
    }
  } catch (error: any) {
    console.error('加载默认权限配置失败:', error)
    defaultCheckedMenuIds.value = []
    defaultCheckedActionsMap.value = new Map()
  }
}

// 打开设置默认权限对话框
const handleSetDefaultPermissions = async () => {
  try {
    // 获取所有菜单（扁平列表）
    const menusResponse = await axios.get(`${API_BASE}/api/admin/menus/list`)
    if (menusResponse.data.success) {
      allMenus.value = menusResponse.data.list || []
    } else {
      // 如果失败，尝试获取树形菜单并展开
      const treeResponse = await axios.get(`${API_BASE}/api/admin/menus`)
      if (treeResponse.data.success) {
        const flattenMenus = (menus: any[]): any[] => {
          const result: any[] = []
          menus.forEach((menu: any) => {
            result.push(menu)
            if (menu.children && menu.children.length > 0) {
              result.push(...flattenMenus(menu.children))
            }
          })
          return result
        }
        allMenus.value = flattenMenus(treeResponse.data.list || [])
      }
    }
    
    // 加载默认权限配置
    await loadDefaultPermissions()
    
    // 加载每个菜单的操作列表
    await loadMenuActions()
    
    defaultPermissionDialogVisible.value = true
  } catch (error: any) {
    console.error('获取菜单数据失败:', error)
    ElMessage.error(error.response?.data?.message || '获取菜单数据失败')
  }
}

// 保存默认权限配置
const handleSaveDefaultPermissions = async () => {
  loading.value = true
  try {
    // 构建操作权限对象 {menuId: [actionCode1, actionCode2, ...]}
    const actions: Record<number, string[]> = {}
    for (const [menuId, actionCodes] of defaultCheckedActionsMap.value.entries()) {
      if (actionCodes && actionCodes.length > 0) {
        actions[menuId] = actionCodes
      }
    }
    
    // 构建默认权限配置对象
    const defaultPerms = {
      menuIds: defaultCheckedMenuIds.value,
      actions: actions
    }
    
    // 保存到系统配置
    const response = await axios.post(`${API_BASE}/api/admin/config/save`, {
      key: 'agent.default.permissions',
      value: JSON.stringify(defaultPerms),
      description: '代理默认权限配置（菜单ID列表和操作权限）'
    })
    
    if (response.data) {
      ElMessage.success('默认权限配置保存成功！新创建的代理将自动应用这些权限。')
      defaultPermissionDialogVisible.value = false
    } else {
      ElMessage.error('保存默认权限配置失败')
    }
  } catch (error: any) {
    console.error('保存默认权限配置失败:', error)
    ElMessage.error(error.response?.data?.message || '保存默认权限配置失败')
  } finally {
    loading.value = false
  }
}

// 打开编辑备注对话框
const handleEditRemark = (row: any) => {
  remarkForm.value = {
    userId: row.id,
    nickname: row.nickname || row.email,
    currentRemark: row.remark || '',
    newRemark: row.remark || '',
  }
  remarkDialogVisible.value = true
}

// 保存备注
const handleSaveRemark = async () => {
  if (!remarkForm.value.userId) return
  
  loading.value = true
  try {
    await request.post(`/admin/users/${remarkForm.value.userId}/update-remark`, {
      remark: remarkForm.value.newRemark || ''
    })
    
    ElMessage.success('备注保存成功')
    remarkDialogVisible.value = false
    fetchAgents()
  } catch (error: any) {
    console.error('保存备注失败:', error)
    ElMessage.error(error?.message || '保存失败')
  } finally {
    loading.value = false
  }
}

// 页面加载时获取数据
onMounted(() => {
  fetchAgents()
  loadDefaultPermissions() // 预加载默认权限配置
})
</script>

<template>
  <div class="agent-management">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span class="card-title">代理管理</span>
          <el-button type="primary" @click="handleSetDefaultPermissions">
            设置默认权限
          </el-button>
        </div>
      </template>

      <!-- 搜索表单 -->
      <el-form :model="searchForm" inline>
        <el-form-item label="关键词">
          <el-input
            v-model="searchForm.keyword"
            placeholder="邮箱/昵称/邀请码"
            clearable
            style="width: 200px"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="searchForm.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="正常" value="active" />
            <el-option label="禁用" value="disabled" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">搜索</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>

      <!-- 表格 -->
      <el-table :data="agents" stripe border v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="email" label="邮箱" width="180" />
        <el-table-column prop="nickname" label="昵称" width="150">
          <template #default="{ row }">
            <div style="display: flex; align-items: center; gap: 8px;">
              <span>{{ row.nickname || '-' }}</span>
              <el-button 
                link 
                type="primary" 
                size="small" 
                @click="handleEditRemark(row)"
                title="编辑备注"
              >
                备注
              </el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="150">
          <template #default="{ row }">
            {{ row.remark || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="用户类型" width="100" align="center">
          <template #default="{ row }">
            <el-tag type="warning">代理</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="myInviteCode" label="推广码" width="120" />
        <el-table-column label="下级数量" width="100" align="center">
          <template #default="{ row }">
            <el-link type="primary" @click="handleViewSubAgents(row)">
              {{ row.subordinateCount || 0 }}人
            </el-link>
          </template>
        </el-table-column>
        <el-table-column label="余额" width="150" align="right">
          <template #default="{ row }">
            <div>USDT: {{ row.usdtBalance || 0 }}</div>
            <div>CNY: {{ row.cnyBalance || 0 }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-switch
              v-model="row.status"
              active-value="active"
              inactive-value="disabled"
              @change="handleStatusChange(row)"
            />
          </template>
        </el-table-column>
        <el-table-column label="登录信息" width="150">
          <template #default="{ row }">
            <div style="font-size: 12px; color: #606266;">
              <div>IP: {{ row.lastLoginIp || '-' }}</div>
              <div>地区: {{ row.lastLoginRegion || '-' }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="注册时间" width="160" />
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="handleAssignMenus(row)">
              分配权限
            </el-button>
            <el-button link type="success" size="small" @click="handleViewPerformance(row)">
              查看业绩
            </el-button>
            <el-button link type="warning" size="small" @click="handleEditRemark(row)">
              修改备注
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <el-pagination
        v-model:current-page="currentPage"
        :page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next, jumper"
        @current-change="handlePageChange"
        style="margin-top: 20px; justify-content: flex-end;"
      />
    </el-card>

    <!-- 权限分配对话框 -->
    <el-dialog 
      v-model="permissionDialogVisible" 
      title="分配菜单权限" 
      width="500px"
    >
      <div style="margin-bottom: 15px; color: #606266;">
        为代理 <strong>{{ currentAgentName }}</strong> 分配菜单权限：
      </div>
      
      <el-checkbox-group v-model="checkedMenuIds" @change="handleMenuCheckChange">
        <div v-for="menu in allMenus" :key="menu.id" style="margin-bottom: 15px; border: 1px solid #e4e7ed; border-radius: 4px; padding: 10px;">
          <div style="display: flex; align-items: center; justify-content: space-between;">
            <el-checkbox :label="menu.id">
              {{ menu.menuName }} <span style="color: #909399;">({{ menu.menuCode }})</span>
            </el-checkbox>
            <el-button 
              v-if="(menuActionsMap.get(menu.id)?.length ?? 0) > 0 && checkedMenuIds.includes(menu.id)"
              link 
              type="primary" 
              size="small"
              @click="toggleMenuExpanded(menu.id)"
            >
              {{ expandedMenus.has(menu.id) ? '收起操作' : '展开操作' }}
            </el-button>
          </div>
          
          <!-- 操作权限选择（当菜单被选中且展开时显示） -->
          <div 
            v-if="checkedMenuIds.includes(menu.id) && expandedMenus.has(menu.id) && (menuActionsMap.get(menu.id)?.length ?? 0) > 0"
            style="margin-top: 10px; margin-left: 24px; padding: 10px; background: #f5f7fa; border-radius: 4px;"
          >
            <div style="margin-bottom: 8px; font-size: 12px; color: #606266;">选择操作权限：</div>
            <el-checkbox-group 
              :model-value="checkedActionsMap.get(menu.id) || []"
              @update:model-value="(val: string[]) => checkedActionsMap.set(menu.id, val)"
            >
              <div 
                v-for="action in menuActionsMap.get(menu.id)" 
                :key="action.id" 
                style="margin-bottom: 8px;"
              >
                <el-checkbox :label="action.actionCode">
                  {{ action.actionName }}
                </el-checkbox>
              </div>
            </el-checkbox-group>
          </div>
        </div>
      </el-checkbox-group>
      
      <template #footer>
        <el-button @click="permissionDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSavePermission" :loading="loading">保存</el-button>
      </template>
    </el-dialog>

    <!-- 设置默认权限对话框 -->
    <el-dialog 
      v-model="defaultPermissionDialogVisible" 
      title="设置代理默认权限" 
      width="500px"
    >
      <div style="margin-bottom: 15px; color: #606266;">
        设置新代理的默认菜单权限和操作权限。当创建新代理或为代理分配权限时，如果代理没有权限，将自动应用这些默认权限。
      </div>
      
      <el-checkbox-group v-model="defaultCheckedMenuIds" @change="handleMenuCheckChange">
        <div v-for="menu in allMenus" :key="menu.id" style="margin-bottom: 15px; border: 1px solid #e4e7ed; border-radius: 4px; padding: 10px;">
          <div style="display: flex; align-items: center; justify-content: space-between;">
            <el-checkbox :label="menu.id">
              {{ menu.menuName }} <span style="color: #909399;">({{ menu.menuCode }})</span>
            </el-checkbox>
            <el-button 
              v-if="(menuActionsMap.get(menu.id)?.length ?? 0) > 0 && defaultCheckedMenuIds.includes(menu.id)"
              link 
              type="primary" 
              size="small"
              @click="toggleMenuExpanded(menu.id)"
            >
              {{ expandedMenus.has(menu.id) ? '收起操作' : '展开操作' }}
            </el-button>
          </div>
          
          <!-- 操作权限选择（当菜单被选中且展开时显示） -->
          <div 
            v-if="defaultCheckedMenuIds.includes(menu.id) && expandedMenus.has(menu.id) && (menuActionsMap.get(menu.id)?.length ?? 0) > 0"
            style="margin-top: 10px; margin-left: 24px; padding: 10px; background: #f5f7fa; border-radius: 4px;"
          >
            <div style="margin-bottom: 8px; font-size: 12px; color: #606266;">选择操作权限：</div>
            <el-checkbox-group 
              :model-value="defaultCheckedActionsMap.get(menu.id) || []"
              @update:model-value="(val: string[]) => defaultCheckedActionsMap.set(menu.id, val)"
            >
              <div 
                v-for="action in menuActionsMap.get(menu.id)" 
                :key="action.id" 
                style="margin-bottom: 8px;"
              >
                <el-checkbox :label="action.actionCode">
                  {{ action.actionName }}
                </el-checkbox>
              </div>
            </el-checkbox-group>
          </div>
        </div>
      </el-checkbox-group>
      
      <template #footer>
        <el-button @click="defaultPermissionDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSaveDefaultPermissions" :loading="loading">保存默认权限</el-button>
      </template>
    </el-dialog>

    <!-- 编辑备注对话框 -->
    <el-dialog 
      v-model="remarkDialogVisible" 
      title="编辑备注" 
      width="500px"
    >
      <el-form label-width="100px">
        <el-form-item label="代理昵称">
          <span>{{ remarkForm.nickname }}</span>
        </el-form-item>
        <el-form-item label="当前备注">
          <span>{{ remarkForm.currentRemark || '-' }}</span>
        </el-form-item>
        <el-form-item label="新备注">
          <el-input
            v-model="remarkForm.newRemark"
            type="textarea"
            :rows="4"
            placeholder="请输入备注信息"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="remarkDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSaveRemark" :loading="loading">保存</el-button>
      </template>
    </el-dialog>

    <!-- 查看下级代理对话框 -->
    <el-dialog 
      v-model="subordinatesDialogVisible" 
      :title="`查看代理 ${currentSubordinateAgentName} 的下级`" 
      width="900px"
    >
      <el-table :data="subordinates" stripe border v-loading="subordinatesLoading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="email" label="邮箱" width="180" />
        <el-table-column prop="nickname" label="昵称" width="120">
          <template #default="{ row }">
            {{ row.nickname || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="用户类型" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.userType === 'agent'" type="warning">代理</el-tag>
            <el-tag v-else type="success">普通用户</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="myInviteCode" label="推广码" width="120" />
        <el-table-column label="余额" width="150" align="right">
          <template #default="{ row }">
            <div>USDT: {{ row.usdtBalance || 0 }}</div>
            <div>CNY: {{ row.cnyBalance || 0 }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.status === 'active'" type="success">正常</el-tag>
            <el-tag v-else type="danger">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="注册时间" width="180" />
      </el-table>
      
      <el-empty v-if="!subordinatesLoading && subordinates.length === 0" description="暂无下级用户" />
      
      <template #footer>
        <el-button @click="subordinatesDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.agent-management {
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
