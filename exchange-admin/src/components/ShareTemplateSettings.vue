<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
import { can } from '@/utils/access'
import { useTenantPolicies } from '@/composables/useTenantPolicies'
import TenantPolicyNotice from '@/components/TenantPolicyNotice.vue'
import ShareTemplatePreview from './ShareTemplatePreview.vue'
import { applyShareFocus, createShareDesign, parseShareTemplateConfig, shareTemplateCatalog as catalog, shareTemplateLanguages as languages,
  type ShareTemplateRule } from '../../../exchange-frontend/src/utils/shareTemplateDesign'
const ShareTemplateEditor = defineAsyncComponent(() => import('./ShareTemplateEditor.vue'))
const { snapshot, policyError, reloadPolicies, editable } = useTenantPolicies('share-templates')
type Row = ShareTemplateRule & { universal: boolean }
const rows = ref<Row[]>([]), previewLanguage = ref('zh-TW')
const loading = ref(false), saving = ref(false), loaded = ref(false), dirty = ref(false), editor = ref<ShareTemplateRule | null>(null)
const canEdit = computed(() => editable('share.templates') && can('share_templates:save') && !saving.value)
const available = (language: string) => rows.value.filter(row => row.enabled && (row.universal || row.languages.includes(language)))
const previewRows = computed(() => available(previewLanguage.value))
const missingLanguages = computed(() => languages.filter(([id]) => !available(id).length).map(([, name]) => name))
let revision = 0
async function load() {
  const run = ++revision; loading.value = true; loaded.value = false
  try {
    await reloadPolicies()
    const result = await request.get('/admin/config/get', { params: { key: 'share.templates' } }) as unknown as { value: string | null }
    const config = parseShareTemplateConfig(result.value)
    if (run !== revision) return
    const missing = catalog.filter(([id]) => !config.templates.some(row => row.id === id)).map(([id, name]) =>
      ({ id, name, base: id, focus: config.focus, enabled: false, languages: ['*'] } as ShareTemplateRule))
    rows.value = [...config.templates, ...missing].map(row => ({ ...row, universal: row.languages.includes('*') }))
    loaded.value = true; dirty.value = false
  } catch (e: any) { if (run === revision) ElMessage.error(e.message || '加载分享模板失败，请重试') }
  finally { if (run === revision) loading.value = false }
}
function move(index: number, offset: number) {
  if (!canEdit.value) return
  const row = rows.value.splice(index, 1)[0]!
  rows.value.splice(index + offset, 0, row); dirty.value = true
}
function rule(row: Row): ShareTemplateRule {
  return { id: row.id, name: row.name.trim(), base: row.base, enabled: row.enabled, focus: row.focus,
    languages: row.universal ? ['*'] : row.languages.filter(code => code !== '*'), ...(row.design ? { design: row.design } : {}) }
}
function edit(row?: Row, duplicate = false) {
  if ((duplicate || !row) && !canEdit.value) return
  if ((duplicate || !row) && rows.value.length >= 32) { ElMessage.warning('最多保留 32 款模板，请先删除自定义模板'); return }
  const value: ShareTemplateRule = row ? rule(row) : { id: '', name: '新分享模板', base: 'light', languages: [previewLanguage.value], focus: 'rate', enabled: true }
  if (!row || duplicate) { value.id = `custom-${crypto.randomUUID()}`; if (duplicate) value.name += ' · 副本'; value.design ||= createShareDesign(value.base, value.focus) }
  editor.value = JSON.parse(JSON.stringify(value))
}
function apply(value: ShareTemplateRule) {
  if (!canEdit.value) return
  const index = rows.value.findIndex(row => row.id === value.id), row = { ...value, universal: value.languages.includes('*') }
  if (index < 0) rows.value.unshift(row); else rows.value[index] = row
  dirty.value = true; editor.value = null; ElMessage.success('排版已应用，请保存模板配置后发布')
}
async function remove(row: Row) {
  if (!canEdit.value || !row.id.startsWith('custom-')) return
  try { await ElMessageBox.confirm(`删除模板「${row.name}」？保存配置后生效。`, '删除模板'); rows.value = rows.value.filter(item => item !== row); dirty.value = true } catch { /* Cancel keeps the template. */ }
}
function changeFocus(row: Row, value: 'amount' | 'rate') {
  if (!canEdit.value || row.focus === value) return
  if (row.design) applyShareFocus(row.design)
  row.focus = value; dirty.value = true
}
function changeScope(row: Row, universal: boolean) {
  if (!canEdit.value) return
  row.languages = universal ? ['*'] : row.languages.filter(code => code !== '*'); dirty.value = true
}
async function save() {
  if (!canEdit.value || !loaded.value) return
  if (rows.value.some(row => row.enabled && !row.universal && !row.languages.filter(code => code !== '*').length)) { ElMessage.warning('请为启用模板绑定语言'); return }
  if (missingLanguages.value.length) { ElMessage.warning(`以下语言没有可用模板：${missingLanguages.value.join('、')}`); return }
  try {
    const value = JSON.stringify({ version: 3, templates: rows.value.map(row => {
      const value = rule(row); if (!value.enabled && !value.languages.length) value.languages = ['*']; return value
    }) })
    parseShareTemplateConfig(value)
    if (new TextEncoder().encode(value).length > 60000) throw new Error('模板配置过大，请减少模板数量')
    saving.value = true
    await request.post('/admin/config/save', { key: 'share.templates', value, description: '持仓分享模板：固定字段排版、独立重心与语言绑定' })
    dirty.value = false; ElMessage.success('模板已发布，手机端与 PC 端下次打开分享时生效')
  } catch (e: any) { ElMessage.error(e.message || '保存失败，配置未发布') }
  finally { saving.value = false }
}
onMounted(load)
onBeforeUnmount(() => { revision++ })
</script>

<template>
  <section v-loading="loading" class="share-settings-page">
    <div class="settings-heading"><div><h1>持仓分享模板</h1><p>手机端与 PC 端共用；按语言筛选，首款可用模板为默认。每款模板独立设置展示重心与排版。</p></div><div><el-tag v-if="dirty" type="warning">有未发布修改</el-tag><el-button v-permission="'share_templates:save'" :disabled="!loaded || !canEdit" @click="edit()">新增模板</el-button><el-button v-permission="'share_templates:save'" type="primary" :disabled="!loaded || !editable('share.templates')" :loading="saving" @click="save">保存模板配置</el-button></div></div>
    <TenantPolicyNotice :snapshot="snapshot" :error="policyError" />
    <el-button v-if="!loaded" v-permission="'share_templates:view'" :disabled="loading" @click="load">重新加载</el-button>
    <template v-else>
      <el-alert title="固定数据盒子可调整位置、大小、字体与颜色；支持形状、图片素材和图层排序。数据由订单 / 当前用户接口填充，预览不创建订单。合约模板显示杠杆。" type="info" :closable="false" />
      <div class="language-preview"><span>语言预览：</span><el-select v-model="previewLanguage" aria-label="语言预览" style="width:180px"><el-option v-for="[id,name] in languages" :key="id" :label="name" :value="id" /></el-select><span>默认：{{ previewRows[0]?.name || '无可用模板' }}</span></div>
      <el-alert v-if="missingLanguages.length" :title="`未覆盖语言：${missingLanguages.join('、')}`" type="warning" :closable="false" />
      <admin-table table-key="ShareTemplateSettings.1" :data="rows" row-key="id">
        <el-table-column label="图片预览" width="120"><template #default="{row}"><ShareTemplatePreview :template="row.base" :name="row.name" :language="previewLanguage" :focus="row.focus" :design="row.design" /></template></el-table-column>
        <el-table-column label="模板" min-width="150"><template #default="{row}"><strong>{{ row.name }}</strong><div class="template-id">{{ row.id }}</div><el-tag size="small" :type="row.design?'success':'info'">{{ row.design?'自定义排版':'内置排版' }}</el-tag></template></el-table-column>
        <el-table-column label="启用" width="75"><template #default="{row}"><el-switch v-permission="'share_templates:save'" v-model="row.enabled" :disabled="!canEdit" :aria-label="`启用${row.name}`" @change="dirty=true" /></template></el-table-column>
        <el-table-column label="绑定语言" min-width="240"><template #default="{row}"><el-checkbox v-model="row.universal" :disabled="!canEdit" @change="changeScope(row,$event as boolean)">全语言通用</el-checkbox><el-select v-if="!row.universal" v-model="row.languages" multiple collapse-tags collapse-tags-tooltip :disabled="!canEdit" :aria-label="`${row.name}适用语言`" placeholder="选择语言" style="width:100%" @change="dirty=true"><el-option v-for="[id,name] in languages" :key="id" :label="name" :value="id" /></el-select></template></el-table-column>
        <el-table-column label="展示重心" min-width="180"><template #default="{row}"><el-radio-group :model-value="row.focus" :disabled="!canEdit" size="small" :aria-label="`${row.name}展示重心`" @update:model-value="changeFocus(row,$event as 'amount'|'rate')"><el-radio-button value="amount">盈亏</el-radio-button><el-radio-button value="rate">收益率</el-radio-button></el-radio-group></template></el-table-column>
        <el-table-column label="排序与默认" min-width="190"><template #default="{row,$index}"><el-button v-permission="'share_templates:save'" size="small" :disabled="!canEdit || $index===0" @click="move($index,-1)">上移</el-button><el-button v-permission="'share_templates:save'" size="small" :disabled="!canEdit || $index===rows.length-1" @click="move($index,1)">下移</el-button><el-button v-permission="'share_templates:save'" size="small" :disabled="!canEdit || !previewRows.includes(row) || previewRows[0]===row" @click="move($index,-$index)">{{ previewRows[0]===row?'当前语言默认':'设为默认' }}</el-button></template></el-table-column>
        <el-table-column label="编辑与预览" min-width="210"><template #default="{row}"><el-button v-permission="'share_templates:view'" type="primary" link @click="edit(row)">{{ canEdit?'编辑 / 试填预览':'试填预览' }}</el-button><el-button v-permission="'share_templates:save'" link :disabled="!canEdit" @click="edit(row,true)">复制</el-button><el-button v-if="row.id.startsWith('custom-')" v-permission="'share_templates:save'" link type="danger" :disabled="!canEdit" @click="remove(row)">删除</el-button></template></el-table-column>
      </admin-table>
    </template>
    <el-dialog :model-value="!!editor" width="min(1500px,98vw)" top="2vh" append-to-body destroy-on-close :show-close="false" :close-on-click-modal="false" class="share-editor-dialog" @update:model-value="!$event && (editor=null)">
      <ShareTemplateEditor v-if="editor" :rule="editor" :editable="canEdit" :materials-editable="editable('share.materials')" :language="previewLanguage" @apply="apply" @close="editor=null" />
    </el-dialog>
  </section>
</template>

<style scoped>
.share-settings-page{padding:22px;background:#fff;border-radius:10px}.settings-heading{display:flex;justify-content:space-between;align-items:center;gap:20px;margin-bottom:18px}.settings-heading h1{margin:0;font-size:22px}.settings-heading p{font-size:13px;color:#77808e;line-height:1.6}.settings-heading>div:last-child{display:flex;align-items:center;gap:10px;flex-shrink:0}.settings-heading .el-button+.el-button{margin:0}.language-preview{display:flex;align-items:center;gap:14px;margin:18px 0;font-size:13px}.template-id{font-size:10px;color:#9aa4b1;word-break:break-all;margin:5px 0}@media(max-width:850px){.share-settings-page{padding:14px}.settings-heading{align-items:flex-start;flex-direction:column}.language-preview{flex-wrap:wrap}}
</style>
