<script setup lang="ts">
import { useRoute } from 'vue-router'
import { useLocaleStore } from '@/store/locale'

const route = useRoute()
const locale = useLocaleStore()
</script>

<template>
  <nav class="auth-mode-switch" :aria-label="`${locale.t('login')} / ${locale.t('register')}`">
    <RouterLink :to="{ path: '/login', query: route.query }" active-class="selected" class="auth-mode">
      {{ locale.t('login') }}
    </RouterLink>
    <RouterLink :to="{ path: '/register', query: route.query }" active-class="selected" class="auth-mode">
      {{ locale.t('register') }}
    </RouterLink>
  </nav>
</template>

<style scoped>
.auth-mode-switch {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px;
  padding: 4px;
  margin-bottom: 24px;
  border: 1px solid #e6e8ed;
  border-radius: 14px;
  background: #f5f6f3;
}
.auth-mode {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  padding: 10px 8px;
  border-radius: 10px;
  color: #666;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.4;
  text-align: center;
  overflow-wrap: anywhere;
}
.auth-mode.selected {
  background: #85bd00;
  color: #203400;
  font-weight: 700;
  box-shadow: 0 2px 6px rgba(53, 79, 0, .16);
}
.auth-mode:not(.selected):hover { background: #e9eddc; color: #203400; }
.auth-mode:focus-visible { outline: 2px solid #406000; outline-offset: 2px; }
</style>
