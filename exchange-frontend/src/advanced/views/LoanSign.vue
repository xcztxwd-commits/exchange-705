<script setup lang="ts">
import BusinessPage from '@/advanced/components/business/BusinessPage.vue'
const { request, error: advancedError, writing: advancedWriting, setTimeout } = useBusinessLifecycle()
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useBusinessLifecycle } from '@/advanced/components/business/useBusinessLifecycle'
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
  const hasSignature = imageData.data.some((value, index) => index % 4 === 3 && value > 0)

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
<BusinessPage :title="localeStore.t('loanAgreement')" :error="advancedError" :busy="advancedWriting">
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
</BusinessPage>
</template>

<style scoped>

.page-content,.cards-list,.addresses-list,.records-list,.yield-list,.product-list,.orders-list{display:flex;flex-direction:column;gap:20px}
.form-group,.form-item,.form-section{display:flex;flex-direction:column;gap:8px}
.form-label,.detail-label,.info-label,.stat-label{font-size:12px;color:#707780}
.form-actions,.order-actions,.dialog-footer,.amount-input-wrapper,.password-input-wrapper,.code-input-wrapper{display:flex;gap:8px}.form-actions>*,.order-actions>*{flex:1}
.record-row,.info-item,.info-row,.detail-item,.yield-header{display:flex;justify-content:space-between;gap:8px;padding:12px 0;overflow-wrap:anywhere}
.submit-btn,.submit-button,.confirm-btn,.purchase-btn{background:#d9e6c8!important;color:#2f4129!important;min-height:48px}
.contract-wrapper,.contract-box{display:flex;flex-direction:column;gap:20px}.contract-section{display:flex;flex-direction:column;gap:12px}.section-title{font-size:17px;font-weight:500}.section-text,.section-note{overflow-wrap:anywhere}.signature-image img{max-width:100%}
.card-item,.address-item{position:relative;z-index:1;border:1px solid #e9edef;border-radius:10px;padding:12px;background:white;overflow-wrap:anywhere}.swipe-item-wrapper{position:relative;overflow:hidden}.delete-button-wrapper{position:absolute;right:0;top:0;height:100%;display:flex;align-items:center}.delete-button{color:#9a3939}
.upload-area,.upload-box{background:#f5f6f7;border:1px solid #e9edef;border-radius:10px;padding:16px;min-height:84px}.upload-area img,.upload-box img,.id-image img{max-width:100%;object-fit:contain}
.empty-state,.loading-state{padding:28px 0;color:#707780;text-align:center}.status-badge,.status-text{color:#736582}.signature-canvas{width:100%;height:180px;touch-action:none;background:#f5f6f7}.image-preview img{max-width:100%;max-height:180px;object-fit:contain}

</style>
