<script setup lang="ts">
import { ref, watch } from 'vue'
import request from '@/utils/request'
import { useRoute, useRouter } from 'vue-router'
import { useUiEditionStore } from '@/store/uiEdition'
import { useLocaleStore } from '@/store/locale'
import { pendingAccountWrites } from '@/utils/accountRequests'
const ui = useUiEditionStore(), locale = useLocaleStore(), router = useRouter(), route = useRoute()
const text = (zh: string, en: string) => locale.text(zh, en)
const advancedEnabled = ref(false)
let generation = 0
async function refreshEntry() {
  const current = ++generation
  advancedEnabled.value = false
  if (!ui.key) return false
  try {
    const data = await request.get('/user/ui-edition') as unknown as { advancedEnabled: boolean }
    if (current === generation) advancedEnabled.value = data.advancedEnabled === true
  } catch { /* Keep the entry hidden when configuration cannot be confirmed. */ }
  return current === generation && advancedEnabled.value
}
watch(() => ui.key, () => { void refreshEntry() }, { immediate: true })
async function change() {
  const next = ui.edition === 'classic' ? 'advanced' : 'classic'
  if (ui.blocked) return
  if (next === 'advanced' && !await refreshEntry()) return
  // Switches are on Profile, not transaction forms. Keep a defensive draft check for fallback views.
  const hasDraft = [...document.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>('input:not([type=hidden]), textarea')].some(input => input.type !== 'checkbox' && input.type !== 'radio' && input.value !== input.defaultValue)
  if (hasDraft && !window.confirm(text('未提交内容不会带入另一版本。继续切换？', 'Unsubmitted changes will not carry over. Switch edition?'))) return
  if (next === 'classic' && route.path === '/explore') {
    // Navigate before switching so the advanced-only route cannot leave a classic dead end.
    await router.replace('/profile')
  }
  await ui.switchTo(next)
}
</script>
<template>
  <div v-if="ui.edition === 'advanced' || advancedEnabled" class="edition-entry" :class="{ 'advanced-ui': ui.edition === 'advanced' }">
    <button type="button" class="edition-switch" :disabled="ui.blocked || !ui.key" @click="change" data-testid="edition-switch">
      {{ ui.switching ? text('正在切换…', 'Switching…') : ui.edition === 'classic' ? text('切换高级版', 'Switch to advanced') : text('切换经典版', 'Switch to classic') }}
    </button>
    <p v-if="pendingAccountWrites > 0" role="status">{{ text('请求处理中，完成后可切换版本', 'A request is in progress. Switch after it finishes.') }}</p>
    <p v-if="ui.error" role="alert">{{ ui.error === 'storage' ? text('无法保存版本偏好，当前版本未改变', 'Unable to save preference. Edition unchanged.') : text('请先确认登录账户与租户', 'Please verify the signed-in account and tenant.') }}</p>
  </div>
</template>
<style scoped>
.edition-entry{margin:12px 8px 24px}.edition-switch{display:block;width:100%;min-height:44px;padding:12px 16px;border:1px solid #ddd;border-radius:12px;background:#f5f5f5;color:#555;font:inherit;cursor:pointer}.advanced-ui .edition-switch{border-color:#e9edef;background:#fff;color:#7557b7}.edition-switch:disabled{opacity:.6;cursor:wait}.edition-switch:focus-visible{outline:3px solid #7557b7;outline-offset:2px}.edition-entry p{font-size:12px;line-height:1.5;color:#707780;overflow-wrap:anywhere}
</style>
