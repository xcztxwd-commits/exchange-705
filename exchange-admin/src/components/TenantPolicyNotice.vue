<script setup lang="ts">
import type { PolicySnapshot } from '@/utils/tenantPolicies'
defineProps<{ snapshot: PolicySnapshot | null; error?: string }>()
</script>

<template>
  <el-alert v-if="error" :title="`权限信息加载失败，编辑已禁用：${error}`" type="error" :closable="false" />
  <el-alert v-else-if="!snapshot" title="正在确认编辑权限，暂不可编辑" type="info" :closable="false" />
</template>
