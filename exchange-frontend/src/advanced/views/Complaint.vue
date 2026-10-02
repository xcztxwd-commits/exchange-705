<script setup lang="ts">
import BusinessPage from '@/advanced/components/business/BusinessPage.vue'
const { request, error: advancedError, writing: advancedWriting, setTimeout } = useBusinessLifecycle()
import { ref, onMounted } from 'vue'
import { useBusinessLifecycle } from '@/advanced/components/business/useBusinessLifecycle'
import { useLocaleStore } from '@/store/locale'

const localeStore = useLocaleStore()
localeStore.loadLocale()

// 状态
const loading = ref(true)
const complaintEmail = ref('')
const emailAvailable = ref(false)
const loadError = ref(false)
const copied = ref(false)

// Toast 提示
const toastMessage = ref('')
const toastType = ref<'success' | 'error' | ''>('')

// 显示提示消息
function showToast(message: string, type: 'success' | 'error' = 'error') {
  toastMessage.value = message
  toastType.value = type
  setTimeout(() => {
    toastMessage.value = ''
    toastType.value = ''
  }, 3000)
}

// 加载投诉邮箱
async function loadComplaintEmail() {
  loading.value = true
  loadError.value = false
  complaintEmail.value = ''
  try {
    const res: any = await request.get('/user/complaint/email')
    if (res && res.email) {
      complaintEmail.value = res.email
      emailAvailable.value = res.available || false
    } else {
      emailAvailable.value = false
    }
  } catch (e: any) {
    loadError.value = true
    console.error('Failed to load complaint email:', e)
    emailAvailable.value = false
    showToast(localeStore.t('loadFailed'), 'error')
  } finally {
    loading.value = false
  }
}

// 复制邮箱地址
async function copyEmail() {
  if (!complaintEmail.value) {
    showToast(localeStore.t('emailNotConfigured'), 'error')
    return
  }
  
  try {
    // 使用 Clipboard API
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(complaintEmail.value)
      copied.value = true
      showToast(localeStore.t('copiedToClipboard'), 'success')
      setTimeout(() => {
        copied.value = false
      }, 2000)
    } else {
      // 降级方案：使用传统方法
      const textarea = document.createElement('textarea')
      textarea.value = complaintEmail.value
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.select()
      try {
        document.execCommand('copy')
        copied.value = true
        showToast(localeStore.t('copiedToClipboard'), 'success')
        setTimeout(() => {
          copied.value = false
        }, 2000)
      } catch (err) {
        showToast(localeStore.t('copyFailedPleaseCopyManually'), 'error')
      } finally {
        document.body.removeChild(textarea)
      }
    }
  } catch (e: any) {
    console.error('Failed to copy email:', e)
    showToast(localeStore.t('copyFailedPleaseCopyManually'), 'error')
  }
}

onMounted(() => {
  loadComplaintEmail()
})
</script>

<template>
<BusinessPage :title="localeStore.text('投诉与反馈','Complaints and feedback')" :error="advancedError" :busy="advancedWriting || loading">
 <section class="card"><h2>{{ localeStore.t('complaintEmail') }}</h2><p v-if="loading" class="muted">{{ localeStore.t('loading') }}</p><h2 v-else>{{ emailAvailable ? complaintEmail : loadError ? localeStore.text('邮箱加载失败','Unable to load email') : localeStore.t('noComplaintEmail') }}</h2><p class="muted">{{ localeStore.text('您可复制邮箱后发送反馈。未配置和加载失败状态会明确提示。','Copy the email to send feedback. Missing configuration and loading failures are shown explicitly.') }}</p><button :disabled="!emailAvailable || loading" @click="copyEmail">{{ copied ? localeStore.t('copiedToClipboard') : localeStore.text('复制邮箱','Copy email') }}</button></section><p class="notice">{{ localeStore.text('反馈通过平台配置的投诉邮箱处理','Feedback is handled by the configured contact email') }}</p><button v-if="loadError" @click="loadComplaintEmail">{{ localeStore.text('重试','Retry') }}</button><div v-if="toastMessage" class="toast-message" :class="toastType" role="status">{{ toastMessage }}</div>
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
