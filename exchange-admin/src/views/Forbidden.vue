<script setup lang="ts">
import { useAuthStore } from '@/store/auth'
import { useRouter } from 'vue-router'
const auth = useAuthStore(), router = useRouter()
const logout = () => { auth.logout(); router.replace('/login') }
</script>
<template>
  <el-result icon="warning" title="暂无访问权限" sub-title="请联系管理员分配菜单和操作权限。权限加载失败时也会禁止访问。">
    <template #extra><el-button v-permission="'session:self'" @click="logout">退出登录</el-button></template>
  </el-result>
</template>
