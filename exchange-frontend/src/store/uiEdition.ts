import { computed, nextTick, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { useAuthStore } from './auth'
import { pendingAccountWrites } from '@/utils/accountRequests'
import { readUiEdition, uiEditionKey, writeUiEdition, type UiEdition } from '@/utils/uiEdition'

export const useUiEditionStore = defineStore('ui-edition', () => {
  const auth = useAuthStore()
  const edition = ref<UiEdition>('classic'), switching = ref(false), error = ref('')
  const key = computed(() => auth.token ? uiEditionKey(auth.user, location.origin) : '')
  const blocked = computed(() => switching.value || pendingAccountWrites.value > 0)
  watch(key, value => {
    try { edition.value = readUiEdition(localStorage, value) } catch { edition.value = 'classic' }
    error.value = ''
  }, { immediate: true, flush: 'sync' })
  async function switchTo(value: UiEdition) {
    if (blocked.value || !['classic', 'advanced'].includes(value)) return false
    switching.value = true; error.value = ''
    try {
      // A user/tenant must be known before a persistent preference can be changed.
      if (!key.value) { error.value = 'identity'; return false }
      if (!writeUiEdition(localStorage, key.value, value)) { error.value = 'storage'; return false }
      edition.value = value
      await nextTick() // Old view unmounts before the next interaction.
      return true
    } catch { error.value = 'storage'; return false }
    finally { switching.value = false }
  }
  return { edition, key, switching, blocked, error, switchTo }
})
