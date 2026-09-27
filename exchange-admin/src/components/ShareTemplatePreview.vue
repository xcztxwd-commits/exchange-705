<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { drawSharePoster, shareBackgrounds, shareCopy, shareTemplates,
  type ShareChart, type ShareOrder, type ShareTemplate } from '@/utils/orderShare'

const props = defineProps<{ template: string; name: string; language: string }>()
const open = ref(false), loading = ref(false), error = ref(''), image = ref('')
let generation = 0
// Design fixtures only: never query or expose a customer's real order or market data.
const order: ShareOrder = {
  id: 'DEMO-001', symbol: 'USDJPY', kind: 'contract', buy: true, profit: 128.50,
  openPrice: 148.25, closePrice: 148.89, quantity: 0.2, leverage: 10,
  margin: 1000, fee: 2.5, amount: null, currency: 'USD',
  openTime: '2026-09-01 10:00:00', closeTime: '2026-09-01 10:59:00',
}
const chart: ShareChart = {
  start: 1788256800000, end: 1788260340000, step: 60000, interval: '1m', source: 'DEMO', recent: true,
  candles: Array.from({ length: 60 }, (_, i) => {
    const price = 148.25 + i * .01 + Math.sin(i * .7) * .05
    return { timestamp: 1788256800000 + i * 60000, open: price, close: price + .02,
      low: price - .03, high: price + .05, volume: 100 + i * 3 }
  }),
}
async function render() {
  const run = ++generation, template = props.template as ShareTemplate, language = props.language
  loading.value = true; error.value = ''; image.value = ''
  try {
    if (!shareTemplates.includes(template)) throw new Error('未知模板')
    await document.fonts.ready
    let background: HTMLImageElement | undefined
    const file = shareBackgrounds[template]
    if (file) {
      background = new Image()
      background.src = `${import.meta.env.BASE_URL}share-templates/${file}`
      await background.decode()
    }
    if (run !== generation) return
    const canvas = document.createElement('canvas')
    drawSharePoster(canvas, order, {
      template, mode: 'both', quantity: true, capital: true, fee: true,
      leverage: true, orderId: true, openTime: true,
    }, shareCopy(language), 'DEMO', 'UTC', undefined, chart, background)
    image.value = canvas.toDataURL('image/png')
  } catch {
    if (run === generation) error.value = '预览加载失败，请重试'
  } finally {
    if (run === generation) loading.value = false
  }
}
watch(() => [props.template, props.language], render, { immediate: true })
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <div class="share-template-preview">
    <button v-if="!error" type="button" class="preview-thumbnail" :disabled="loading || !image"
      :aria-label="`预览${name}`" aria-haspopup="dialog" @click="open = true">
      <img v-if="image" :src="image" :alt="`${name} · ${language}示例图`" />
      <span v-else role="status">生成中…</span>
      <span>点击放大</span>
    </button>
    <div v-else role="status">
      <span>{{ error }}</span>
      <el-button link type="primary" @click="render">重试</el-button>
    </div>
    <el-dialog v-model="open" :title="`${name} · 图片预览`" width="min(560px, calc(100vw - 32px))"
      top="4vh" append-to-body destroy-on-close class="share-preview-dialog">
      <p class="preview-caption">语言：{{ language }} · 示例数据，仅用于展示模板，不代表真实交易。</p>
      <div class="preview-stage" v-loading="loading">
        <img v-if="image" :src="image" :alt="`${name} · ${language}完整预览`" />
        <div v-else-if="error" role="alert">{{ error }} <el-button @click="render">重试</el-button></div>
      </div>
      <template #footer><el-button @click="open = false">关闭预览</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.preview-thumbnail { display: flex; flex-direction: column; align-items: center; gap: 5px; width: 88px; padding: 6px; border: 1px solid var(--el-border-color); border-radius: 8px; background: var(--el-fill-color-light); color: var(--el-text-color-secondary); font: inherit; font-size: 12px; cursor: zoom-in; }
.preview-thumbnail:disabled { cursor: wait; }
.preview-thumbnail:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.preview-thumbnail img { display: block; width: 72px; height: 100px; object-fit: contain; }
.preview-caption { margin: 0 0 12px; color: var(--el-text-color-secondary); font-size: 13px; }
.preview-stage { display: flex; justify-content: center; align-items: center; min-height: 160px; border-radius: 8px; background: #e9edf1; padding: 12px; }
.preview-stage img { display: block; max-width: 100%; max-height: 65vh; object-fit: contain; }
</style>
