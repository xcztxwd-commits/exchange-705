<script setup lang="ts">
import { useLocaleStore } from '@/store/locale'
const locale = useLocaleStore()
</script>
<template><div class="advanced-loading" role="status">{{ locale.text('正在加载高级版…', 'Loading advanced edition…') }}</div></template>
<style scoped>.advanced-loading{padding:40px 16px;background:#f7f8fa;color:#707780;min-height:80vh;font:14px/1.5 system-ui}</style>
