<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
import { can } from '@/utils/access'

type Rule = { strategyId: string | null; mode: string; until: string | null; reason: string }
type Weekly = { startDay: number; startTime: string; endDay: number; endTime: string; reason: string; timezone?: string | null }
type ExceptionRange = { start: string; end: string; closed: boolean; reason: string; source: string }
type Strategy = { id: string; name: string; timezone: string; note: string; weekly: Weekly[]; exceptions: ExceptionRange[] }
type Settings = { version: number; revision: number; strategies: Strategy[]; categories: Record<string, Rule>; symbols: Record<string, Rule> }
const settings = ref<Settings>(), categories = ref<any[]>([]), symbols = ref<any[]>([])
const loading = ref(false), saving = ref(false), loadedAt = ref(0), baseline = ref('')
const tab = ref('bindings'), query = ref(''), categoryFilter = ref(''), policyId = ref('fx-weekend')
const dirty = computed(() => !!settings.value && JSON.stringify(settings.value) !== baseline.value)
const policy = computed(() => settings.value?.strategies.find(p => p.id === policyId.value))
const filtered = computed(() => symbols.value.filter(s => (!categoryFilter.value || s.category === categoryFilter.value)
  && `${s.name} ${s.symbol}`.toLowerCase().includes(query.value.toLowerCase())))
const days = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const manual = ref<{ scope: string; target: string; label: string; mode: string; until: Date | null; reason: string }>()
const modeLabel = (r?: Rule) => !r || r.mode === 'AUTO' || r.until && Date.parse(r.until) <= loadedAt.value ? '自动策略' : r.mode === 'OPEN' ? '手动开市' : '手动休市'
const time = (value?: number | string | null) => value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Singapore', hour12: false }) : '—'
function apply(data: any) {
  settings.value = data.settings; categories.value = data.categories; symbols.value = data.symbols; loadedAt.value = data.serverTime
  baseline.value = JSON.stringify(settings.value)
  if (!settings.value?.strategies.some(p => p.id === policyId.value)) policyId.value = settings.value?.strategies[0]?.id || ''
}
async function load() {
  if (!can('market_hours:view') || saving.value || loading.value) return
  if (dirty.value) { try { await ElMessageBox.confirm('重新加载会丢弃未保存的策略和绑定。', '重新加载', { type: 'warning' }) } catch { return } }
  if (!can('market_hours:view') || saving.value || loading.value) return
  loading.value = true
  try { apply(await request.get('/admin/market-hours')) } catch (e: any) { ElMessage.error(e.message || '加载失败') }
  finally { loading.value = false }
}
function rule(scope: 'categories' | 'symbols', key: string): Rule {
  return settings.value![scope][key] ||= { strategyId: null, mode: 'AUTO', until: null, reason: '' }
}
function setStrategy(scope: 'categories' | 'symbols', key: string, value: string) {
  if (!settings.value || saving.value || !can('market_hours:save')) return
  rule(scope, key).strategyId = value || null
}
async function save() {
  if (!settings.value || !can('market_hours:save') || saving.value) return
  saving.value = true
  try { apply(await request.put('/admin/market-hours', settings.value)); ElMessage.success('休市策略与绑定已保存') }
  catch (e: any) { ElMessage.error(e.message || '保存失败') } finally { saving.value = false }
}
function openManual(scope: string, target: string, label: string, mode: string) {
  if (!settings.value || saving.value || !can('market_hours:manual')) return
  if (dirty.value) { ElMessage.warning('请先保存策略和绑定，再操作手动开关'); return }
  manual.value = { scope, target, label, mode, until: mode === 'OPEN' ? new Date(Date.now() + 3600000) : null, reason: '' }
}
async function commitManual() {
  if (!manual.value || !settings.value || saving.value || !can('market_hours:manual')) return
  if (manual.value.mode !== 'AUTO' && !manual.value.reason.trim()) { ElMessage.warning('请填写操作原因'); return }
  if (manual.value.mode === 'OPEN' && !manual.value.until) { ElMessage.warning('手动开市须设置失效时间'); return }
  saving.value = true
  try {
    apply(await request.post('/admin/market-hours/override', { ...manual.value, until: manual.value.until?.toISOString() || null, revision: settings.value.revision }))
    manual.value = undefined; ElMessage.success('手动模式已更新')
  } catch (e: any) { ElMessage.error(e.message || '切换失败') } finally { saving.value = false }
}
function addPolicy() {
  if (!settings.value || saving.value || !can('market_hours:save') || settings.value.strategies.length >= 30) return
  const id = `custom-${Date.now()}`
  settings.value!.strategies.push({ id, name: '自定义休市策略', timezone: 'America/New_York', note: '', weekly: [], exceptions: [] }); policyId.value = id
}
function removePolicy() {
  if (!policy.value || !settings.value || saving.value || !can('market_hours:save')) return
  if (policy.value.id === 'fx-weekend' || [...Object.values(settings.value.categories), ...Object.values(settings.value.symbols)].some(r => r.strategyId === policyId.value)) {
    ElMessage.warning('默认或已被绑定的策略不能删除'); return
  }
  settings.value.strategies = settings.value.strategies.filter(p => p.id !== policyId.value); policyId.value = 'fx-weekend'
}
function localValue(value: string) {
  const date = new Date(value); if (!Number.isFinite(date.getTime())) return ''
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16)
}
function setInstant(e: ExceptionRange, key: 'start' | 'end', event: Event) {
  if (saving.value || !can('market_hours:save')) return
  const value = (event.target as HTMLInputElement).value
  if (value && !Number.isFinite(new Date(value).getTime())) return
  e[key] = value ? new Date(value).toISOString() : ''
}
function addWeekly() {
  if (!policy.value || saving.value || !can('market_hours:save') || policy.value.weekly.length >= 64) return
  policy.value.weekly.push({ startDay: 5, startTime: '17:00', endDay: 7, endTime: '17:00', reason: '计划休市' })
}
function removeWeekly(index: number) {
  if (!policy.value || saving.value || !can('market_hours:save') || index < 0 || index >= policy.value.weekly.length) return
  policy.value.weekly.splice(index, 1)
}
function addException() {
  if (!policy.value || saving.value || !can('market_hours:save') || policy.value.exceptions.length >= 128) return
  policy.value.exceptions.push({ start: '', end: '', closed: true, reason: '', source: '' })
}
function removeException(index: number) {
  if (!policy.value || saving.value || !can('market_hours:save') || index < 0 || index >= policy.value.exceptions.length) return
  policy.value.exceptions.splice(index, 1)
}
onMounted(load)
</script>

<template>
  <section class="market-hours" v-loading="loading">
    <header><div><h2>休市设置</h2><p>分类统一设置，品种单独覆盖。手动休市优先；手动开市不会恢复过期行情。</p></div>
      <div class="toolbar"><el-button v-permission="'market_hours:view'" :disabled="saving || loading" @click="load">重新加载</el-button>
        <el-button v-permission="'market_hours:save'" type="primary" :loading="saving" :disabled="!settings || loading || !dirty" @click="save">保存策略与绑定</el-button></div>
    </header>
    <el-alert v-if="!settings && !loading" title="休市设置加载失败，不能修改。请重新加载。" type="error" :closable="false" />
    <template v-if="settings">
      <p class="as-of">状态查询时间：{{ time(loadedAt) }}（UTC+8）；配置版本 {{ settings.revision }}<strong v-if="dirty"> · 有未保存修改</strong></p>
      <el-tabs v-model="tab">
        <el-tab-pane label="分类与品种" name="bindings">
          <el-card shadow="never"><template #header>分类默认策略</template>
            <admin-table table-key="MarketHours.categories" :data="categories" row-key="key">
              <el-table-column column-key="category" label="分类" min-width="130"><template #default="{ row }">{{ row.label }} <small>{{ row.key }}</small></template></el-table-column>
              <el-table-column column-key="policy" label="策略" min-width="230"><template #default="{ row }">
                <el-select placeholder="使用来源默认策略" :model-value="settings.categories[row.key]?.strategyId || ''" :disabled="saving || !can('market_hours:save')" @update:model-value="setStrategy('categories', row.key, $event)">
                  <el-option label="默认（实际外汇来源应用周末策略）" value="" /><el-option v-for="p in settings.strategies" :key="p.id" :label="p.name" :value="p.id" />
                </el-select></template></el-table-column>
              <el-table-column column-key="mode" label="模式" min-width="120"><template #default="{ row }">{{ modeLabel(settings.categories[row.key]) }}</template></el-table-column>
              <el-table-column column-key="manualExpiresAt" label="手动失效时间（UTC+8）" min-width="185"><template #default="{ row }">{{ time(settings.categories[row.key]?.until) }}</template></el-table-column>
              <el-table-column column-key="manualActions" label="手动操作" min-width="280"><template #default="{ row }">
                <div class="actions" v-if="can('market_hours:manual')"><el-button v-permission="'market_hours:manual'" size="small" :disabled="saving" @click="openManual('CATEGORY', row.key, row.label, 'CLOSED')">休市</el-button>
                  <el-button v-permission="'market_hours:manual'" size="small" :disabled="saving" @click="openManual('CATEGORY', row.key, row.label, 'OPEN')">开市</el-button>
                  <el-button v-permission="'market_hours:manual'" size="small" :disabled="saving" @click="openManual('CATEGORY', row.key, row.label, 'AUTO')">恢复自动</el-button></div>
              </template></el-table-column>
            </admin-table>
          </el-card>
          <el-card shadow="never" class="section"><template #header><div class="toolbar"><strong>品种覆盖与生效状态</strong>
            <el-input v-model="query" placeholder="搜索品种" clearable class="search" /><el-select v-model="categoryFilter" placeholder="全部分类" clearable class="filter"><el-option v-for="c in categories" :key="c.key" :label="c.label" :value="c.key" /></el-select></div></template>
            <admin-table table-key="MarketHours.symbols" :data="filtered" row-key="id" max-height="580">
              <el-table-column column-key="symbol" label="品种" min-width="160"><template #default="{ row }"><strong>{{ row.name }}</strong><small class="block">{{ row.symbol }} · {{ row.category }}<span v-if="!row.isEnabled"> · 已停用</span></small></template></el-table-column>
              <el-table-column column-key="overridePolicy" label="覆盖策略" min-width="230"><template #default="{ row }">
                <el-select placeholder="继承分类 / 外汇默认" :model-value="settings.symbols[row.id]?.strategyId || ''" :disabled="saving || !can('market_hours:save')" @update:model-value="setStrategy('symbols', String(row.id), $event)">
                  <el-option label="继承分类 / 外汇来源默认" value="" /><el-option v-for="p in settings.strategies" :key="p.id" :label="p.name" :value="p.id" />
                </el-select></template></el-table-column>
              <el-table-column column-key="effectiveStatus" label="生效状态（查询时）" min-width="180"><template #default="{ row }"><el-tag :type="row.status.closed ? 'warning' : 'success'">{{ row.status.closed ? '休市' : '时段开放' }}</el-tag>
                <small class="block">{{ row.status.reason }}</small><small class="block">{{ row.status.strategyId || '无计划休市' }}</small></template></el-table-column>
              <el-table-column column-key="nextTransition" label="下次状态切换（UTC+8）" min-width="185"><template #default="{ row }">{{ time(row.status.nextChangeAt) }}</template></el-table-column>
              <el-table-column column-key="manualActions" label="手动操作" min-width="280"><template #default="{ row }"><div class="actions" v-if="can('market_hours:manual')">
                <el-button v-permission="'market_hours:manual'" size="small" :disabled="saving" @click="openManual('SYMBOL', String(row.id), row.name, 'CLOSED')">休市</el-button>
                <el-button v-permission="'market_hours:manual'" size="small" :disabled="saving" @click="openManual('SYMBOL', String(row.id), row.name, 'OPEN')">开市</el-button>
                <el-button v-permission="'market_hours:manual'" size="small" :disabled="saving" @click="openManual('SYMBOL', String(row.id), row.name, 'AUTO')">恢复自动</el-button></div></template></el-table-column>
            </admin-table>
          </el-card>
        </el-tab-pane>
        <el-tab-pane label="策略配置" name="strategies">
          <div class="toolbar"><el-select v-model="policyId"><el-option v-for="p in settings.strategies" :key="p.id" :value="p.id" :label="p.name" /></el-select>
            <el-button v-permission="'market_hours:save'" :disabled="settings.strategies.length >= 30 || saving" @click="addPolicy">新增策略</el-button><el-button v-permission="'market_hours:save'" :disabled="!policy || saving" @click="removePolicy">删除未使用策略</el-button></div>
          <el-card v-if="policy" shadow="never" class="section">
            <el-form label-position="top" :disabled="saving || !can('market_hours:save')">
              <div class="form-grid"><el-form-item label="名称"><el-input v-model="policy.name" maxlength="80" /></el-form-item>
                <el-form-item label="IANA时区（自动处理夏令时）"><el-select v-model="policy.timezone" filterable allow-create default-first-option><el-option v-for="z in ['America/New_York', 'UTC', 'Asia/Singapore', 'Europe/London', 'Pacific/Auckland']" :key="z" :label="z" :value="z" /></el-select></el-form-item></div>
              <el-form-item label="说明"><el-input v-model="policy.note" type="textarea" :rows="3" maxlength="1000" /></el-form-item>
              <h3>每周休市区间</h3><p>默认使用策略时区，也可为某一段单独选择时区（例如NZD奥克兰日切）。开始含、结束不含；结束早于开始表示跨周。没有区间表示无计划休市。</p>
              <div class="interval" v-for="(w, index) in policy.weekly" :key="index">
                <el-select v-model="w.startDay" aria-label="休市开始星期"><el-option v-for="(d, i) in days" :key="i" :label="d" :value="i + 1" /></el-select>
                <input type="time" v-model="w.startTime" aria-label="休市开始时间" :disabled="saving || !can('market_hours:save')" /><span>至</span>
                <el-select v-model="w.endDay" aria-label="休市结束星期"><el-option v-for="(d, i) in days" :key="i" :label="d" :value="i + 1" /></el-select>
                <input type="time" v-model="w.endTime" aria-label="休市结束时间" :disabled="saving || !can('market_hours:save')" />
                <el-select v-model="w.timezone" clearable filterable allow-create default-first-option placeholder="继承策略时区" aria-label="区间时区"><el-option v-for="z in ['America/New_York', 'Pacific/Auckland', 'Europe/Berlin', 'UTC']" :key="z" :label="z" :value="z" /></el-select>
                <el-input v-model="w.reason" maxlength="200" placeholder="原因" /><el-button v-permission="'market_hours:save'" @click="removeWeekly(index)">移除</el-button>
              </div>
              <el-button v-permission="'market_hours:save'" :disabled="policy.weekly.length >= 64" @click="addWeekly">添加每周区间</el-button>
              <h3>日期例外 / 节假日</h3><p>具体日期优先于每周策略；必须填写执行商公告或其他依据。输入按当前设备时区，保存为UTC；不能相互重叠。</p>
              <div class="exception" v-for="(e, index) in policy.exceptions" :key="index">
                <div class="toolbar"><input type="datetime-local" :value="localValue(e.start)" @change="setInstant(e, 'start', $event)" aria-label="例外开始" :disabled="saving || !can('market_hours:save')" /><span>至</span>
                  <input type="datetime-local" :value="localValue(e.end)" @change="setInstant(e, 'end', $event)" aria-label="例外结束" :disabled="saving || !can('market_hours:save')" />
                  <el-select v-model="e.closed"><el-option label="休市" :value="true" /><el-option label="开市例外" :value="false" /></el-select><el-button v-permission="'market_hours:save'" @click="removeException(index)">移除</el-button></div>
                <div class="form-grid"><el-input v-model="e.reason" maxlength="200" placeholder="例外原因" /><el-input v-model="e.source" maxlength="500" placeholder="依据：执行商公告网址 / 公告编号" /></div>
              </div>
              <el-button v-permission="'market_hours:save'" :disabled="policy.exceptions.length >= 128" @click="addException">添加日期例外</el-button>
            </el-form>
          </el-card>
        </el-tab-pane>
        <el-tab-pane label="外汇规则与依据" name="research">
          <el-card shadow="never"><h3>采用规则</h3><ul>
            <li>默认采用纽约时间周五17:00至周日17:00休市的参考策略。不是所有执行商统一时间，正式接入须校准。</li>
            <li>UTC+8：夏令时通常周六05:00至周一05:00；标准时通常06:00至06:00。使用America/New_York，不能硬编码北京时间。</li>
            <li>OANDA US示例：周五16:59收市、周日17:05开市，周一至周四每日16:59–17:05暂停；不可当作所有外汇的通用日切。</li>
            <li>TRY和NZD等品种有例外；金银、指数、外汇期货不能直接套现货外汇规则。通过品种覆盖维护实际执行商时段。</li>
            <li>圣诞、元旦等须逐年核验；美国法定假日不意味着外汇整日休市。没有预置未经公告确认的2026年末假期。</li>
            <li>休市阻止新单、撮合及市价平仓；撤单、查看持仓、修改止盈止损不受影响。历史行情保留，报价故障不冒充休市。</li>
          </ul><h3>核验日期：2026-10-03</h3><ul class="sources">
            <li><a href="https://www.oanda.com/us-en/trading/hours-of-operation/" target="_blank" rel="noopener noreferrer">OANDA US · 常规时段、日切与特殊品种</a></li>
            <li><a href="https://www.oanda.com/us-en/trading/holiday-trading-hours/" target="_blank" rel="noopener noreferrer">OANDA US · 当年节假日公告</a></li>
            <li><a href="https://www.fxcm.com/eu/client-portal/" target="_blank" rel="noopener noreferrer">FXCM · 不同执行商的开收市差异</a></li>
            <li><a href="https://www.forex.com/en-sg/help-and-support/market-trading-hours/" target="_blank" rel="noopener noreferrer">FOREX.com · 外汇与金银交易时段区别</a></li>
            <li><a href="https://www.nist.gov/pml/time-and-frequency-division/local-time-faqs" target="_blank" rel="noopener noreferrer">NIST · 美国夏令时规则</a></li>
          </ul></el-card>
        </el-tab-pane>
      </el-tabs>
    </template>
    <el-dialog :model-value="!!manual" :title="manual?.label + ' · ' + (manual?.mode === 'AUTO' ? '恢复自动' : manual?.mode === 'OPEN' ? '手动开市' : '手动休市')" width="min(520px, 94vw)" :close-on-click-modal="false" @close="manual = undefined">
      <template v-if="manual"><el-alert :title="manual.mode === 'OPEN' ? '只覆盖本平台时间策略，不绕过分类手动休市、品种停用或行情有效性。开市最长24小时。' : manual.mode === 'AUTO' ? '清除手动模式，立即按绑定策略判断。' : '分类手动休市会阻止其下所有品种成交；既有订单和资金不会被删除。'" type="warning" :closable="false" />
        <el-form label-position="top" :disabled="saving || !can('market_hours:manual')" class="section" v-if="manual.mode !== 'AUTO'"><el-form-item label="操作原因（必填）"><el-input v-model="manual.reason" maxlength="200" /></el-form-item>
          <el-form-item :label="manual.mode === 'OPEN' ? '失效时间（必填；当前设备时区）' : '失效时间（可选；留空持续休市）'"><el-date-picker v-model="manual.until" type="datetime" placeholder="到期恢复自动" /></el-form-item></el-form>
      </template>
      <template #footer><el-button v-permission="'market_hours:view'" :disabled="saving" @click="manual = undefined">取消</el-button><el-button v-permission="'market_hours:manual'" type="primary" :loading="saving" @click="commitManual">确认切换</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.market-hours { max-width: 1560px; }
header, .toolbar, .actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
header { justify-content: space-between; margin-bottom: 18px; } h2 { margin: 0; } h3 { margin-top: 24px; }
p, li { color: #606266; line-height: 1.8; } p { margin: 8px 0; } small, .as-of { color: #909399; }
.block { display: block; margin-top: 5px; } .section { margin-top: 18px; } .search, .filter { width: 190px; }
.el-select { width: 100%; } .toolbar > .el-select { width: 260px; } .actions { gap: 6px; }.actions .el-button { margin-left: 0; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; } .interval { display: grid; grid-template-columns: 80px 100px 24px 80px 100px 210px minmax(100px,1fr) 70px; gap: 10px; align-items: center; margin-bottom: 12px; }
input { box-sizing: border-box; border: 1px solid #dcdfe6; border-radius: 4px; padding: 7px; font: inherit; min-width: 0; }
.exception { padding: 16px; margin-bottom: 12px; border: 1px solid #ebeef5; border-radius: 6px; } .exception .form-grid { margin-top: 12px; }
.sources a { color: #409eff; } .as-of strong { color: #b88230; }
@media(max-width: 850px) { .form-grid { grid-template-columns: 1fr; }.interval { grid-template-columns: 90px 100px 24px 90px 100px; }.interval .el-input { grid-column: 1 / 5; } header { align-items: start; } }
@media(max-width: 500px) {
  .interval { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .interval > * { min-width: 0; max-width: 100%; }
  .interval > span, .interval .el-input { grid-column: 1 / -1; }
}
</style>
