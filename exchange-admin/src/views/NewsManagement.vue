<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { can } from '@/utils/access'
import request from '@/utils/request'

type Row = Record<string, any>
const api = '/admin/insights/news'
const articles = ref<Row[]>([]), sources = ref<Row[]>([]), audit = ref<Row[]>([])
const loading = ref(false), busy = ref(false), total = ref(0), page = ref(1), dataStatus = ref('')
const category = ref(''), sourceId = ref(''), language = ref(''), hidden = ref(''), from = ref(''), to = ref('')
const editing = ref<Row | null>(null), sourceEditing = ref<Row | null>(null), reason = ref('')
const editOpen = ref(false), sourceOpen = ref(false), auditOpen = ref(false), importOpen = ref(false)
const importSource = ref('FED'), format = ref('RSS'), content = ref(''), material = ref(''), verified = ref(false)
const auditPage = ref(0)
const labels: Record<string, string> = { MACRO: '宏观政策', CRYPTO: '加密索引', FINANCE: '财经索引', OK: '正常', EMPTY: '来源无内容', NEVER_FETCHED: '尚未采集', UNCONFIGURED: '未配置', RATE_LIMITED: '上游限流', BLOCKED: '上游拒绝', ERROR: '采集失败', BUDGET_EXHAUSTED: '预算已用完' }
function error(e: any) { ElMessage.error(e?.message || '操作失败，请刷新并检查权限') }
function safeLink(value: string) { try { const u = new URL(value); return ['http:', 'https:'].includes(u.protocol) && !u.username && !u.password ? u.href : '' } catch { return '' } }
async function load(reset = false) {
  if (reset) page.value = 1
  loading.value = true
  try {
    const params: Row = { page: page.value - 1, size: 20 }
    if (category.value) params.category = category.value
    if (sourceId.value) params.sourceId = sourceId.value
    if (language.value) params.language = language.value
    if (hidden.value) params.hidden = hidden.value === 'true'
    if (from.value) params.from = `${from.value}T00:00:00Z`
    if (to.value) params.to = `${to.value}T23:59:59.999Z`
    const result: any = await request.get(api, { params })
    articles.value = result.content; total.value = result.totalElements; dataStatus.value = result.dataStatus
    sources.value = await request.get(`${api}/sources`) as any
  } catch (e) { error(e) } finally { loading.value = false }
}
async function open(row: Row) { try { editing.value = await request.get(`${api}/${row.articleId}`) as any; reason.value = ''; editOpen.value = true } catch (e) { error(e) } }
function openSource(row: Row) { sourceEditing.value = { ...row }; reason.value = ''; sourceOpen.value = true }
async function saveArticle() {
  if (!editing.value || !reason.value.trim()) return
  busy.value = true
  try { await request.put(`${api}/${editing.value.articleId}`, { category: editing.value.category, hidden: editing.value.hidden, sortOrder: editing.value.sortOrder, rowVersion: editing.value.rowVersion, reason: reason.value }); editOpen.value = false; ElMessage.success('文章设置已保存，公开可见性立即生效'); await load(true) } catch (e) { error(e) } finally { busy.value = false }
}
async function saveSource() {
  if (!sourceEditing.value || !reason.value.trim()) return
  busy.value = true
  try { await request.put(`${api}/sources/${sourceEditing.value.sourceId}`, { enabled: sourceEditing.value.enabled, rowVersion: sourceEditing.value.rowVersion, licenseReviewed: sourceEditing.value.licenseReviewed, licenseEvidence: sourceEditing.value.licenseEvidence, reason: reason.value }); sourceOpen.value = false; ElMessage.success('来源设置已保存，关闭后公开读取立即停止'); await load(true) } catch (e) { error(e) } finally { busy.value = false }
}
async function sync(row: Row) {
  busy.value = true
  try {
    const result: any = await request.post(`${api}/sources/${row.sourceId}/sync`, {}, { timeout: 60000 })
    const current = result.sources.find((s: Row) => s.sourceId === row.sourceId)
    if (!result.requested) ElMessage.info('处于预算、采集租约或退避窗口；未再次请求上游')
    else if (['OK', 'EMPTY'].includes(current?.status)) ElMessage.success(`采集完成，本租户更新 ${result.changed} 条`)
    else ElMessage.warning(`来源状态 ${current?.status || 'ERROR'}，保留旧缓存`)
    await load()
  } catch (e) { error(e) } finally { busy.value = false }
}
async function showAudit(reset = false) { if (reset) auditPage.value = 0; try { audit.value = await request.get(`${api}/audit`, { params: { page: auditPage.value } }) as any; auditOpen.value = true } catch (e) { error(e) } }
async function importData() {
  busy.value = true
  try { const result: any = await request.post(`${api}/import`, { sourceId: importSource.value, format: format.value, content: content.value, material: material.value, verified: verified.value }, { timeout: 60000 }); ElMessage.success(`更新 ${result.changed} 条，跳过 ${result.skipped} 条；新增文章先隐藏待核验`); importOpen.value = false; content.value = ''; verified.value = false; await load(true) } catch (e) { error(e) } finally { busy.value = false }
}
onMounted(() => load())
</script>

<template>
  <section class="news-management" aria-label="外部财经新闻管理">
    <header><div><h2>外部财经新闻</h2><p>官方宏观新闻与可关闭的加密标题索引；不是平台公告，也不是全市场实时快讯。</p></div><div class="actions"><el-button @click="showAudit(true)">操作审计</el-button><el-button v-if="can('news:import')" @click="importOpen = true" :disabled="busy">核验导入</el-button></div></header>
    <el-alert title="仅展示标题、允许的简述、来源与原文链接；不抓全文、图片或自动翻译。缺少发布时间保持未知，12 小时延迟不能标为实时。Key 只在后端配置。" type="info" :closable="false" />
    <details class="sources" open><summary>来源开关、许可与采集状态（{{ sources.length }} 个固定来源）</summary><div class="scroll"><table><caption>集中采集共享预算；用户只读已保存内容</caption><thead><tr><th>来源与归属</th><th>启用 / 配置</th><th>状态 / HTTP</th><th>成功 / 下次 UTC</th><th>预算 / 内容</th><th>许可 / 操作</th></tr></thead><tbody><tr v-for="s in sources" :key="s.sourceId" :data-source="s.sourceId"><td>{{ s.sourceId }}<br>{{ s.name }}<br><a :href="safeLink(s.feedUrl)" target="_blank" rel="noopener noreferrer">固定订阅入口</a></td><td>{{ s.enabled ? '已启用' : '已关闭' }}<br>{{ s.configured ? '后端已配置' : '后端未配置' }}<br>{{ s.schedulerEnabled ? '定时采集开启' : '定时采集关闭' }}<br><span v-if="s.delayHours">至少 {{ s.delayHours }} 小时延迟</span></td><td>{{ labels[s.status] || s.status }} / {{ s.httpStatus ?? '—' }}<br>{{ s.stale ? '缓存过期或尚无缓存' : '最近检查有效' }}<br>{{ s.lastError || '—' }}</td><td>{{ s.lastSuccess || '尚无成功采集' }}<br>{{ s.nextAttempt || '尚未安排' }}</td><td>{{ s.requestsToday }} / {{ s.dailyBudget }}（{{ s.budgetDate || '未使用' }}）<br>有效 {{ s.itemCount }} / 跳过 {{ s.skippedCount }}</td><td><a :href="safeLink(s.licenseUrl)" target="_blank" rel="noopener noreferrer">官方使用说明</a><p>{{ s.licenseScope }}</p><div class="actions"><el-button v-if="can('news:source')" :disabled="busy" @click="openSource(s)">管理 {{ s.sourceId }}</el-button><el-button v-if="can('news:sync')" :disabled="busy || !s.effectiveEnabled" @click="sync(s)">同步 {{ s.sourceId }}</el-button></div></td></tr></tbody></table></div></details>
    <div class="filters"><label>分类<select v-model="category" aria-label="新闻分类"><option value="">全部</option><option v-for="v in ['MACRO','CRYPTO','FINANCE']" :key="v" :value="v">{{ labels[v] }}</option></select></label><label>来源<select v-model="sourceId" aria-label="新闻来源"><option value="">全部</option><option v-for="s in sources" :key="s.sourceId" :value="s.sourceId">{{ s.sourceId }}</option></select></label><label>语言<input v-model="language" maxlength="16" placeholder="如 en，留空为全部" aria-label="新闻语言"></label><label>可见性<select v-model="hidden" aria-label="文章可见性"><option value="">全部</option><option value="false">公开</option><option value="true">隐藏</option></select></label><label>发布时间起 UTC<input v-model="from" type="date" aria-label="发布时间起"></label><label>发布时间止 UTC<input v-model="to" type="date" aria-label="发布时间止"></label><el-button :loading="loading" @click="load(true)">查询新闻</el-button></div>
    <p>缓存状态：{{ dataStatus }}。时间筛选只匹配已知发布时间，不把索引发现时间冒充发布时间。</p>
    <div v-loading="loading" class="scroll"><table><caption>当前租户与环境 · {{ total }} 条</caption><thead><tr><th>标题 / 来源 / 稳定 ID</th><th>发布时间 / 首次发现 UTC</th><th>分类 / 可见性 / 缓存</th><th>操作</th></tr></thead><tbody><tr v-for="row in articles" :key="row.articleId" :data-article="row.articleId"><td>{{ row.title }}<br><small>{{ row.publisher }} · {{ row.language }} · {{ row.articleId }}</small></td><td>{{ row.publishedAt || '发布时间未知' }}<br><small>首次发现 {{ row.discoveredAt }}<br>更新 {{ row.updatedAt }}</small><br><strong v-if="row.delayed">{{ row.delayHours }} 小时延迟</strong></td><td>{{ labels[row.category] }} · {{ row.hidden ? '隐藏' : '公开' }} · {{ row.environment }}<br>{{ row.stale ? '旧缓存' : '最近检查有效' }} · {{ row.sourceStatus }} · 排序 {{ row.sortOrder }}</td><td><div class="actions"><el-button @click="open(row)">查看 / 维护</el-button><a :href="safeLink(row.originalUrl)" target="_blank" rel="noopener noreferrer">阅读原文</a></div></td></tr><tr v-if="!articles.length && !loading"><td colspan="4">当前条件没有新闻。官方宏观来源不提供加密快讯，不会生成假内容。</td></tr></tbody></table></div>
    <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next, total" @current-change="load()" />
    <el-dialog v-model="editOpen" title="文章核验与展示设置" width="min(800px, 96vw)" :close-on-click-modal="false"><div v-if="editing"><h3>{{ editing.title }}</h3><p>{{ editing.publisher }} · {{ editing.environment }} · {{ editing.articleId }}</p><p>{{ editing.attribution }}</p><p>{{ editing.summary || '来源未提供可展示简述' }}</p><p>发布时间：{{ editing.publishedAt || '未知' }}；首次发现：{{ editing.discoveredAt }}。</p><a :href="safeLink(editing.originalUrl)" target="_blank" rel="noopener noreferrer">阅读原文（独立窗口）</a><el-form label-position="top" :disabled="busy || !can('news:edit')"><el-form-item label="分类"><el-select v-model="editing.category"><el-option v-for="v in editing.sourceId === 'FED' || editing.sourceId === 'BEA' || editing.sourceId === 'ECB' ? ['MACRO'] : ['CRYPTO','FINANCE']" :key="v" :label="labels[v]" :value="v" /></el-select></el-form-item><el-form-item label="推荐排序（-10000 至 10000，不是业绩排行）"><el-input-number v-model="editing.sortOrder" :min="-10000" :max="10000" /></el-form-item><el-checkbox v-model="editing.hidden">隐藏文章（立即停止公开列表及详情读取）</el-checkbox><el-form-item label="核验修改依据"><el-input v-model="reason" type="textarea" maxlength="1000" aria-label="文章核验依据" /></el-form-item></el-form><el-button v-if="can('news:edit')" type="primary" :disabled="busy || !reason.trim()" @click="saveArticle">保存文章设置</el-button></div></el-dialog>
    <el-dialog v-model="sourceOpen" title="来源与许可设置" width="min(800px, 96vw)" :close-on-click-modal="false"><div v-if="sourceEditing"><h3>{{ sourceEditing.sourceId }} · {{ sourceEditing.name }}</h3><p>固定上游不可改为任意 URL。来源关闭后已保存内容保留，但公开接口立即不再显示。</p><p v-if="!sourceEditing.configured">本来源后端配置尚未满足。NewsData 需要 Key、账户权益与许可记录；GDELT 需要显式后端开关。</p><el-form label-position="top"><el-checkbox v-model="sourceEditing.enabled" :disabled="busy || !sourceEditing.configured">启用本租户当前环境来源</el-checkbox><el-checkbox v-model="sourceEditing.licenseReviewed" :disabled="busy">已核对当前内容使用条件及账户权益</el-checkbox><el-form-item label="许可核验记录（不是 Key）"><el-input v-model="sourceEditing.licenseEvidence" type="textarea" maxlength="1000" aria-label="来源许可记录" /></el-form-item><el-form-item label="变更依据"><el-input v-model="reason" type="textarea" maxlength="1000" aria-label="来源变更依据" /></el-form-item></el-form><el-button type="primary" :disabled="busy || !reason.trim() || !sourceEditing.licenseEvidence?.trim() || (sourceEditing.enabled && !sourceEditing.licenseReviewed)" @click="saveSource">保存来源设置</el-button></div></el-dialog>
    <el-dialog v-model="importOpen" title="导入合法官方 RSS/Atom（新增先隐藏）" width="min(800px, 96vw)" :close-on-click-modal="false"><el-form label-position="top"><el-form-item label="官方来源"><el-select v-model="importSource"><el-option v-for="s in ['FED','BEA','ECB']" :key="s" :value="s" /></el-select></el-form-item><el-form-item label="格式"><el-select v-model="format"><el-option value="RSS" /><el-option value="ATOM" /></el-select></el-form-item><el-form-item label="已核验订阅材料"><el-input v-model="content" type="textarea" :rows="8" aria-label="新闻材料" /></el-form-item><el-form-item label="材料来源与核验依据"><el-input v-model="material" type="textarea" maxlength="1000" aria-label="新闻材料依据" /></el-form-item><el-checkbox v-model="verified">材料来自合法官方渠道，已检查许可与归属</el-checkbox></el-form><el-button type="primary" :disabled="busy || !verified || !content.trim() || !material.trim()" @click="importData">确认核验导入</el-button></el-dialog>
    <el-dialog v-model="auditOpen" title="新闻操作审计（每页 50 条）" width="min(900px, 96vw)"><details v-for="a in audit" :key="a.id"><summary>{{ a.capturedAt }} · {{ a.actor }} · {{ a.action }} · {{ a.reason }}</summary><h4>修改前</h4><pre>{{ a.beforeJson || '新增记录' }}</pre><h4>修改后</h4><pre>{{ a.afterJson }}</pre></details><p v-if="!audit.length">暂无操作记录</p><div class="actions"><el-button :disabled="auditPage === 0" @click="auditPage--; showAudit()">上一页</el-button><span>第 {{ auditPage + 1 }} 页</span><el-button :disabled="audit.length < 50" @click="auditPage++; showAudit()">下一页</el-button></div></el-dialog>
  </section>
</template>

<style scoped>
.news-management { --el-color-primary: #47633d; --el-color-primary-light-9: #f2f5ed; --el-color-primary-dark-2: #344d2c; min-width: 0; padding: 16px; color: #252a30; background: white; border-radius: 8px; }
header, .actions, .filters { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; } header { justify-content: space-between; } h2 { margin: 0; } p, small, h3 { overflow-wrap: anywhere; } small, p { color: #59616b; } .sources, .filters { margin: 20px 0; } summary { cursor: pointer; padding: 14px 0; overflow-wrap: anywhere; } .scroll { overflow-x: auto; max-width: 100%; } table { border-collapse: collapse; width: 100%; min-width: 960px; text-align: left; } caption { text-align: left; padding: 10px 0; } th, td { padding: 12px; vertical-align: top; border-bottom: 1px solid #e9edef; max-width: 340px; overflow-wrap: anywhere; } th { background: #f2f5ed; } a { color: #344d2c; text-decoration: underline; } .filters label { display: grid; gap: 6px; } input, select { min-height: 44px; max-width: 100%; padding: 8px; border: 1px solid #d9dfd5; border-radius: 5px; background: white; } pre { max-height: 350px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; background: #f2f5ed; padding: 12px; }
:deep(.el-button) { min-height: 44px; margin-left: 0; white-space: normal; } :deep(.el-pagination) { flex-wrap: wrap; margin-top: 16px; } :deep(.el-pagination button), :deep(.el-pager li) { min-width: 44px; min-height: 44px; } :deep(.el-dialog__body) { overflow: auto; } :deep(.el-dialog__headerbtn) { width: 44px; height: 44px; } :deep(.el-checkbox) { min-height: 44px; height: auto; white-space: normal; } :deep(.el-checkbox__label) { white-space: normal; } :deep(.el-input__wrapper), :deep(.el-select__wrapper) { min-height: 44px; }
button:focus-visible, input:focus-visible, select:focus-visible, summary:focus-visible, a:focus-visible { outline: 2px solid #47633d; outline-offset: 3px; }
@media(max-width: 600px) { .news-management { padding: 12px; } .filters label { width: 100%; } }
</style>
