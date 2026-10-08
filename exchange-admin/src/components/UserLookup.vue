<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useAccountTable } from '@/utils/useAccountTable'
import { useDebouncedSearch } from '@/composables/useDebouncedSearch'
import type { AccountMode } from '@/utils/accountTableData'

const props = withDefaults(defineProps<{
  modelValue: string
  scope: string
  accountModes: AccountMode[]
  agentId?: number | string | null
  placeholder?: string
}>(), { placeholder: '输入部分用户 ID / 邮箱' })
const emit = defineEmits<{ 'update:modelValue': [value: string]; change: [] }>()
const selected = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value || '') })
const table = useAccountTable()
const matches = ref<{ userId: number; email: string | null; accountModeLabel?: string }[]>([])
const loading = ref(false), error = ref('')
let query = '', version = 0
const { schedule, cancel } = useDebouncedSearch(async () => {
  const current = version
  try {
    const result = await table.query(`/admin/user-lookup/${props.scope}`, { query, filterAgentId: props.agentId || undefined })
    if (current === version) matches.value = Array.isArray(result) ? result : result.list || []
  } catch (failure: any) {
    if (current === version) error.value = failure.message || '用户搜索失败'
  } finally {
    if (current === version) loading.value = false
  }
})
function search(input: string) {
  cancel(); version++; matches.value = []; error.value = ''; query = input.trim()
  loading.value = !!query && !!props.accountModes.length
  if (loading.value) schedule()
}
const options = computed(() => {
  const users = new Map<string, Set<string>>()
  for (const user of matches.value) {
    const id = String(user.userId)
    const labels = users.get(id) || new Set<string>()
    labels.add(`${props.accountModes.length > 1 ? (user.accountModeLabel || '') + ' · ' : ''}${user.email || '无邮箱'}`)
    users.set(id, labels)
  }
  return [...users].map(([id, labels]) => ({ id, label: `${id} · ${[...labels].join(' / ')}` }))
})
watch([() => props.accountModes.join(','), () => props.agentId, () => props.scope], () => {
  table.modes.value = [...props.accountModes]
  search('')
}, { immediate: true, flush: 'sync' })
watch(() => props.modelValue, value => { if (!value) search('') }, { flush: 'sync' })
onBeforeUnmount(() => search(''))
</script>

<template>
  <el-select :key="`${accountModes.join(',')}:${agentId || ''}`" v-model="selected" class="user-lookup"
    filterable remote clearable :debounce="0" :remote-method="search" :loading="loading" :disabled="!accountModes.length"
    :placeholder="placeholder" aria-label="用户 ID / 邮箱" :no-data-text="error || '未找到匹配用户'"
    :no-match-text="error || '未找到匹配用户'"
    loading-text="正在搜索用户…" @clear="search('')" @change="emit('change')">
    <el-option v-for="user in options" :key="user.id" :value="user.id" :label="user.label" />
  </el-select>
</template>

<style scoped>
.user-lookup { width: 300px; max-width: 100%; }
</style>
