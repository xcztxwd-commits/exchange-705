<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { can } from '@/utils/access'
import request from '@/utils/request'

type Event = Record<string, any>
const api = '/admin/insights/calendar'
const events = ref<Event[]>([]), sources = ref<Event[]>([]), coverage = ref<Event>({})
const loading = ref(false), busy = ref(false), total = ref(0), page = ref(1)
const utcToday = new Date().toISOString().slice(0, 10)
const end = new Date(); end.setUTCDate(end.getUTCDate() + 30)
const from = ref(utcToday), to = ref(end.toISOString().slice(0, 10))
const metric = ref(''), status = ref(''), zone = ref('Asia/Singapore')
const metrics = ['NFP_CHANGE', 'UNEMPLOYMENT_RATE', 'CPI_YOY', 'CORE_PCE_MOM', 'GDP_QOQ_ANNUALIZED', 'FOMC_DECISION', 'FOMC_MINUTES']
const states = ['SCHEDULED', 'AWAITING_RELEASE', 'RELEASED', 'POSTPONED', 'CANCELLED']
const labels: Record<string, string> = { NFP_CHANGE: '非农就业月度变动', UNEMPLOYMENT_RATE: '失业率', CPI_YOY: 'CPI 同比', CORE_PCE_MOM: '核心 PCE 环比', GDP_QOQ_ANNUALIZED: '实际 GDP 季度年化', FOMC_DECISION: 'FOMC 决定', FOMC_MINUTES: 'FOMC 纪要', SCHEDULED: '待公布', AWAITING_RELEASE: '等待实际公布', RELEASED: '已公布', POSTPONED: '已改期', CANCELLED: '已取消' }
const editing = ref<Event | null>(null), editOpen = ref(false), reason = ref('')
const audit = ref<Event[]>([]), auditOpen = ref(false), sourceHistory = ref<Event[]>([]), historyOpen = ref(false)
const importOpen = ref(false), format = ref('ICS'), sourceId = ref('BLS_CALENDAR')
const content = ref(''), material = ref(''), verified = ref(false)
function error(e: any) { ElMessage.error(e?.message || '操作失败，请检查权限或刷新后重试') }
function time(row: Event) {
  if (row.timePrecision === 'MINUTE' && row.releaseAt) return new Intl.DateTimeFormat('zh-CN', { timeZone: zone.value, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(row.releaseAt))
  return `${row.releaseDate || '日期未知'} · ${row.timePrecision === 'DATE' ? '仅日期，时刻未提供' : '时间未确认'}（${row.sourceTimezone}）`
}
async function load(reset = false) {
  if (reset) page.value = 1
  loading.value = true
  try {
    const result: any = await request.get(api, { params: { from: from.value, to: to.value, metric: metric.value || undefined, status: status.value || undefined, page: page.value - 1, size: 20 } })
    events.value = result.content; sources.value = result.sources; total.value = result.totalElements; coverage.value = result.coverage
  } catch (e) { error(e) } finally { loading.value = false }
}
async function open(row: Event) {
  try { editing.value = await request.get(`${api}/${row.eventId}`) as any; reason.value = ''; editOpen.value = true } catch (e) { error(e) }
}
async function save() {
  if (!editing.value || !reason.value.trim() || !can('calendar:edit')) return
  busy.value = true
  try {
    const data = { ...editing.value }; for (const field of ['actual', 'previous', 'adminEstimate', 'releaseAt', 'releaseDate']) if (data[field] === '') data[field] = null
    editing.value = await request.put(`${api}/${data.eventId}`, { rowVersion: data.rowVersion, reason: reason.value, manualLock: data.manualLock, data }) as any
    ElMessage.success('核验纠正已保存'); await load()
  } catch (e) { error(e) } finally { busy.value = false }
}
async function publication(published: boolean, cancelled = false) {
  if (!editing.value || !reason.value.trim() || !can('calendar:publish')) return
  busy.value = true
  try {
    const row = editing.value
    editing.value = await request.post(`${api}/${row.eventId}/publication`, { rowVersion: row.rowVersion, published, status: cancelled ? 'CANCELLED' : row.status, reason: reason.value }) as any
    ElMessage.success(cancelled ? '事件已取消，未投递提醒将停止' : published ? '已发布' : '已下架'); await load()
  } catch (e) { error(e) } finally { busy.value = false }
}
async function showAudit(row: Event) {
  try { audit.value = await request.get(`${api}/${row.eventId}/audit`) as any; auditOpen.value = true } catch (e) { error(e) }
}
async function history(source: Event) {
  try { sourceHistory.value = await request.get(`${api}/sources/${source.sourceId}/history`) as any; historyOpen.value = true } catch (e) { error(e) }
}
async function sync(source: Event) {
  if (!can('calendar:sync') || busy.value) return
  busy.value = true
  try {
    const result: any = await request.post(`${api}/sources/${source.sourceId}/sync`, {}, { timeout: 60000 })
    const current = result.sources.find((s: Event) => s.sourceId === source.sourceId)
    if (!result.requested) ElMessage.info('处于配额/退避窗口，复用持久化缓存，未再次请求来源')
    else if (current?.status === 'OK') ElMessage.success(`官方同步已验证；本租户更新 ${result.changed} 条`)
    else ElMessage.warning(`官方采集未成功：${current?.status || 'ERROR'}；保留原缓存`)
    await load()
  } catch (e) { error(e) } finally { busy.value = false }
}
async function importData() {
  if (!can('calendar:import') || busy.value || !verified.value || !material.value.trim() || !content.value.trim()) return
  busy.value = true
  try {
    const result: any = await request.post(`${api}/import`, { format: format.value, sourceId: sourceId.value, content: content.value, material: material.value, verified: verified.value }, { timeout: 60000 })
    ElMessage.success(`已核验人工导入 ${result.changed} 条；新增事件为草稿，不代表自动采集成功`)
    importOpen.value = false; content.value = ''; verified.value = false; await load()
  } catch (e) { error(e) } finally { busy.value = false }
}
onMounted(() => load())
</script>

<template>
  <section class="calendar-management" aria-label="财经事件日历管理">
    <header><div><h2>财经事件日历</h2><p>美国 · 高重要性 · 官方日程与核验材料；不是全球日历。市场共识预期来源未提供。</p></div><el-button v-permission="'calendar:import'" v-if="can('calendar:import')" :disabled="busy" @click="importOpen = true">核验导入</el-button></header>
    <el-alert title="BLS 时间序列是当前最新值，不等于历史初值。核心 PCE RSS 缺值须核验官方材料，不能用个人收入/支出替代。提醒只投递站内信；ICS 是独立系统日历导出。" type="info" :closable="false" />
    <details class="sources"><summary>官方来源健康与配额（{{ sources.length }} 个固定渠道）</summary>
      <div class="scroll"><table><caption>来源采集状态</caption><thead><tr><th>来源</th><th>状态 / HTTP</th><th>成功时间 UTC</th><th>下次允许 UTC</th><th>日预算</th><th>错误 / 操作</th></tr></thead><tbody><tr v-for="source in sources" :key="source.sourceId"><td>{{ source.sourceId }}<br><small>{{ source.autoSyncEnabled ? '自动采集开启' : '自动采集关闭' }}</small></td><td>{{ source.status }} / {{ source.httpStatus ?? '—' }}<br>{{ source.stale ? '缓存过期/暂无成功缓存' : '缓存有效' }}</td><td>{{ source.lastSuccess || '—' }}</td><td>{{ source.nextAttempt || '—' }}</td><td>{{ source.requestsToday }} / {{ source.dailyBudget }}</td><td><span>{{ source.lastError || '—' }}</span><div class="actions"><el-button v-permission="'calendar:view'" @click="history(source)">更新记录</el-button><el-button v-permission="'calendar:sync'" v-if="can('calendar:sync')" :disabled="busy" @click="sync(source)">同步此来源</el-button></div></td></tr></tbody></table></div>
    </details>
    <div class="filters"><label>来源日期起<input v-model="from" type="date" aria-label="来源日期起"></label><label>来源日期止<input v-model="to" type="date" aria-label="来源日期止"></label><label>事件类型<select v-model="metric" aria-label="事件类型"><option value="">全部</option><option v-for="m in metrics" :key="m" :value="m">{{ labels[m] }}</option></select></label><label>事件状态<select v-model="status" aria-label="事件状态"><option value="">全部</option><option v-for="s in states" :key="s" :value="s">{{ labels[s] }}</option></select></label><label>显示时区<select v-model="zone" aria-label="显示时区"><option>Asia/Singapore</option><option>America/New_York</option><option>UTC</option></select></label><el-button v-permission="'calendar:view'" :loading="loading" @click="load(true)">查询日历</el-button></div>
    <p>真实已发布覆盖：<span v-for="(c, i) in coverage.persistedCoverage || []" :key="i">{{ labels[c[0]] }} {{ c[1] }} 条（{{ c[2] }} 至 {{ c[3] }}）；</span><span v-if="!coverage.persistedCoverage?.length">尚无已发布事件</span></p>
    <div v-loading="loading" class="scroll"><table class="events"><caption>本租户当前环境事件 · {{ zone }}</caption><thead><tr><th>事件 / 稳定 ID</th><th>发布时间 / 统计期</th><th>实际 / 前值 / 预期</th><th>口径与状态</th><th>操作</th></tr></thead><tbody><tr v-for="row in events" :key="row.eventId"><td>{{ row.title }}<br><small>{{ row.eventId }}</small><br><a :href="row.sourceUrl" target="_blank" rel="noopener noreferrer">{{ row.agency }} 官方材料</a></td><td>{{ time(row) }}<br><small>{{ row.statisticalPeriod }} · {{ row.releaseStage }}</small></td><td>{{ row.unit === 'NONE' ? '政策公告，无数字映射' : `${row.actual ?? '—'} / ${row.previous ?? '—'}` }}<br>预期：— / 来源未提供<span v-if="row.adminEstimate != null"><br>管理员估计：{{ row.adminEstimate }}（ADMIN_ESTIMATE）</span></td><td>{{ row.unit }} · {{ row.comparison }} · {{ row.seasonality }}<br>{{ row.actualBasis }}<br>{{ labels[row.status] }} · {{ row.published ? '已发布' : '草稿/未发布' }} · {{ row.manualLock ? '人工覆盖锁定' : '自动更新' }}<br>{{ row.isRevised ? '已修订' : '暂无已知修订' }}</td><td><div class="actions"><el-button v-permission="'calendar:view'" @click="open(row)">查看 / 维护</el-button><el-button v-permission="'calendar:view'" @click="showAudit(row)">审计记录</el-button></div></td></tr><tr v-if="!events.length && !loading"><td colspan="5">当前窗口没有事件。请调整日期或核验导入；不会生成假事件。</td></tr></tbody></table></div>
    <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next, total" @current-change="load()" />

    <el-dialog v-model="editOpen" title="事件核验与发布" width="min(880px, 96vw)" :close-on-click-modal="false">
      <div v-if="editing" class="edit"><p class="identity">{{ editing.eventId }} · {{ editing.environment }} · version {{ editing.rowVersion }}</p>
        <el-form label-position="top" :disabled="busy || !can('calendar:edit')"><el-form-item label="标题"><el-input v-model="editing.title" maxlength="160" /></el-form-item><p>{{ labels[editing.metric] }} · 统计期 {{ editing.statisticalPeriod }} · {{ editing.releaseStage }} · {{ editing.unit }} / {{ editing.comparison }} / {{ editing.seasonality }}（身份和口径不可更换）</p>
          <div class="field-grid"><el-form-item label="时间精度"><el-select v-model="editing.timePrecision"><el-option v-for="p in ['MINUTE', 'DATE', 'UNKNOWN']" :key="p" :value="p" /></el-select></el-form-item><el-form-item label="来源时区 IANA"><el-input v-model="editing.sourceTimezone" /></el-form-item><el-form-item label="UTC 发布时间（MINUTE，ISO 8601 Z）"><el-input v-model="editing.releaseAt" placeholder="仅填经核验的 UTC 整分钟时刻" /></el-form-item><el-form-item label="来源日期（DATE 或与 UTC 对应）"><el-input v-model="editing.releaseDate" placeholder="yyyy-MM-dd；UNKNOWN 时留空" /></el-form-item><el-form-item label="实际值（无值留空，不填 0）"><el-input v-model="editing.actual" :disabled="busy || !can('calendar:edit') || editing.unit === 'NONE'" /></el-form-item><el-form-item label="前值（无值留空）"><el-input v-model="editing.previous" :disabled="busy || !can('calendar:edit') || editing.unit === 'NONE'" /></el-form-item><el-form-item label="管理员估计（独立 ADMIN_ESTIMATE）"><el-input v-model="editing.adminEstimate" :disabled="busy || !can('calendar:edit') || editing.unit === 'NONE'" /></el-form-item><el-form-item label="估计依据"><el-input v-model="editing.estimateReason" maxlength="1000" /></el-form-item></div>
          <el-form-item label="事件状态"><el-select v-model="editing.status"><el-option v-for="s in states" :key="s" :label="labels[s]" :value="s" /></el-select></el-form-item><el-form-item label="对应机构官方 HTTPS 证据链接"><el-input v-model="editing.sourceUrl" maxlength="800" /></el-form-item><el-checkbox v-model="editing.manualLock">保留人工覆盖锁定（解除后才允许官方自动更新）</el-checkbox>
        </el-form><label class="reason">核验 / 修改依据（保存和发布均必填）<el-input v-model="reason" type="textarea" :rows="3" maxlength="1000" :disabled="busy || (!can('calendar:edit') && !can('calendar:publish'))" aria-label="核验修改依据" /></label><p>预期固定为空 / 来源未提供。已投递提醒不会因重新设置或改期重复发送。仅日期事件不可设置提前站内提醒。</p>
        <div class="actions"><el-button v-permission="'calendar:edit'" v-if="can('calendar:edit')" type="primary" :disabled="busy || !reason.trim()" @click="save">保存核验纠正</el-button><el-button v-permission="'calendar:publish'" v-if="can('calendar:publish')" :disabled="busy || !reason.trim()" @click="publication(true)">发布事件</el-button><el-button v-permission="'calendar:publish'" v-if="can('calendar:publish')" :disabled="busy || !reason.trim()" @click="publication(false)">下架事件</el-button><el-button v-permission="'calendar:publish'" v-if="can('calendar:publish')" type="danger" :disabled="busy || !reason.trim()" @click="publication(editing.published, true)">取消事件</el-button></div>
      </div>
    </el-dialog>
    <el-dialog v-model="importOpen" title="官方材料核验导入（新增为草稿）" width="min(880px, 96vw)" :close-on-click-modal="false"><el-form label-position="top"><el-form-item label="格式"><el-select v-model="format"><el-option label="官方 ICS 原文" value="ICS" /><el-option label="已核验官方材料 JSON" value="VERIFIED_JSON" /></el-select></el-form-item><el-form-item v-if="format === 'ICS'" label="官方日程机构"><el-select v-model="sourceId"><el-option label="BLS（当前可能 403，需合法官方原文）" value="BLS_CALENDAR" /><el-option label="BEA" value="BEA_CALENDAR" /></el-select></el-form-item><p>VERIFIED_JSON 为事件数组：agency、metric、statisticalPeriod、releaseStage、title、timePrecision、sourceTimezone、releaseAt/releaseDate、unit、comparison、seasonality、sourceUrl。actual/previous 可空；forecast 必须 null。统计身份自动生成，不是静态假月份。</p><el-form-item label="核验材料正文"><el-input v-model="content" type="textarea" :rows="10" aria-label="核验材料正文" /></el-form-item><el-form-item label="材料来源与核验依据"><el-input v-model="material" type="textarea" :rows="3" maxlength="1000" aria-label="材料来源与核验依据" /></el-form-item><el-checkbox v-model="verified">已核验为合法官方材料，数值和口径对应</el-checkbox></el-form><template #footer><el-button v-permission="'calendar:view'" @click="importOpen = false">关闭</el-button><el-button v-permission="'calendar:import'" type="primary" :disabled="busy || !verified || !material.trim() || !content.trim()" @click="importData">确认核验导入</el-button></template></el-dialog>
    <el-dialog v-model="auditOpen" title="事件审计记录（最近 50 条）" width="min(940px, 96vw)"><details v-for="item in audit" :key="item.id"><summary>{{ item.createdAt }} · {{ item.actor }} · {{ item.action }} · {{ item.reason }}</summary><h4>修改前</h4><pre>{{ item.beforeJson || '新增事件' }}</pre><h4>修改后</h4><pre>{{ item.afterJson }}</pre></details><p v-if="!audit.length">暂无记录</p></el-dialog>
    <el-dialog v-model="historyOpen" title="官方采集记录（最近 50 条）" width="min(940px, 96vw)"><pre v-for="item in sourceHistory" :key="item.id">{{ JSON.stringify(item, null, 2) }}</pre><p v-if="!sourceHistory.length">尚未尝试采集；人工导入不计为官方自动成功。</p></el-dialog>
  </section>
</template>

<style scoped>
.calendar-management { --el-color-primary: #47633d; --el-color-primary-light-7: #d4dfcf; --el-color-primary-light-9: #f2f5ed; --el-color-primary-dark-2: #344d2c; min-width: 0; color: #252a30; padding: 16px; background: white; border-radius: 8px; }
header, .filters, .actions { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
header { justify-content: space-between; } h2 { margin: 0; } p, small { color: #59616b; overflow-wrap: anywhere; }
.sources, .filters { margin: 20px 0; } summary { cursor: pointer; padding: 14px 0; overflow-wrap: anywhere; }
.filters label, .reason { display: grid; gap: 6px; } input, select { min-height: 44px; max-width: 100%; border: 1px solid #d9dfd5; border-radius: 5px; padding: 8px; color: inherit; background: white; }
.scroll { overflow-x: auto; max-width: 100%; } table { width: 100%; min-width: 920px; border-collapse: collapse; text-align: left; } caption { text-align: left; padding: 10px 0; }
th, td { padding: 12px; border-bottom: 1px solid #e9edef; vertical-align: top; overflow-wrap: anywhere; max-width: 340px; } th { background: #f2f5ed; } a { color: #344d2c; text-decoration: underline; }
.field-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; } .identity, pre { white-space: pre-wrap; overflow-wrap: anywhere; } pre { background: #f2f5ed; padding: 12px; max-height: 400px; overflow: auto; }
:deep(.el-button) { min-height: 44px; margin-left: 0; white-space: normal; } :deep(.el-pagination) { flex-wrap: wrap; margin-top: 16px; } :deep(.el-pagination button), :deep(.el-pager li) { min-height: 44px; min-width: 44px; } :deep(.el-input__wrapper), :deep(.el-select__wrapper) { min-height: 44px; } :deep(.el-dialog__body) { overflow: auto; } :deep(.el-dialog__headerbtn) { width: 44px; height: 44px; } :deep(.el-checkbox) { min-height: 44px; white-space: normal; }
button:focus-visible, input:focus-visible, select:focus-visible, summary:focus-visible { outline: 2px solid #47633d; outline-offset: 3px; }
@media(max-width: 600px) { .field-grid { grid-template-columns: 1fr; } .calendar-management { padding: 12px; } .filters { align-items: stretch; } .filters label { width: 100%; } }
</style>
