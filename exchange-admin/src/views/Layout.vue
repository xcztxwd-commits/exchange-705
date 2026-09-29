<script setup lang="ts">
import { ref, onMounted, computed, watch, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { Expand, Fold, User, SwitchButton, Bell, Setting } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import { startReadPolling } from '@/utils/readPolling'
import { access, can, canRoute, loadAccess } from '@/utils/access'
import { getAudioUrl } from '@/utils/audioUrl'
import AdminSettings from './AdminSettings.vue'
import AgentSettings from './AgentSettings.vue'
import SupportNotifications from '@/components/SupportNotifications.vue'


const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
auth.load()

const isCollapse = ref(false)
const menuItems = computed(() => access.menus.map(m => ({ ...m, title: m.menuName })))
const menuGroups = computed(() => access.groups.map(g => ({ ...g, children: menuItems.value.filter(m => m.parentId === g.id) })).filter(g => g.children.length))
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
let pollingActive = false
let stopPendingPolling: (() => void) | undefined
let stopOnlinePolling: (() => void) | undefined
let lastCounts = { deposit: 0, withdraw: 0, kyc: 0, order: 0 }

// 加载待处理消息数量
const loadPendingCounts = async () => {
  const token = auth.token
  try {
    const res: any = await request.get('/admin/notification/pending-counts')
    if (!pollingActive || token !== auth.token) return false
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
      return true
    }
  } catch (e: any) {
    console.error('加载待处理消息数量失败:', e)
  }
  return false
}

// 加载在线用户数
const loadOnlineUserCount = async () => {
  const token = auth.token
  try {
    const res: any = await request.get('/admin/users/online-count')
    if (!pollingActive || token !== auth.token) return false
    if (res && res.success) {
      onlineUserCount.value = res.count || 0
      return true
    }
  } catch (e: any) {
    console.error('加载在线用户数失败:', e)
  }
  return false
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
    const res: any = await request.get('/admin/notification/sounds')
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
  if (!canRoute(({ deposit: "/deposit-review", withdraw: "/withdraw-review", kyc: "/kyc-review", order: "/orders" } as Record<string,string>)[type] || "")) return
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

const loadMenus = async () => {
  loadingMenus.value = true
  try { await loadAccess(true) } finally { loadingMenus.value = false }
}
let permissionTimer: number | null = null
onMounted(() => {
  loadMenus().catch(() => router.replace('/forbidden'))
  permissionTimer = window.setInterval(async () => {
    try { await loadAccess(true) } catch { /* Failed refresh closes access rather than granting defaults. */ }
    if (!canRoute(route.path)) router.replace(access.menus[0]?.path || '/forbidden')
  }, 30000)
  loadSoundConfig()
  pollingActive = true
  stopPendingPolling = startReadPolling(loadPendingCounts)
  stopOnlinePolling = startReadPolling(loadOnlineUserCount)
})

onUnmounted(() => {
  if (permissionTimer !== null) clearInterval(permissionTimer)
  pollingActive = false
  stopPendingPolling?.()
  stopOnlinePolling?.()
})

const onLogout = () => {
  pollingActive = false
  stopPendingPolling?.()
  stopOnlinePolling?.()
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
    <el-aside :width="isCollapse ? '64px' : '228px'" class="layout-aside">
      <div class="logo-box">
        <div class="logo-circle">
          <el-icon><DataLine /></el-icon>
        </div>
        <span v-if="!isCollapse" class="logo-title">Exchange Admin</span>
      </div>
      
      <el-menu
        :default-active="route.path"
        :default-openeds="menuGroups.filter(g => g.children.some((m: any) => m.path === route.path)).map(g => g.menuCode)"
        :collapse="isCollapse"
        :router="true"
        class="menu"
      >
        <el-sub-menu v-for="group in menuGroups" :key="group.id" :index="group.menuCode">
          <template #title><el-icon><component :is="group.icon || 'Folder'" /></el-icon><span>{{ group.menuName }}</span></template>
          <el-menu-item v-for="item in group.children" :key="item.path" :index="item.path">
            <span>{{ item.title }}</span>
          </el-menu-item>
        </el-sub-menu>
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
            
          <SupportNotifications admin :enabled="can('support:view')" />
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
                <el-dropdown-item v-permission="'session:self'"
                  v-if="auth.user?.userType === 'admin' || auth.user?.isSuperAdmin"
                  @click="openAdminSettings"
                >
                  <el-icon><Setting /></el-icon>
                  管理员设置
                </el-dropdown-item>
                <el-dropdown-item v-permission="'session:self'"
                  v-if="auth.user?.userType === 'agent'"
                  @click="openAgentSettings"
                >
                  <el-icon><Setting /></el-icon>
                  代理设置
                </el-dropdown-item>
                <el-dropdown-item v-permission="'session:self'" @click="onLogout" divided>
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

.menu :deep(.el-sub-menu__title) { color: #d6e0ec; }
.menu :deep(.el-menu) { background: #263445; }
.menu :deep(.el-sub-menu__title:hover) { background: #263445; color: #fff; }

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
