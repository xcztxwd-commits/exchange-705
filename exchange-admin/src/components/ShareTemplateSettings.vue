<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import ShareTemplatePreview from './ShareTemplatePreview.vue'

const catalog = [
  ['light', '清爽白'], ['dark', '专业黑'], ['chart', '行情图'], ['gold', '黑金'],
  ['globe', '环球'], ['architecture', '建筑'], ['city', '城市'], ['referenceGold', '黑金原版'],
  ['referenceWhite', '白色原版'], ['referenceTerminal', '行情原版'], ['launch', '启航'],
  ['aurora', '极光'], ['racing', '竞速'], ['receipt', '交易票据'], ['journal', '交易日志'], ['voyage', '旅程'],
] as const
const languages = [
  ['zh-TW', '中文（繁体）'], ['en', '英语'], ['fr', '法语'], ['de', '德语'], ['ru', '俄语'],
  ['es', '西班牙语'], ['pt', '葡萄牙语'], ['it', '意大利语'], ['ar', '阿拉伯语'], ['tr', '土耳其语'],
  ['id', '印度尼西亚语'], ['my', '缅甸语'], ['hi', '印地语'], ['cs', '捷克语'], ['pl', '波兰语'],
  ['ja', '日语'], ['ko', '韩语'], ['th', '泰语'], ['vi', '越南语'],
] as const
const rows = ref(catalog.map(([id, name]) => ({ id: String(id), name, enabled: true, universal: true, languages: [] as string[] })))
const previewLanguage = ref('zh-TW')
const focus = ref<'amount' | 'rate'>('amount')
const available = (language: string) => rows.value.filter(row => row.enabled && (row.universal || row.languages.includes(language)))
const previewRows = computed(() => available(previewLanguage.value))
const missingLanguages = computed(() => languages.filter(([id]) => !available(id).length).map(([, name]) => name))
const loading = ref(false), saving = ref(false), loaded = ref(false)
async function load() {
  loading.value = true
  loaded.value = false
  try {
    const result = await request.get('/admin/config/get', { params: { key: 'share.templates' } }) as unknown as { value: string | null }
    let rules: { id: string; languages: string[] }[]
    if (result.value?.trim().startsWith('{')) {
      const config = JSON.parse(result.value)
      if (config.version !== 2 || !Array.isArray(config.templates)) throw new Error('模板配置无效')
      if (config.focus !== undefined && !['amount', 'rate'].includes(config.focus)) throw new Error('展示重心配置无效')
      focus.value = config.focus || 'amount'
      rules = config.templates
    } else rules = (result.value == null ? catalog.map(([id]) => String(id)) : result.value.split(',')).map(id => ({ id, languages: ['*'] }))
    const ids = rules.map(row => row.id)
    if (!ids.length || ids.some(id => !catalog.some(([key]) => key === id)) || new Set(ids).size !== ids.length
      || rules.some(row => !Array.isArray(row.languages) || !row.languages.length || new Set(row.languages).size !== row.languages.length
        || row.languages.some(language => language !== '*' && !languages.some(([key]) => key === language))
        || (row.languages.includes('*') && row.languages.length !== 1))) throw new Error('模板配置无效')
    rows.value = [...ids, ...catalog.map(([id]) => id).filter(id => !ids.includes(id))].map(id => {
      const rule = rules.find(row => row.id === id)
      return { id, name: catalog.find(([key]) => key === id)![1], enabled: !!rule,
        universal: !rule || rule.languages.includes('*'), languages: rule?.languages.filter(language => language !== '*') || [] }
    })
    loaded.value = true
  } catch (e: any) { ElMessage.error(e?.message || '加载分享模板失败，请重试') }
  finally { loading.value = false }
}
function move(index: number, offset: number) {
  const row = rows.value.splice(index, 1)[0]!
  rows.value.splice(index + offset, 0, row)
}
async function save() {
  const enabled = rows.value.filter(row => row.enabled)
  if (enabled.some(row => !row.universal && !row.languages.length)) { ElMessage.warning('请为每款启用模板选择语言，或勾选全语言通用'); return }
  if (missingLanguages.value.length) { ElMessage.warning(`以下语言没有可用模板：${missingLanguages.value.join('、')}。请至少保留一款全语言通用模板或补齐设置。`); return }
  const value = JSON.stringify({ version: 2, focus: focus.value, templates: enabled.map(row => ({ id: row.id, languages: row.universal ? ['*'] : row.languages })) })
  saving.value = true
  try {
    await request.post('/admin/config/save', { key: 'share.templates', value, description: '历史持仓分享模板：按语言范围筛选，首款匹配模板为该语言默认模板' })
    ElMessage.success('已保存，用户下次打开分享弹窗时生效')
  } catch (e: any) { ElMessage.error(e?.message || '保存失败') }
  finally { saving.value = false }
}
onMounted(load)
</script>

<template>
  <section v-loading="loading">
    <p>手机端和 PC 端共用配置。先按页面语言筛选，再按下表排序；首款匹配模板为该语言默认模板。用户选择按语言记忆，失效时自动使用默认模板。排序为全局顺序，移动模板会影响其所有适用语言。</p>
    <el-button v-permission="'settings:view'" v-if="!loaded" :disabled="loading" @click="load">重新加载</el-button>
    <template v-else>
      <el-alert title="全语言通用指模板适用范围，不是固定文案语言。所有标题、背景文字和数据标签始终跟随当前页面语言。取消勾选后，可选择一种或多种语言。" type="info" :closable="false" />
      <div style="margin: 18px 0">
        <span>分享图展示重心：</span>
        <el-radio-group v-model="focus" :disabled="saving" aria-label="分享图展示重心">
          <el-radio-button value="amount">盈亏为重心</el-radio-button>
          <el-radio-button value="rate">收益率为重心</el-radio-button>
        </el-radio-group>
        <p>应用于全部模板。重心数据置于上方并放大；另一项在下方显示。正数绿色、负数红色、零值灰色。收益率无法计算时，优先展示盈亏。不显示保证金、投入金额、手续费或订单编号。</p>
      </div>
      <div style="margin: 18px 0">
        <span>语言预览：</span>
        <el-select v-model="previewLanguage" aria-label="语言预览" style="width: 180px">
          <el-option v-for="[id, name] in languages" :key="id" :label="name" :value="id" />
        </el-select>
        <p>默认：{{ previewRows[0]?.name || '无可用模板' }}；可用顺序：{{ previewRows.map(row => row.name).join('、') || '无' }}</p>
        <el-alert v-if="missingLanguages.length" :title="`未覆盖语言：${missingLanguages.join('、')}`" type="warning" :closable="false" />
      </div>
      <admin-table table-key="ShareTemplateSettings.1" :data="rows" row-key="id">
        <el-table-column label="图片预览" width="120">
          <template #default="{ row }">
            <ShareTemplatePreview :template="row.id" :name="row.name" :language="previewLanguage" :focus="focus" />
          </template>
        </el-table-column>
        <el-table-column prop="name" label="模板" min-width="150" />
        <el-table-column prop="id" label="编号" min-width="150" />
        <el-table-column label="启用" width="90">
          <template #default="{ row }"><el-switch v-permission="'settings:view'" v-model="row.enabled" :disabled="saving" :aria-label="`启用${row.name}`" /></template>
        </el-table-column>
        <el-table-column label="适用语言" min-width="320">
          <template #default="{ row }">
            <el-checkbox v-model="row.universal" :disabled="saving || !row.enabled">全语言通用</el-checkbox>
            <el-select v-if="!row.universal" v-model="row.languages" multiple collapse-tags collapse-tags-tooltip :disabled="saving || !row.enabled" :aria-label="`${row.name}适用语言`" placeholder="选择一种或多种语言" style="width: 100%">
              <el-option v-for="[id, name] in languages" :key="id" :label="name" :value="id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="排序与默认" min-width="260">
          <template #default="{ row, $index }">
            <el-button v-permission="'settings:share_templates'" :disabled="saving || $index === 0" @click="move($index, -1)">上移</el-button>
            <el-button v-permission="'settings:share_templates'" :disabled="saving || $index === rows.length - 1" @click="move($index, 1)">下移</el-button>
            <el-button v-permission="'settings:share_templates'" :disabled="saving || !previewRows.includes(row) || previewRows[0] === row" @click="move($index, -$index)">{{ previewRows[0] === row ? '当前语言默认' : '移到最前' }}</el-button>
          </template>
        </el-table-column>
      </admin-table>
      <el-button v-permission="'settings:share_templates'" type="primary" :loading="saving" style="margin-top: 20px" @click="save">保存模板配置</el-button>
    </template>
  </section>
</template>
