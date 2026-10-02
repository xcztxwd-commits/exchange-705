<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { useAuthStore } from '@/store/auth'
import { useRouter } from 'vue-router'
import { access, loadAccess } from '@/utils/access'
import { startReadPolling } from '@/utils/readPolling'
const auth = useAuthStore(), router = useRouter()
const loading = ref(false)
const failed = computed(() => !access.loaded)
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
    const first = access.menus[0]?.path
    if (first) await router.replace(first)
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
  <el-result icon="warning" :title="failed ? '权限加载暂时失败' : '暂无访问权限'"
    :sub-title="failed ? '网络或服务暂时异常，正在自动重试。恢复前暂停访问，无需退出登录。' : '请联系管理员分配菜单和操作权限。'">
    <template #extra>
      <el-button v-permission="'session:self'" type="primary" :loading="loading" @click="retry">重新加载权限</el-button>
      <el-button v-permission="'session:close'" @click="logout">退出登录</el-button>
    </template>
  </el-result>
</template>
