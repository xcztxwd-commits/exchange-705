<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'

const catalog = [
  ['light', '清爽白'], ['dark', '专业黑'], ['chart', '行情图'], ['gold', '黑金'],
  ['globe', '环球'], ['architecture', '建筑'], ['city', '城市'], ['referenceGold', '黑金原版'],
  ['referenceWhite', '白色原版'], ['referenceTerminal', '行情原版'], ['launch', '启航'],
  ['aurora', '极光'], ['racing', '竞速'], ['receipt', '交易票据'], ['journal', '交易日志'], ['voyage', '旅程'],
] as const
const rows = ref(catalog.map(([id, name]) => ({ id: String(id), name, enabled: true })))
const loading = ref(false), saving = ref(false), loaded = ref(false)
async function load() {
  loading.value = true
  loaded.value = false
  try {
    const result = await request.get('/admin/config/get', { params: { key: 'share.templates' } }) as unknown as { value: string | null }
    const ids = result.value == null ? catalog.map(([id]) => String(id)) : result.value.split(',')
    if (!ids.length || ids.some(id => !catalog.some(([key]) => key === id)) || new Set(ids).size !== ids.length) throw new Error('模板配置无效')
    rows.value = [...ids, ...catalog.map(([id]) => id).filter(id => !ids.includes(id))].map(id => ({
      id, name: catalog.find(([key]) => key === id)![1], enabled: ids.includes(id),
    }))
    loaded.value = true
  } catch (e: any) { ElMessage.error(e?.message || '加载分享模板失败，请重试') }
  finally { loading.value = false }
}
function move(index: number, offset: number) {
  const row = rows.value.splice(index, 1)[0]!
  rows.value.splice(index + offset, 0, row)
}
async function save() {
  const ids = rows.value.filter(row => row.enabled).map(row => row.id)
  if (!ids.length) { ElMessage.warning('至少启用一款模板'); return }
  saving.value = true
  try {
    await request.post('/admin/config/save', { key: 'share.templates', value: ids.join(','), description: '历史持仓分享模板，按显示顺序排列，首项为默认模板' })
    ElMessage.success('已保存，用户下次打开分享弹窗时生效')
  } catch (e: any) { ElMessage.error(e?.message || '保存失败') }
  finally { saving.value = false }
}
onMounted(load)
</script>

<template>
  <section v-loading="loading">
    <p>管理手机端和 PC 端历史持仓分享图。第一款启用的模板为默认模板；用户此前选择的模板仍启用时，保留用户选择。</p>
    <el-button v-if="!loaded" :disabled="loading" @click="load">重新加载</el-button>
    <template v-else>
      <el-table :data="rows" row-key="id">
        <el-table-column prop="name" label="模板" min-width="150" />
        <el-table-column prop="id" label="编号" min-width="150" />
        <el-table-column label="启用" width="90">
          <template #default="{ row }"><el-switch v-model="row.enabled" :disabled="saving" :aria-label="`启用${row.name}`" /></template>
        </el-table-column>
        <el-table-column label="排序与默认" min-width="260">
          <template #default="{ row, $index }">
            <el-button :disabled="saving || $index === 0" @click="move($index, -1)">上移</el-button>
            <el-button :disabled="saving || $index === rows.length - 1" @click="move($index, 1)">下移</el-button>
            <el-button :disabled="saving || !row.enabled || rows.find(item => item.enabled) === row" @click="move($index, -$index)">{{ rows.find(item => item.enabled) === row ? '默认模板' : '设为默认' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button type="primary" :loading="saving" style="margin-top: 20px" @click="save">保存模板配置</el-button>
    </template>
  </section>
</template>
