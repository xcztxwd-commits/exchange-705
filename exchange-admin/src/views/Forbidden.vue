<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { useAuthStore } from '@/store/auth'
import { useRouter, useRoute } from 'vue-router'
import { access, loadAccess, canRoute } from '@/utils/access'
import { startReadPolling } from '@/utils/readPolling'
const auth = useAuthStore(), router = useRouter(), route = useRoute()
const loading = ref(false)
const failed = computed(() => !!access.error || !access.loaded)
let active = true
let stopPolling: (() => void) | undefined
const logout = () => {
  const target = auth.isControl ? '/access-ended' : '/login'
  auth.logout()
  void router.replace(target)
}
const retry = async () => {
  if (loading.value) return false
  loading.value = true
  const token = auth.token
  try {
    await loadAccess(true)
    if (!active || token !== auth.token) return false
    const intended = route.query.redirect
    const target = typeof intended === 'string' && intended.startsWith('/') && !intended.startsWith('//') && canRoute(router.resolve(intended).path)
      ? intended : access.menus[0]?.path || '/forbidden'
    await router.replace(target)
    return access.loaded
  } catch { return false }
  finally { loading.value = false }
}
onMounted(() => {
  // Retry only failed reads; a confirmed empty permission set stays denied.
  stopPolling = startReadPolling(() => failed.value ? retry() : Promise.resolve(true))
})
onUnmounted(() => { active = false; stopPolling?.() })
</script>
<template>
  <el-result icon="warning" :title="failed ? '后台服务暂时不可用' : '暂无访问权限'"
    :sub-title="failed ? `${access.error || '暂时无法确认访问权限'}；正在自动重试，无需退出登录。` : '请联系管理员分配菜单和操作权限。'">
    <template #extra>
      <el-button v-permission="'session:self'" type="primary" :loading="loading" @click="retry">重新加载权限</el-button>
      <el-button v-permission="'session:close'" @click="logout">退出登录</el-button>
    </template>
  </el-result>
</template>
