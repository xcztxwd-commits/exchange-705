<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import { can } from '@/utils/access'

const props = defineProps<{ canEdit: boolean }>()
const health = ref<any>(null), busy = ref(false), saving = ref(false), error = ref('')
const maySave = computed(() => props.canEdit && can('settings:save') && health.value?.globalEnabled)
const labels: Record<string, string> = { LIVE: 'LIVE / 有效快照', SYNCING: 'SYNCING / 待同步', STALE: 'STALE / 已过期', UNSUPPORTED: 'UNSUPPORTED / 不支持', ERROR: 'ERROR / 来源故障', DISABLED: 'DISABLED / 已关闭' }
const date = (value: number | null) => value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Singapore', hour12: false }) + ' SGT' : '—'
const refresh = (row: any) => row.refreshMethod === 'REST_POLL' ? '周期刷新 / REST' : row.refreshMethod === 'WS_SNAPSHOT' ? 'WS 有限档快照' : '尚未采集'
let timer: ReturnType<typeof setInterval> | undefined
let active = true
async function load() {
  if (busy.value || !active) return
  busy.value = true
  try { const result = await request.get('/admin/market/depth/status'); if (active) { health.value = result; error.value = '' } }
  catch (e: any) { if (active) error.value = e.message || '深度状态读取失败' }
  finally { busy.value = false }
}
async function toggle() {
  if (!maySave.value || saving.value) return
  saving.value = true
  try {
    await request.put('/admin/market/depth/settings', { enabled: !health.value.enabled })
    ElMessage.success('深度开关已保存；仅影响当前租户')
    await load()
  } catch (e: any) { error.value = e.message || '保存失败' }
  finally { saving.value = false }
}
onMounted(() => { load(); timer = setInterval(() => { if (!document.hidden) load() }, 5000) })
onUnmounted(() => { active = false; if (timer) clearInterval(timer) })
</script>

<template>
  <section class="depth-health" aria-labelledby="depth-health-heading">
    <div class="depth-toolbar">
      <h3 id="depth-health-heading">外部参考深度 / 来源健康</h3>
      <el-button :loading="busy" @click="load">刷新状态</el-button>
      <el-button v-permission="'settings:save'" :disabled="!maySave" :loading="saving" @click="toggle">{{ health?.enabled ? '禁用当前租户深度' : '启用当前租户深度' }}</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon role="alert" />
    <el-alert title="仅为外部参考盘口，不是平台执行订单簿。无账户 Key；不切换原有供应商；只在可见订阅或近期访问时采集。" type="info" :closable="false" />
    <dl v-if="health" class="depth-summary">
      <div><dt>启用 / 全局开关</dt><dd>{{ health.enabled ? '开启' : '关闭' }} / {{ health.globalEnabled ? '开启' : '关闭' }}</dd></div>
      <div><dt>当前供应商（只读）</dt><dd>{{ health.provider }}</dd></div>
      <div><dt>当前租户活跃引用</dt><dd>{{ health.activeReferences }}</dd></div>
      <div><dt>REST 周期 / 过期阈值</dt><dd>{{ health.pollIntervalMs }} ms / {{ health.maxAgeMs }} ms</dd></div>
    </dl>
    <p>源时间未提供显示“—”，不会伪装为接收时间。5 档不补成 20 档；支持情况以官方目录验证为准。</p>
    <el-table v-if="health" :data="health.instruments" empty-text="本租户没有启用品种" :max-height="440" style="width: 100%" tabindex="0" aria-label="本租户深度来源状态">
      <el-table-column prop="symbol" label="品种 / 市场" min-width="160"><template #default="{ row }">{{ row.symbol }}<br>{{ row.externalSymbol || '—' }} · {{ row.marketType || '—' }}</template></el-table-column>
      <el-table-column label="状态 / 支持" min-width="195"><template #default="{ row }">{{ labels[row.status] || row.status }}<br>{{ row.supportVerified ? '官方目录已确认' : row.status === 'UNSUPPORTED' ? '不支持' : '待访问验证' }}</template></el-table-column>
      <el-table-column label="刷新 / 连接" min-width="175"><template #default="{ row }">{{ refresh(row) }}<br>{{ row.stream?.connected ? '已连接' : '未连接' }}</template></el-table-column>
      <el-table-column label="实际档数 / 单位" min-width="150"><template #default="{ row }">买 {{ row.displayedLevels?.bids || 0 }} / 卖 {{ row.displayedLevels?.asks || 0 }}<br>{{ row.quantityUnit === 'CONTRACT' ? '张（合约）' : row.quantityCurrency || '—' }}</template></el-table-column>
      <el-table-column label="最后成功 / 接收时间（SGT）" min-width="245"><template #default="{ row }">{{ date(row.lastSuccessAt) }}<br>{{ date(row.receivedAt) }}</template></el-table-column>
      <el-table-column label="源时间 / 延迟" min-width="245"><template #default="{ row }">{{ date(row.sourceAsOf) }}<br>{{ row.latencyMs == null ? '来源未提供时间' : row.latencyMs + ' ms' }}</template></el-table-column>
      <el-table-column label="最近错误 / 请求数" min-width="180"><template #default="{ row }">{{ row.reason || row.stream?.error || '—' }}<br>REST {{ row.restRequests || 0 }} / 缓存 {{ row.cacheHits || 0 }}</template></el-table-column>
    </el-table>
  </section>
</template>

<style scoped>
.depth-health { box-sizing: border-box; min-width: 280px; margin-bottom: 24px; padding: 16px; border: 1px solid #e9edef; border-radius: 10px; color: #252a30; }
.depth-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-bottom: 12px; }
.depth-toolbar h3 { flex: 1 1 240px; margin: 0; color: #47633d; }
.depth-toolbar button { min-height: 44px; }
.depth-health p { color: #707780; overflow-wrap: anywhere; }
.depth-summary { display: flex; flex-wrap: wrap; gap: 20px; margin: 16px 0; }
.depth-summary div { min-width: 180px; flex: 1; }
.depth-summary dt { color: #707780; }
.depth-summary dd { margin: 6px 0; overflow-wrap: anywhere; }
.depth-health :focus-visible { outline: 2px solid #47633d; outline-offset: 2px; }
</style>
