<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import request from '@/utils/request'
import { useLocaleStore } from '@/store/locale'

const router = useRouter()
const route = useRoute()

// 多语言
const localeStore = useLocaleStore()
localeStore.loadLocale()

const loanId = ref<string>('')
const loan = ref<any>(null)
const canvasRef = ref<HTMLCanvasElement | null>(null)
const isDrawing = ref(false)
const signature = ref<string>('')
const submitting = ref(false)
const loading = ref(true)

// Toast提示
const toastMessage = ref('')
const toastType = ref<'success' | 'error' | ''>('')

function showToast(message: string, type: 'success' | 'error' = 'error') {
  toastMessage.value = message
  toastType.value = type
  setTimeout(() => {
    toastMessage.value = ''
    toastType.value = ''
  }, 3000)
}

// 初始化画布
function initCanvas() {
  const canvas = canvasRef.value
  if (!canvas) return

  const ctx = canvas.getContext('2d')
  if (!ctx) return

  // 设置画布尺寸
  const rect = canvas.getBoundingClientRect()
  canvas.width = rect.width
  canvas.height = rect.height

  // 设置绘制样式
  ctx.strokeStyle = '#333'
  ctx.lineWidth = 2
  ctx.lineCap = 'round'
  ctx.lineJoin = 'round'
}

// 开始绘制
function startDraw(e: MouseEvent | TouchEvent) {
  isDrawing.value = true
  const canvas = canvasRef.value
  if (!canvas) return

  const ctx = canvas.getContext('2d')
  if (!ctx) return

  const rect = canvas.getBoundingClientRect()
  const clientX = 'touches' in e ? (e.touches?.[0]?.clientX ?? 0) : (e as MouseEvent).clientX
  const clientY = 'touches' in e ? (e.touches?.[0]?.clientY ?? 0) : (e as MouseEvent).clientY

  ctx.beginPath()
  ctx.moveTo(clientX - rect.left, clientY - rect.top)
}

// 绘制中
function draw(e: MouseEvent | TouchEvent) {
  if (!isDrawing.value) return

  const canvas = canvasRef.value
  if (!canvas) return

  const ctx = canvas.getContext('2d')
  if (!ctx) return

  const rect = canvas.getBoundingClientRect()
  const clientX = 'touches' in e ? (e.touches?.[0]?.clientX ?? 0) : (e as MouseEvent).clientX
  const clientY = 'touches' in e ? (e.touches?.[0]?.clientY ?? 0) : (e as MouseEvent).clientY

  ctx.lineTo(clientX - rect.left, clientY - rect.top)
  ctx.stroke()
}

// 结束绘制
function endDraw() {
  isDrawing.value = false
}

// 清除签名
function clearSignature() {
  const canvas = canvasRef.value
  if (!canvas) return

  const ctx = canvas.getContext('2d')
  if (!ctx) return

  ctx.clearRect(0, 0, canvas.width, canvas.height)
  signature.value = ''
}

// 加载贷款信息
async function loadLoan() {
  if (!loanId.value) return
  
  try {
    const res: any = await request.get(`/loan/${loanId.value}`)
    if (res && res.success && res.data) {
      loan.value = res.data
      // 如果已经签署，不允许再次签署
      if (loan.value.contractSigned) {
        showToast(localeStore.t('contractAlreadySigned'), 'error')
        setTimeout(() => {
          router.back()
        }, 2000)
        return
      }
    } else {
      showToast(localeStore.t('loadLoanInfoFailed'), 'error')
      setTimeout(() => {
        router.back()
      }, 1500)
    }
  } catch (e: any) {
    showToast(e.message || localeStore.t('loadFailed'), 'error')
    setTimeout(() => {
      router.back()
    }, 1500)
  } finally {
    loading.value = false
  }
}

// 提交签名
async function submitSignature() {
  // 再次检查是否已签署
  if (loan.value?.contractSigned) {
    showToast(localeStore.t('contractAlreadySigned'), 'error')
    return
  }

  const canvas = canvasRef.value
  if (!canvas) return

  // 检查是否有签名
  const ctx = canvas.getContext('2d')
  if (!ctx) return

  const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height)
  const hasSignature = imageData.data.some((value, index) => index % 4 !== 3 && value !== 255)

  if (!hasSignature) {
    showToast(localeStore.t('pleaseSignFirst'), 'error')
    return
  }

  if (submitting.value) return
  submitting.value = true

  // 将画布转换为base64
  const signatureImage = canvas.toDataURL('image/png')

  try {
    const res: any = await request.post('/loan/sign', {
      loanId: loanId.value,
      signatureImage: signatureImage
    })

    if (res && res.success) {
      showToast(localeStore.t('signSuccess'), 'success')
      setTimeout(() => {
        router.push('/loan/records')
      }, 1000)
    } else {
      showToast(res.message || localeStore.t('signFailed'), 'error')
    }
  } catch (e: any) {
    const errorMsg = e.response?.data?.message || e.message || localeStore.t('signFailed')
    showToast(errorMsg, 'error')
    console.error('签署失败:', e)
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loanId.value = (route.query.id as string) || ''
  if (!loanId.value) {
    showToast(localeStore.t('loanIdNotExists'), 'error')
    setTimeout(() => {
      router.back()
    }, 1500)
    return
  }

  // 加载贷款信息，检查是否已签署
  loadLoan().then(() => {
    if (!loan.value?.contractSigned) {
      setTimeout(() => {
        initCanvas()
      }, 100)
    }
  })
})
</script>

<template>
  <div class="loan-sign-page">
    <div class="page-header">
      <div class="back-button" @click="router.back()">
        <svg t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
          <path d="M768 903.232l-50.432 56.768L256 512l461.568-448 50.432 56.768L364.928 512z" fill="#333"></path>
        </svg>
      </div>
      <div class="header-title">{{ localeStore.t('loan') }}</div>
      <div class="header-placeholder"></div>
    </div>

    <div class="sign-content" v-if="!loading">
      <div class="sign-instruction" v-if="!loan?.contractSigned">{{ localeStore.t('pleaseSign') }}</div>
      <div class="sign-instruction" v-else style="color: #ff4444;">{{ localeStore.t('contractAlreadySigned') }}</div>
      <div class="sign-area" v-if="!loan?.contractSigned">
        <canvas
          ref="canvasRef"
          class="sign-canvas"
          @mousedown="startDraw"
          @mousemove="draw"
          @mouseup="endDraw"
          @mouseleave="endDraw"
          @touchstart="startDraw"
          @touchmove="draw"
          @touchend="endDraw"
        ></canvas>
      </div>
    </div>

    <div class="sign-footer" v-if="!loading && !loan?.contractSigned">
      <button class="resign-btn" @click="clearSignature">{{ localeStore.t('resign') }}</button>
      <button class="sign-submit-btn" @click="submitSignature" :disabled="submitting">
        {{ submitting ? localeStore.t('signing') : localeStore.t('signature') }}
      </button>
    </div>

    <!-- Toast提示 -->
    <div v-if="toastMessage" :class="['toast-message', toastType]">
      {{ toastMessage }}
    </div>
  </div>
</template>

<style scoped>
.loan-sign-page {
  min-height: 100vh;
  background: #fff;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px;
  background: #fff;
  border-bottom: 1px solid #eee;
}

.back-button {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.header-title {
  font-size: 18px;
  font-weight: 600;
  color: #333;
}

.header-placeholder {
  width: 40px;
}

.sign-content {
  padding: 24px 16px;
}

.sign-instruction {
  font-size: 16px;
  color: #333;
  margin-bottom: 16px;
  font-weight: 500;
}

.sign-area {
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  background: #fff;
  overflow: hidden;
}

.sign-canvas {
  width: 100%;
  height: 300px;
  display: block;
  cursor: crosshair;
  touch-action: none;
}

.sign-footer {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 16px;
  background: #fff;
  border-top: 1px solid #eee;
  display: flex;
  gap: 12px;
}

.resign-btn {
  flex: 1;
  padding: 14px;
  background: #fff;
  color: #333;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 500;
  cursor: pointer;
}

.sign-submit-btn {
  flex: 1;
  padding: 14px;
  background: #2abf4b;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
}

.sign-submit-btn:disabled {
  background: #ccc;
  cursor: not-allowed;
}

/* Toast提示 */
.toast-message {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  padding: 16px 24px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  z-index: 2000;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  animation: toastSlideIn 0.3s ease-out;
  max-width: 80%;
  text-align: center;
  word-wrap: break-word;
}

.toast-message.error {
  background: #ff4444;
  color: #fff;
}

.toast-message.success {
  background: #2abf4b;
  color: #fff;
}

@keyframes toastSlideIn {
  from {
    opacity: 0;
    transform: translate(-50%, -60%);
  }
  to {
    opacity: 1;
    transform: translate(-50%, -50%);
  }
}
</style>

