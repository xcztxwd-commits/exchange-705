<script setup lang="ts">
import { useLocaleStore } from '@/store/locale'
import EditionSwitch from './EditionSwitch.vue'
const locale = useLocaleStore()
const reload = () => location.reload()
</script>
<template><section class="advanced-error" role="alert"><h1>{{ locale.text('高级页面加载失败', 'Advanced page failed to load') }}</h1><p>{{ locale.text('业务请求未重发。可重试或返回经典版。', 'No business request was replayed. Retry or return to classic.') }}</p><button type="button" @click="reload">{{ locale.text('重试', 'Retry') }}</button><EditionSwitch /></section></template>
<style scoped>.advanced-error{padding:32px 16px;background:#f7f8fa;min-height:80vh;font:14px/1.5 system-ui}.advanced-error h1{font-size:18px}.advanced-error button{min-height:44px;padding:8px 16px;background:#fff;border:1px solid #e9edef;border-radius:8px;cursor:pointer}</style>
