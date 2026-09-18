<script setup lang="ts">
import { ref, onMounted, computed, watch, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { Expand, Fold, User, SwitchButton, Bell, Setting } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import { getAudioUrl } from '@/utils/audioUrl'
import AdminSettings from './AdminSettings.vue'
import AgentSettings from './AgentSettings.vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
auth.load()

const isCollapse = ref(false)
const menuItems = ref<any[]>([])
const loadingMenus = ref(false)

// 待处理消息数量
const pendingCounts = ref({
  deposit: 0,
  withdraw: 0,
  kyc: 0,
  order: 0
})

// 在线用户数
const onlineUserCount = ref(0)

// 提示音配置
const soundConfig = ref({
  withdrawSound: '',
  depositSound: '',
  kycSound: '',
  orderSound: ''
})

// 实时更新定时器
let updateTimer: number | null = null
let lastCounts = { deposit: 0, withdraw: 0, kyc: 0, order: 0 }

// 加载待处理消息数量
const loadPendingCounts = async () => {
  try {
    const res: any = await request.get('/admin/notification/pending-counts')
    if (res && res.success && res.data) {
      const newCounts = res.data
      
      // 检查是否有新增消息，如果有则播放提示音
      // 只有设置了提示音URL（不为空）才播放，未设置表示关闭提示音
      if (newCounts.withdraw > lastCounts.withdraw && soundConfig.value.withdrawSound && soundConfig.value.withdrawSound.trim() !== '') {
        playSound(soundConfig.value.withdrawSound)
      }
      if (newCounts.deposit > lastCounts.deposit && soundConfig.value.depositSound && soundConfig.value.depositSound.trim() !== '') {
        playSound(soundConfig.value.depositSound)
      }
      if (newCounts.kyc > lastCounts.kyc && soundConfig.value.kycSound && soundConfig.value.kycSound.trim() !== '') {
        playSound(soundConfig.value.kycSound)
      }
      if (newCounts.order > lastCounts.order && soundConfig.value.orderSound && soundConfig.value.orderSound.trim() !== '') {
        playSound(soundConfig.value.orderSound)
      }
      
      lastCounts = { ...newCounts }
      pendingCounts.value = newCounts
    }
  } catch (e: any) {
    console.error('加载待处理消息数量失败:', e)
  }
}

// 加载在线用户数
const loadOnlineUserCount = async () => {
  try {
    const res: any = await request.get('/admin/users/online-count')
    if (res && res.success) {
      onlineUserCount.value = res.count || 0
    }
  } catch (e: any) {
    console.error('加载在线用户数失败:', e)
  }
}

// 播放提示音
// 如果设置了提示音URL（不为空），表示开启提示音，会播放
// 如果未设置提示音URL（为空），表示关闭提示音，不会播放
const playSound = (soundUrl: string) => {
  // 未设置提示音URL或为空，表示关闭提示音，不播放
  if (!soundUrl || soundUrl.trim() === '') {
    return
  }
  try {
    // 使用工具函数获取完整的音频URL（生产环境需要完整URL）
    const fullAudioUrl = getAudioUrl(soundUrl)
    const audio = new Audio(fullAudioUrl)
    // 设置音量（可选，避免声音过大）
    audio.volume = 0.7
    audio.play().catch(err => {
      // 忽略用户未交互的错误（浏览器安全策略）
      if (err.name !== 'NotAllowedError') {
        console.error('播放提示音失败:', err, 'URL:', fullAudioUrl)
      }
    })
  } catch (e) {
    console.error('播放提示音失败:', e)
  }
}

// 加载提示音配置
const loadSoundConfig = async () => {
  try {
    const res: any = await request.get('/admin/config/list')
    if (Array.isArray(res)) {
      res.forEach((item: any) => {
        if (item.configKey === 'notification.sound.withdraw') {
          soundConfig.value.withdrawSound = item.configValue || ''
        }
        if (item.configKey === 'notification.sound.deposit') {
          soundConfig.value.depositSound = item.configValue || ''
        }
        if (item.configKey === 'notification.sound.kyc') {
          soundConfig.value.kycSound = item.configValue || ''
        }
        if (item.configKey === 'notification.sound.order') {
          soundConfig.value.orderSound = item.configValue || ''
        }
      })
    }
  } catch (e: any) {
    console.error('加载提示音配置失败:', e)
  }
}

// 跳转到对应页面
const goToPage = (type: string) => {
  if (type === 'deposit') {
    router.push('/deposit-review')
  } else if (type === 'withdraw') {
    router.push('/withdraw-review')
  } else if (type === 'kyc') {
    router.push('/kyc-review')
  } else if (type === 'order') {
    router.push('/orders')
  }
}

// 所有菜单配置（用于映射）
const allMenuConfig: Record<string, any> = {
  '/dashboard': { path: '/dashboard', icon: 'DataLine', title: '仪表盘', menuCode: 'dashboard' },
  '/users': { path: '/users', icon: 'User', title: '用户管理', menuCode: 'users' },
  '/symbols': { path: '/symbols', icon: 'Coin', title: '币种管理', menuCode: 'symbols' },
  '/ai-control': { path: '/ai-control', icon: 'Setting', title: 'AI控盘', menuCode: 'ai_control' },
  '/durations': { path: '/durations', icon: 'Timer', title: '期限设置', menuCode: 'durations' },
  '/orders': { path: '/orders', icon: 'Document', title: '订单管理', menuCode: 'orders' },
  '/deposit-settings': { path: '/deposit-settings', icon: 'Wallet', title: '充值设置', menuCode: 'deposit_settings' },
  '/deposit-review': { path: '/deposit-review', icon: 'Check', title: '充值审核', menuCode: 'deposit_review' },
  '/withdraw-review': { path: '/withdraw-review', icon: 'Money', title: '提现审核', menuCode: 'withdraw_review' },
  '/loan-settings': { path: '/loan-settings', icon: 'Wallet', title: '贷款设置', menuCode: 'loan_settings' },
  '/loan-review': { path: '/loan-review', icon: 'Document', title: '贷款审核', menuCode: 'loan_review' },
  '/loan-personal-info-review': { path: '/loan-personal-info-review', icon: 'UserFilled', title: '个人信息审核', menuCode: 'loan_personal_info_review' },
  '/kyc-review': { path: '/kyc-review', icon: 'DocumentChecked', title: '实名审核', menuCode: 'kyc_review' },
  '/financial-products': { path: '/financial-products', icon: 'Coin', title: '理财产品', menuCode: 'financial_products' },
  '/financial-orders': { path: '/financial-orders', icon: 'Document', title: '理财订单', menuCode: 'financial_orders' },
  '/announcement': { path: '/announcement', icon: 'Bell', title: '公告管理', menuCode: 'announcement' },
  '/roles': { path: '/roles', icon: 'Unlock', title: '角色管理', menuCode: 'roles' },
  '/agents': { path: '/agents', icon: 'Avatar', title: '代理管理', menuCode: 'agents' },
  '/admin-list': { path: '/admin-list', icon: 'UserFilled', title: '管理员列表', menuCode: 'admin_list' },
  '/settings': { path: '/settings', icon: 'Setting', title: '系统配置', menuCode: 'settings' },
  '/operation-log': { path: '/operation-log', icon: 'Document', title: '操作日志', menuCode: 'operation_log' },
  '/statistics': { path: '/statistics', icon: 'DataAnalysis', title: '数据统计', menuCode: 'statistics' },
}

// 加载菜单
const loadMenus = async () => {
  loadingMenus.value = true
  try {
    const user = auth.user
    console.log('当前登录用户信息:', user)
    const isSuperAdmin = user?.isSuperAdmin || user?.role === 'super_admin'
    const isAgent = user?.userType === 'agent'
    console.log('用户类型判断 - isSuperAdmin:', isSuperAdmin, 'isAgent:', isAgent)

    if (isSuperAdmin) {
      // 超级管理员显示所有菜单
      console.log('加载超级管理员菜单，菜单数量:', Object.values(allMenuConfig).length)
      menuItems.value = Object.values(allMenuConfig)
    } else if (isAgent) {
      // 代理用户：获取分配的菜单
      console.log('开始加载代理菜单...')
      try {
        const response: any = await request.get('/admin/agent-menus/current')
        console.log('代理菜单API响应:', response)
        if (response && response.success) {
          const agentMenus = response.list || []
          console.log('代理菜单数据（原始）:', agentMenus, '菜单数量:', agentMenus.length)
          // 将菜单树转换为扁平列表，并匹配配置
          const flattenMenus = (menus: any[]): any[] => {
            const result: any[] = []
            menus.forEach((menu: any) => {
              // 只处理类型为 menu 的菜单项
              if (menu.menuType === 'menu' && menu.path) {
                // 先尝试通过路径精确匹配
                let config = Object.values(allMenuConfig).find((m: any) => 
                  m.path === menu.path
                )
                // 如果路径不匹配，尝试通过 menuCode 匹配（处理下划线和连字符的差异）
                if (!config && menu.menuCode) {
                  config = Object.values(allMenuConfig).find((m: any) => {
                    const menuCode = menu.menuCode || ''
                    const mCode = m.menuCode || ''
                    // 支持下划线和连字符互转匹配
                    return mCode === menuCode || 
                           mCode.replace(/_/g, '-') === menuCode || 
                           mCode === menuCode.replace(/_/g, '-') ||
                           mCode.replace(/-/g, '_') === menuCode ||
                           mCode === menuCode.replace(/-/g, '_')
                  })
                }
                if (config) {
                  result.push(config)
                } else {
                  // 如果没有找到配置，使用菜单自身的数据，并确保路径正确
                  result.push({
                    path: menu.path,
                    icon: menu.icon || 'Menu',
                    title: menu.menuName || menu.menu_name || '未知菜单',
                    menuCode: menu.menuCode || menu.menu_code
                  })
                }
              }
              // 递归处理子菜单
              if (menu.children && menu.children.length > 0) {
                result.push(...flattenMenus(menu.children))
              }
            })
            return result
          }
          const flatMenus = flattenMenus(agentMenus)
          console.log('处理后的菜单（扁平化）:', flatMenus, '菜单数量:', flatMenus.length)
          menuItems.value = flatMenus.length > 0 ? flatMenus : []
          if (flatMenus.length === 0) {
            console.warn('⚠️ 代理菜单处理后的数量为0，可能是菜单匹配失败')
            ElMessage.warning('代理用户没有分配的菜单权限，请联系管理员分配菜单')
          }
        } else {
          console.warn('⚠️ API响应中没有菜单数据 - response:', response)
          // 如果没有菜单权限，显示空列表
          menuItems.value = []
          ElMessage.warning('代理用户没有分配的菜单权限，请联系管理员分配菜单')
        }
      } catch (error: any) {
        console.error('❌ 获取代理菜单失败:', error)
        console.error('错误详情:', error?.response || error)
        ElMessage.error('获取菜单失败: ' + (error?.message || error?.response?.data?.message || '未知错误'))
        menuItems.value = []
      }
    } else {
      // 普通管理员：显示默认菜单
      menuItems.value = Object.values(allMenuConfig)
    }
  } catch (error) {
    console.error('加载菜单失败:', error)
    menuItems.value = Object.values(allMenuConfig) // 失败时显示所有菜单
  } finally {
    loadingMenus.value = false
  }
}

onMounted(() => {
  loadMenus()
  loadSoundConfig()
  loadPendingCounts()
  loadOnlineUserCount()
  // 每5秒更新一次消息数量和在线用户数
  updateTimer = window.setInterval(() => {
    loadPendingCounts()
    loadOnlineUserCount()
  }, 5000)
})

onUnmounted(() => {
  if (updateTimer !== null) {
    clearInterval(updateTimer)
  }
})

// 监听用户变化，重新加载菜单（例如登录后）
watch(() => auth.user, (newUser, oldUser) => {
  console.log('[Layout] 用户信息变化:', { old: oldUser, new: newUser })
  if (newUser && newUser !== oldUser) {
    loadMenus()
  }
}, { deep: true, immediate: false })

// 监听路由变化，确保菜单加载
watch(() => route.path, () => {
  // 如果是代理用户且菜单为空，重新加载
  if (auth.user?.userType === 'agent' && menuItems.value.length === 0) {
    console.log('[Layout] 路由变化，重新加载菜单')
    loadMenus()
  }
})

const onLogout = () => {
  auth.logout()
  router.replace('/login')
}

const toggleCollapse = () => {
  isCollapse.value = !isCollapse.value
}

// 管理员设置对话框
const adminSettingsVisible = ref(false)
// 代理设置对话框
const agentSettingsVisible = ref(false)

const openAdminSettings = () => {
  adminSettingsVisible.value = true
}

const openAgentSettings = () => {
  agentSettingsVisible.value = true
}

const handleSettingsUpdated = () => {
  // 设置更新后，重新加载用户信息
  auth.load()
  ElMessage.success('设置已更新')
}
</script>

<template>
  <el-container class="layout-container">
    <!-- 侧边栏 -->
    <el-aside :width="isCollapse ? '64px' : '200px'" class="layout-aside">
      <div class="logo-box">
        <div class="logo-circle">
          <el-icon><DataLine /></el-icon>
        </div>
        <span v-if="!isCollapse" class="logo-title">Exchange Admin</span>
      </div>
      
      <el-menu
        :default-active="route.path"
        :collapse="isCollapse"
        :router="true"
        class="menu"
      >
        <el-menu-item
          v-for="item in menuItems"
          :key="item.path"
          :index="item.path"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <template #title>{{ item.title }}</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <!-- 主体 -->
    <el-container>
      <!-- 顶部导航 -->
      <el-header class="layout-header">
        <div class="header-left">
          <el-icon class="collapse-btn" @click="toggleCollapse">
            <Expand v-if="isCollapse" />
            <Fold v-else />
          </el-icon>
        </div>
        
        <div class="header-right">
          <!-- 在线用户数 -->
          <div class="online-count-area">
            <span class="online-text">在线({{ onlineUserCount }})</span>
          </div>
          
          <!-- 待处理消息 -->
          <div class="notification-area">
            <el-icon class="notification-icon"><Bell /></el-icon>
            <span class="notification-text">待处理消息:</span>
            <span 
              class="notification-item" 
              :class="{ 'has-pending': pendingCounts.kyc > 0 }"
              @click="goToPage('kyc')"
            >
              实名({{ pendingCounts.kyc }})
            </span>
            <span 
              class="notification-item" 
              :class="{ 'has-pending': pendingCounts.deposit > 0 }"
              @click="goToPage('deposit')"
            >
              充值({{ pendingCounts.deposit }})
            </span>
            <span 
              class="notification-item" 
              :class="{ 'has-pending': pendingCounts.withdraw > 0 }"
              @click="goToPage('withdraw')"
            >
              提现({{ pendingCounts.withdraw }})
            </span>
            <span 
              class="notification-item" 
              :class="{ 'has-pending': pendingCounts.order > 0 }"
              @click="goToPage('order')"
            >
              订单({{ pendingCounts.order }})
            </span>
          </div>
          
          <el-dropdown>
            <div class="user-info">
              <el-icon><User /></el-icon>
              <span class="username">{{ auth.user?.userType === 'agent' ? (auth.user?.email || auth.user?.account) : (auth.user?.account || 'admin') }}</span>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item 
                  v-if="auth.user?.userType === 'admin' || auth.user?.isSuperAdmin"
                  @click="openAdminSettings"
                >
                  <el-icon><Setting /></el-icon>
                  管理员设置
                </el-dropdown-item>
                <el-dropdown-item 
                  v-if="auth.user?.userType === 'agent'"
                  @click="openAgentSettings"
                >
                  <el-icon><Setting /></el-icon>
                  代理设置
                </el-dropdown-item>
                <el-dropdown-item @click="onLogout" divided>
                  <el-icon><SwitchButton /></el-icon>
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <!-- 内容区 -->
      <el-main class="layout-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>

  <!-- 管理员设置对话框 -->
  <AdminSettings 
    v-if="auth.user?.userType === 'admin' || auth.user?.isSuperAdmin"
    v-model="adminSettingsVisible"
    @updated="handleSettingsUpdated"
  />
  
  <!-- 代理设置对话框 -->
  <AgentSettings 
    v-if="auth.user?.userType === 'agent'"
    v-model="agentSettingsVisible"
    @updated="handleSettingsUpdated"
  />
</template>

<style scoped>
.layout-container {
  height: 100vh;
}

.layout-aside {
  background: #304156;
  transition: width 0.3s;
  overflow-x: hidden;
}

.logo-box {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  background: #2b3a4a;
  border-bottom: 1px solid #1f2d3d;
}

.logo-circle {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: linear-gradient(135deg, #85bd00, #6fa200);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 20px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
}

.logo-title {
  font-size: 16px;
  font-weight: 600;
  color: #fff;
}

.menu {
  border: none;
  background: #304156;
}

.menu :deep(.el-menu-item) {
  color: #bfcbd9;
}

.menu :deep(.el-menu-item:hover) {
  background: #263445 !important;
  color: #fff;
}

.menu :deep(.el-menu-item.is-active) {
  background: #85bd00 !important;
  color: #fff;
}

.layout-header {
  background: #fff;
  border-bottom: 1px solid #e6e6e6;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}

.header-left {
  display: flex;
  align-items: center;
}

.collapse-btn {
  font-size: 20px;
  cursor: pointer;
  color: #5a5e66;
  transition: color 0.3s;
}

.collapse-btn:hover {
  color: #85bd00;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 20px;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 8px 12px;
  border-radius: 4px;
  transition: background 0.3s;
}

.user-info:hover {
  background: #f5f7fa;
}

.username {
  font-size: 14px;
  color: #303133;
}

.online-count-area {
  display: flex;
  align-items: center;
  padding: 8px 16px;
  background: #e6f7ff;
  border-radius: 4px;
  margin-right: 20px;
}

.online-text {
  font-size: 14px;
  color: #1890ff;
  font-weight: 600;
}

.notification-area {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  background: #f5f7fa;
  border-radius: 4px;
  margin-right: 20px;
}

.notification-icon {
  font-size: 18px;
  color: #909399;
}

.notification-text {
  font-size: 14px;
  color: #606266;
  margin-right: 4px;
}

.notification-item {
  font-size: 14px;
  color: #67c23a;
  cursor: pointer;
  padding: 2px 8px;
  border-radius: 3px;
  transition: all 0.3s;
}

.notification-item:hover {
  background: #ecf5ff;
  color: #409eff;
}

.notification-item.has-pending {
  color: #f56c6c;
  font-weight: 600;
}

.notification-item.has-pending:hover {
  color: #f56c6c;
  background: #fef0f0;
}

.layout-main {
  background: #f0f2f5;
  padding: 20px;
  overflow-y: auto;
}
</style>
