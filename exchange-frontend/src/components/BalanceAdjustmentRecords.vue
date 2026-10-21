<script setup lang="ts">
import { ref, onMounted } from 'vue'
import request from '@/utils/request'
import { useLocaleStore } from '@/store/locale'
import { accountMode } from '@/utils/accountMode'
const props = withDefaults(defineProps<{ visible?: boolean }>(), { visible: true })
const locale = useLocaleStore()
const rows = ref<any[]>([]), loading = ref(false), error = ref(false), hasMore = ref(false), page = ref(0)
async function load(reset = false) {
  if (loading.value || accountMode() !== 'REAL') return
  loading.value = true; error.value = false
  try {
    const next = reset ? 0 : page.value
    const response: any = await request.get('/user/balance-adjustments', { params: { page: next, size: 20 } })
    if (response.success === false) throw new Error('Unable to load records')
    rows.value = reset ? response.list || [] : [...rows.value, ...(response.list || [])]
    hasMore.value = response.hasMore === true; page.value = next + 1
  } catch { error.value = true } finally { loading.value = false }
}
const accountName = (account: string) => account === 'FUND' ? locale.t('fundAccountTitle') : account === 'CONTRACT' ? locale.t('contractAccountTitle') : locale.t('optionAccountTitle')
onMounted(() => load(true))
</script>

<template>
  <section v-if="rows.length || error" class="admin-fund-records">
    <div class="record-heading"><h2>{{ locale.text('后台出入金', 'Admin deposits and withdrawals') }}</h2><button type="button" :disabled="loading" @click="load(true)">{{ locale.text('刷新', 'Refresh') }}</button></div>
    <p v-if="error" role="alert">{{ locale.text('记录加载失败，请重试', 'Unable to load records. Please retry.') }} <button type="button" @click="load(true)">{{ locale.text('重试', 'Retry') }}</button></p>
    <div v-for="record in rows" :key="record.id" class="fund-record">
      <div><strong>{{ record.type === 'ADMIN_IN' ? locale.text('后台入金', 'Admin deposit') : locale.text('后台出金', 'Admin withdrawal') }}</strong><small>{{ accountName(record.account) }} · {{ String(record.createdAt).replace('T', ' ') }}</small></div>
      <span :class="record.type === 'ADMIN_IN' ? 'money-in' : 'money-out'">{{ props.visible ? (record.type === 'ADMIN_IN' ? '+' : '') + String(record.amount) + ' USD' : '****' }}</span>
    </div>
    <button v-if="hasMore" type="button" :disabled="loading" @click="load()">{{ locale.text('加载更多', 'Load more') }}</button>
  </section>
</template>

<style scoped>
.admin-fund-records{margin:16px;padding:16px;border:1px solid var(--el-border-color-light,#e5e7eb);border-radius:12px;background:var(--bg-card,#fff);color:var(--text-primary,#303133)}
.record-heading,.fund-record{display:flex;align-items:center;justify-content:space-between;gap:12px}.record-heading h2{font-size:15px;margin:0}.fund-record{padding:12px 0;border-bottom:1px solid var(--el-border-color-light,#eee);font-size:13px}.fund-record:last-child{border:0}.fund-record strong{font-weight:500}.fund-record small{display:block;margin-top:5px;color:var(--text-secondary,#909399)}.fund-record>span{font-variant-numeric:tabular-nums;overflow-wrap:anywhere;text-align:right}.money-in{color:#1e9c62}.money-out{color:#d64e54}button{border:0;background:none;color:#5e8b16;cursor:pointer;font:inherit;padding:4px}button:disabled{opacity:.5;cursor:wait}
</style>
