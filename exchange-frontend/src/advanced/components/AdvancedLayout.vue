<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useLocaleStore } from '@/store/locale'
import AdvancedNav from './AdvancedNav.vue'
const props = defineProps<{ title?: string; back?: boolean; nav?: boolean }>()
const route = useRoute(), router = useRouter(), locale = useLocaleStore()
const showNav = computed(() => props.nav ?? ['/home', '/orders', '/trade', '/explore', '/profile'].includes(route.path))
function goBack() { if (window.history.state?.back) router.back(); else void router.replace('/profile') }
</script>
<template>
  <main class="advanced-ui advanced-layout" :class="{ 'with-nav': showNav }">
    <header v-if="title" class="advanced-header">
      <button v-if="back" type="button" @click="goBack" class="back" :aria-label="locale.text('返回', 'Back')"><span class="ui-chevron ui-chevron--left" aria-hidden="true"></span></button>
      <h1>{{ title }}</h1><slot name="actions" />
    </header>
    <slot />
    <AdvancedNav v-if="showNav" />
  </main>
</template>
<style scoped>
.advanced-ui{--advanced-accent:#7557b7;--advanced-text:#20252d;--advanced-muted:#707780;--advanced-line:#e9edef;--advanced-surface:#fff;--advanced-bg:#fff;--advanced-up:#209477;--advanced-down:#d25564;color:var(--advanced-text);background:var(--advanced-bg);font:14px/1.45 'Noto Sans SC','PingFang SC','Microsoft YaHei',system-ui,sans-serif;min-height:100dvh;width:100%;overflow-wrap:anywhere;font-variant-numeric:tabular-nums}.advanced-layout{padding:0 16px max(24px,env(safe-area-inset-bottom));box-sizing:border-box}.with-nav{padding-bottom:calc(92px + env(safe-area-inset-bottom))}.advanced-header{height:56px;display:flex;align-items:center;gap:12px;border-bottom:1px solid var(--advanced-line);margin:0 -16px 16px;padding:0 16px;background:var(--advanced-surface)}.advanced-header h1{font-size:17px;font-weight:500;line-height:1.45;flex:1;min-width:0;margin:0}.back{min-width:44px;height:44px;border:0;background:transparent;color:var(--advanced-text);margin-left:-10px;cursor:pointer}.back:focus-visible{outline:2px solid var(--advanced-accent);outline-offset:1px}
</style>
