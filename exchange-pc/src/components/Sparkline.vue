<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = defineProps<{
  data: number[]
  width?: number
  height?: number
  color?: string
}>()

const width = props.width || 60
const height = props.height || 20
const color = props.color || '#e25d4d'

// 控制是否显示动画（只在首次加载时显示）
const showAnimation = ref(true)
const hasData = ref(false)

// 监听数据变化，只在首次有数据时触发动画
watch(() => props.data, (newData) => {
  if (newData && newData.length > 0 && !hasData.value) {
    // 首次有数据，标记并启动动画
    hasData.value = true
    showAnimation.value = true
    
    // 动画完成后（1秒后）移除动画类
    setTimeout(() => {
      showAnimation.value = false
    }, 1000)
  }
}, { immediate: true })

const path = computed(() => {
  if (!props.data || props.data.length === 0) return ''
  
  const values = props.data
  const min = Math.min(...values)
  const max = Math.max(...values)
  const range = max - min || 1
  
  const stepX = width / (values.length - 1 || 1)
  const points = values.map((val, i) => {
    const x = i * stepX
    const y = height - ((val - min) / range) * height
    return `${x},${y}`
  })
  
  return `M ${points.join(' L ')}`
})

const areaPath = computed(() => {
  if (!props.data || props.data.length === 0) return ''
  
  const values = props.data
  const min = Math.min(...values)
  const max = Math.max(...values)
  const range = max - min || 1
  
  const stepX = width / (values.length - 1 || 1)
  const points = values.map((val, i) => {
    const x = i * stepX
    const y = height - ((val - min) / range) * height
    return `${x},${y}`
  })
  
  // 添加底部两个点形成闭合区域
  const firstPoint = points[0]
  const lastPoint = points[points.length - 1]
  if (!firstPoint || !lastPoint) return ''
  
  const firstX = firstPoint.split(',')[0]
  const lastX = lastPoint.split(',')[0]
  
  return `M ${firstPoint} L ${points.join(' L ')} L ${lastX},${height} L ${firstX},${height} Z`
})

const fillColor = computed(() => {
  // 根据主颜色生成半透明填充色
  const baseColor = props.color || '#e25d4d'
  
  // 解析颜色值
  if (baseColor.startsWith('#')) {
    // 十六进制颜色
    const hex = baseColor.replace('#', '')
    const r = parseInt(hex.substring(0, 2), 16)
    const g = parseInt(hex.substring(2, 4), 16)
    const b = parseInt(hex.substring(4, 6), 16)
    return `rgba(${r}, ${g}, ${b}, 0.15)`
  } else if (baseColor.startsWith('rgb')) {
    // RGB颜色
    const match = baseColor.match(/\d+/g)
    if (match && match.length >= 3) {
      return `rgba(${match[0]}, ${match[1]}, ${match[2]}, 0.15)`
    }
  }
  
  // 默认浅红色填充
  return 'rgba(232, 109, 67, 0.15)'
})
</script>

<template>
  <svg :width="width" :height="height" style="display: block">
    <!-- 填充区域 -->
    <path
      v-if="areaPath"
      :d="areaPath"
      :fill="fillColor"
      :class="{ 'sparkline-area-animate': showAnimation }"
    />
    <!-- 折线 -->
    <path
      v-if="path"
      :d="path"
      :stroke="color"
      stroke-width="1.5"
      fill="none"
      stroke-linecap="round"
      stroke-linejoin="round"
      :class="{ 'sparkline-path-animate': showAnimation }"
    />
  </svg>
</template>

<style scoped>
/* K线从左往右绘制动画 - 仅在首次加载时播放 */
.sparkline-path-animate {
  stroke-dasharray: 1000;
  stroke-dashoffset: 1000;
  animation: draw-line 0.8s ease-out forwards;
}

.sparkline-area-animate {
  opacity: 0;
  animation: fade-in-area 0.6s ease-out 0.2s forwards;
}

@keyframes draw-line {
  from {
    stroke-dashoffset: 1000;
  }
  to {
    stroke-dashoffset: 0;
  }
}

@keyframes fade-in-area {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}
</style>

