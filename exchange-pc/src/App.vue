<template>
  <router-view />
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, watch } from 'vue'
import { useAuthStore } from '@/store/auth'
import { useLocaleStore } from '@/store/locale'
import request from '@/utils/request'

const auth = useAuthStore()
const localeStore = useLocaleStore()

// 应用启动时自动加载语言（自动检测浏览器语言）
localeStore.loadLocale()

// 心跳定时器
let heartbeatTimer: number | null = null

// 发送心跳请求
const sendHeartbeat = async () => {
  if (!auth.token) {
    return
  }
  
  try {
    await request.post('/auth/heartbeat')
  } catch (error) {
    // 心跳失败不影响，由request拦截器处理token失效
    console.error('心跳请求失败:', error)
  }
}

// 启动心跳机制（每30秒发送一次）
const startHeartbeat = () => {
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer)
  }
  
  // 立即发送一次
  sendHeartbeat()
  
  // 每30秒发送一次心跳
  heartbeatTimer = window.setInterval(() => {
    sendHeartbeat()
  }, 30000) // 30秒
}

// 停止心跳机制
const stopHeartbeat = () => {
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer)
    heartbeatTimer = null
  }
}

// 监听token变化，启动或停止心跳
watch(() => auth.token, (newToken) => {
  if (newToken) {
    startHeartbeat()
  } else {
    stopHeartbeat()
  }
}, { immediate: true })

onMounted(() => {
  // 加载用户信息
  auth.load()
})

onUnmounted(() => {
  stopHeartbeat()
})
</script>

