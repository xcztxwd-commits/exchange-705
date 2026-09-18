<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'

type SymbolItem = {
  id: number
  symbol: string
  name?: string
  baseCurrency?: string
  quoteCurrency?: string
  category?: string
  controlEnabled?: boolean
  controlPriceOffset?: number
  isEnabled?: boolean
  currentPrice?: number
  pricePrecision?: number
  volumePrecision?: number
  minTradeAmount?: number
  sortOrder?: number
  isHot?: boolean
  alltickSymbol?: string
  lotSize?: number
  feeMultiplier?: number
}

const loading = ref(false)
const saving = ref(false)
const symbolList = ref<SymbolItem[]>([])
const selectedSymbol = ref<string>('')
const form = ref({
  controlEnabled: false,
  controlPriceOffset: 0,
})

const currentSymbol = computed(() =>
  symbolList.value.find((s) => s.symbol === selectedSymbol.value)
)

// 偏移后价格 = 当前价格 + 偏移（仅用于展示）
const offsetPrice = computed(() => {
  const price = currentSymbol.value?.currentPrice
  const offset = form.value.controlPriceOffset
  if (price == null || Number.isNaN(Number(price))) return null
  const base = Number(price)
  const off = Number(offset || 0)
  return base + off
})

const loadSymbols = async () => {
  loading.value = true
  try {
    // 拉全量，方便选择
    const res: any = await request.post('/admin/symbols/query', {
      page: 0,
      size: 1000,
      category: '',
    })
    symbolList.value = res.list || []
    // 默认选第一个启用的
    const firstEnabled = symbolList.value.find((s) => s.isEnabled !== false)
    if (firstEnabled) {
      selectedSymbol.value = firstEnabled.symbol
      form.value.controlEnabled = !!firstEnabled.controlEnabled
      form.value.controlPriceOffset = Number(firstEnabled.controlPriceOffset || 0)
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '加载币种失败')
  } finally {
    loading.value = false
  }
}

const handleSelectSymbol = (symbol: string) => {
  const target = symbolList.value.find((s) => s.symbol === symbol)
  if (!target) return
  form.value.controlEnabled = !!target.controlEnabled
  form.value.controlPriceOffset = Number(target.controlPriceOffset || 0)
}

const handleSave = async () => {
  if (!currentSymbol.value) {
    ElMessage.warning('请选择币种')
    return
  }
  const payload = {
    ...currentSymbol.value,
    controlEnabled: form.value.controlEnabled,
    controlPriceOffset: form.value.controlPriceOffset,
  }
  // 防止精度丢失，转数字
  payload.controlPriceOffset = Number(payload.controlPriceOffset || 0)

  saving.value = true
  try {
    await request.post('/admin/symbols/update', payload)
    ElMessage.success('保存成功，前端行情将按偏移价推送')
    // 更新本地列表
    const idx = symbolList.value.findIndex((s) => s.symbol === payload.symbol)
    if (idx >= 0) {
      symbolList.value[idx] = { ...symbolList.value[idx], ...payload }
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

const handleDisable = async () => {
  if (!currentSymbol.value) return
  await ElMessageBox.confirm('确认关闭该币种的控盘？', '提示', { type: 'warning' })
  form.value.controlEnabled = false
  form.value.controlPriceOffset = 0
  await handleSave()
}

onMounted(() => {
  loadSymbols()
})
</script>

<template>
  <div class="ai-control-page">
    <el-card shadow="never">
      <template #header>
        <div class="header">
          <div>
            <span class="title">AI 控盘</span>
            <span class="desc">选择币种，设置控盘开关与偏移（单位：计价货币）</span>
          </div>
          <el-button :loading="loading" type="primary" plain @click="loadSymbols">刷新列表</el-button>
        </div>
      </template>

      <el-form label-width="120px" style="max-width: 720px">
        <el-form-item label="选择币种">
          <el-select
            v-model="selectedSymbol"
            placeholder="请选择"
            filterable
            style="width: 100%"
            @change="handleSelectSymbol"
          >
            <el-option
              v-for="s in symbolList"
              :key="s.symbol"
              :label="`${s.symbol} (${s.name || ''})`"
              :value="s.symbol"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="当前价格">
          <span v-if="currentSymbol">
            {{ currentSymbol?.currentPrice ?? '—' }}
            <span class="hint">（行情价，未含偏移，仅供参考）</span>
          </span>
          <span v-else>—</span>
        </el-form-item>

        <el-form-item label="偏移后价格">
          <span v-if="offsetPrice !== null">
            {{ offsetPrice }}
            <span class="hint">（前端实际推送到用户看到的大盘价格）</span>
          </span>
          <span v-else>—</span>
        </el-form-item>

        <el-form-item label="启用控盘">
          <el-switch
            v-model="form.controlEnabled"
            active-text="开启"
            inactive-text="关闭"
          />
        </el-form-item>

        <el-form-item label="控盘偏移">
          <div class="inline">
            <el-input-number
              v-model="form.controlPriceOffset"
              :step="0.0001"
              :precision="8"
              style="width: 240px"
            />
            <span class="hint">前端推送价格 = 实际价格 + 此偏移</span>
          </div>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
          <el-button :disabled="!form.controlEnabled" @click="handleDisable">关闭控盘</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.ai-control-page {
  padding: 16px;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.title {
  font-size: 16px;
  font-weight: 600;
  margin-right: 8px;
}
.desc {
  color: #999;
  font-size: 12px;
}
.hint {
  color: #999;
  margin-left: 8px;
  font-size: 12px;
}
.inline {
  display: flex;
  align-items: center;
  gap: 12px;
}
</style>

