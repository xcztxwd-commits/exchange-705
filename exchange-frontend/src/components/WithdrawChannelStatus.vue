<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useLocaleStore } from '@/store/locale'
defineProps<{ ready: boolean; available: boolean; error: boolean }>()
defineEmits<{ retry: [] }>()
const router = useRouter()
const localeStore = useLocaleStore()
</script>

<template>
  <section v-if="!ready || !available" class="withdraw-channel-status" data-testid="withdraw-channel-status">
    <template v-if="error">
      <p role="alert">{{ localeStore.text('出金方式加载失败，请重试', 'Unable to load withdrawal methods. Please retry.') }}</p>
      <button type="button" @click="$emit('retry')">{{ localeStore.text('重试', 'Retry') }}</button>
    </template>
    <p v-else-if="!ready" role="status">{{ localeStore.t('loading') }}</p>
    <template v-else>
      <svg aria-hidden="true" width="56" height="56" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
        <path d="M4 13v-1a8 8 0 0 1 16 0v1M20 17v1a3 3 0 0 1-3 3h-5" />
        <rect x="2" y="11" width="4" height="7" rx="2" />
        <rect x="18" y="11" width="4" height="7" rx="2" />
      </svg>
      <p role="status">{{ localeStore.text('出金请联系客服', 'Please contact customer service for withdrawals') }}</p>
      <button type="button" @click="router.push('/customer-service')">{{ localeStore.t('contactCustomerService') }}</button>
    </template>
  </section>
</template>

<style scoped>
.withdraw-channel-status { padding: 40px 20px; margin-bottom: 16px; text-align: center; color: #888; border: 1px solid #8883; border-radius: 12px; }
svg { display: block; margin: 0 auto; color: #8cc63f; }
p { margin: 16px 0; }
button { padding: 12px 24px; background: #8cc63f; color: #fff; border: 0; border-radius: 8px; cursor: pointer; font: inherit; }
</style>
