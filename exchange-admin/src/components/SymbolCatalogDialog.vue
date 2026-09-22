<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import { getImageUrl } from '@/utils/imageUrl'

const props = defineProps<{ modelValue: boolean; sources: any[]; categories: any[] }>()
const emit = defineEmits(['update:modelValue', 'added'])
const visible = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const source = ref('binance'), sourceCategory = ref('Crypto'), projectCategory = ref('')
const query = ref(''), page = ref(0), rows = ref<any[]>([]), selected = ref<string[]>([])
const busy = ref(false), saving = ref(false), error = ref(''), hasMore = ref(false), total = ref<number | null>(null)
const sourceCategories = computed(() => props.sources.find(s => s.value === source.value)?.categories || [])
const matches = computed(() => props.categories.filter(c => c.marketSource === source.value && c.sourceCategory === sourceCategory.value))
let generation = 0
async function load() {
  const current = ++generation
  busy.value = true; error.value = ''; rows.value = []; hasMore.value = false
  try {
    const result: any = await request.get('/admin/symbols/catalog', { params: { source: source.value, sourceCategory: sourceCategory.value, query: query.value, page: page.value }, timeout: 30000 })
    if (current !== generation) return
    rows.value = result.list; hasMore.value = result.hasMore; total.value = result.total
    selected.value = selected.value.filter(code => !rows.value.some(row => row.symbol === code && row.added))
  } catch (e: any) { if (current === generation) error.value = e?.message || '目录加载失败，请重试' }
  finally { if (current === generation) busy.value = false }
}
function changeCategory() {
  selected.value = []; page.value = 0
  projectCategory.value = matches.value.length === 1 ? matches.value[0].key : ''
  query.value = source.value === 'yahoo' ? ({ US: 'A', Forex: 'USD', CFD: '^', Oil: 'oil' } as Record<string,string>)[sourceCategory.value] || '' : ''
  load()
}
function changeSource() { sourceCategory.value = sourceCategories.value[0]?.value || ''; changeCategory() }
function search() { page.value = 0; load() }
function turn(delta: number) { page.value += delta; load() }
function select(row: any, checked: unknown) {
  if (row.added || row.unavailableReason) return
  if (checked && selected.value.length >= 20) { ElMessage.warning('每次最多添加 20 个交易对'); return }
  selected.value = checked ? [...selected.value, row.symbol] : selected.value.filter(code => code !== row.symbol)
}
async function add() {
  if (saving.value || !projectCategory.value || !selected.value.length) return
  saving.value = true
  try {
    const result: any = await request.post('/admin/symbols/catalog/add', { source: source.value, sourceCategory: sourceCategory.value, projectCategory: projectCategory.value, symbols: selected.value }, { timeout: 120000 })
    ElMessage.success(`已添加 ${result.added.length} 个，已存在 ${result.existing.length} 个`)
    selected.value = []; emit('added'); await load()
  } catch (e: any) { error.value = e?.message || '添加失败，请重试' }
  finally { saving.value = false }
}
watch(() => props.modelValue, value => { if (value) changeCategory(); else ++generation })
</script>

<template>
  <el-dialog v-model="visible" title="从行情源添加交易对" top="4vh" :style="{ maxHeight: '92vh', overflowY: 'auto' }" width="min(1000px, 96vw)" :close-on-click-modal="!saving" :show-close="!saving" :close-on-press-escape="!saving">
    <el-form label-position="top" class="catalog-selects" :disabled="saving">
      <el-form-item label="行情源"><el-select v-model="source" aria-label="行情源" @change="changeSource"><el-option v-for="item in sources" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
      <el-form-item label="源分类"><el-select v-model="sourceCategory" aria-label="源分类" @change="changeCategory"><el-option v-for="item in sourceCategories" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
      <el-form-item label="添加到项目分类（可手动修改）" required><el-select v-model="projectCategory" aria-label="项目分类" placeholder="请选择项目分类"><el-option v-for="item in categories" :key="item.key" :label="item.label" :value="item.key" /></el-select></el-form-item>
    </el-form>
    <p class="catalog-hint">{{ matches.length === 1 ? `分类绑定默认匹配：${matches[0].label}` : '当前源分类未唯一绑定项目分类，请手动选择。' }}</p>
    <el-alert :title="source === 'yahoo' ? 'Yahoo 搜索结果：按关键词查询，非全市场完整名录。' : 'Binance 可交易名录：支持搜索、翻页和多选。'" type="info" :closable="false" />
    <div class="catalog-search"><el-input v-model="query" aria-label="搜索交易对" placeholder="交易对或名称，如 BTC、AAPL、EURUSD=X" clearable :disabled="saving" @keyup.enter="search" /><el-button @click="search" :disabled="saving" :loading="busy">查询</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon><el-button link @click="load" :disabled="saving">重试加载</el-button></el-alert>
    <el-table :data="rows" v-loading="busy" height="330" row-key="symbol" border :empty-text="error ? '加载失败，请重试' : '没有匹配结果，请更换关键词'">
      <el-table-column prop="symbol" label="交易对" min-width="180"><template #default="{ row }"><span style="display:flex;align-items:center;gap:8px"><img v-if="row.iconUrl" :src="getImageUrl(row.iconUrl)" alt="" width="28" height="28" />{{ row.symbol }}</span></template></el-table-column><el-table-column prop="name" label="名称" min-width="200" /><el-table-column prop="exchange" label="市场" min-width="110" />
      <el-table-column label="选择" min-width="150" fixed="right"><template #default="{ row }"><el-checkbox :model-value="row.added || selected.includes(row.symbol)" :disabled="saving || row.added || !!row.unavailableReason" :aria-label="row.symbol" @change="select(row, $event)">{{ row.added ? '已添加' : row.unavailableReason || '选择' }}</el-checkbox></template></el-table-column>
    </el-table>
    <div class="catalog-pages"><span>第 {{ page + 1 }} 页{{ total !== null ? ` · 共 ${total} 个交易对` : '' }} · 已选 {{ selected.length }} / 20</span><div><el-button :disabled="page === 0 || busy || saving" @click="turn(-1)">上一页</el-button><el-button :disabled="!hasMore || busy || saving" @click="turn(1)">下一页</el-button></div></div>
    <p v-if="selected.length" class="catalog-hint">已选：{{ selected.join('、') }} <el-button link :disabled="saving" @click="selected = []">清空选择</el-button></p>
    <template #footer><el-button :disabled="saving" @click="visible = false">关闭</el-button><el-button type="primary" :disabled="busy || !selected.length || !projectCategory" :loading="saving" @click="add">添加选中（{{ selected.length }}）</el-button></template>
  </el-dialog>
</template>
<style scoped>
.catalog-selects { display: grid; grid-template-columns: 1fr 1fr 1.4fr; gap: 16px; }
.catalog-selects .el-select { width: 100%; }
.catalog-search, .catalog-pages { display: flex; gap: 12px; align-items: center; margin: 16px 0; }
.catalog-pages { justify-content: space-between; flex-wrap: wrap; }
.catalog-hint { color: #606266; font-size: 13px; overflow-wrap: anywhere; }
@media (max-width: 640px) { .catalog-selects { grid-template-columns: 1fr; gap: 0; } }
</style>
