<script setup lang="ts">
import BusinessPage from '@/advanced/components/business/BusinessPage.vue'
const { request, error: advancedError, writing: advancedWriting, setTimeout } = useBusinessLifecycle()
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useBusinessLifecycle } from '@/advanced/components/business/useBusinessLifecycle'
import { useAuthStore } from '@/store/auth'
import { useLocaleStore } from '@/store/locale'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
auth.load()

// 多语言
const localeStore = useLocaleStore()
localeStore.loadLocale()

const formData = ref({
  realName: '',
  idNumber: '',
  phone: '',
  address: ''
})

// const loading = ref(false) // 保留用于未来扩展
const submitting = ref(false)

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

// 加载实名认证信息
async function loadKycInfo() {
  try {
    const res: any = await request.get('/loan/personal-info/status')
    if (res && res.success && (res.verified || res.exempt) && res.data) {
      const info = res.data
      if (info.realName) formData.value.realName = info.realName
      if (info.idNumber) formData.value.idNumber = info.idNumber
      if (info.phone) formData.value.phone = info.phone
      formData.value.address = info.address || ''
    } else {
      router.replace('/loan/personal-info')
    }
  } catch (e) {
    router.replace('/loan/personal-info')
  }
}

// 提交贷款信息
async function submitLoanInfo() {
  if (!formData.value.realName || !formData.value.realName.trim()) {
    showToast(localeStore.t('enterRealName'), 'error')
    return
  }

  if (!formData.value.idNumber || !formData.value.idNumber.trim()) {
    showToast(localeStore.t('enterIdNumber'), 'error')
    return
  }

  if (!formData.value.phone || !formData.value.phone.trim()) {
    showToast(localeStore.t('enterPhoneNumber'), 'error')
    return
  }

  if (!formData.value.address || !formData.value.address.trim()) {
    showToast(localeStore.t('enterHomeAddress'), 'error')
    return
  }

  submitting.value = true
  try {
    const loanData = route.query
    const res: any = await request.post('/loan/apply', {
      amount: loanData.amount,
      settingId: loanData.settingId,

    })

    if (res && res.success && res.data) {
      showToast(localeStore.t('applicationSubmittedSuccess'), 'success')
      setTimeout(() => {
        router.push({
          path: '/loan/contract',
          query: { id: res.data.id.toString() }
        })
      }, 1000)
    } else {
      showToast(res.message || localeStore.t('applicationFailed'), 'error')
    }
  } catch (e: any) {
    showToast(e.message || localeStore.t('applicationFailed'), 'error')
    console.error('提交貸款申請失敗:', e)
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadKycInfo()
})
</script>

<template>
<BusinessPage :title="localeStore.t('loanInfo')" :error="advancedError" :busy="advancedWriting">
<div class="page-content">
      <div class="form-section card">
        <div class="form-label">{{ localeStore.t('realName') }} <span class="required">*</span></div>
        <input
          readonly v-model="formData.realName"
          type="text"
          class="form-input"
          :placeholder="localeStore.t('enterRealName')"
        />
      </div>

      <div class="form-section card">
        <div class="form-label">{{ localeStore.t('idNumber') }} <span class="required">*</span></div>
        <input
          readonly v-model="formData.idNumber"
          type="text"
          class="form-input"
          :placeholder="localeStore.t('enterIdNumber')"
        />
      </div>

      <div class="form-section card">
        <div class="form-label">{{ localeStore.t('phoneNumber') }} <span class="required">*</span></div>
        <input
          readonly v-model="formData.phone"
          type="tel"
          class="form-input"
          :placeholder="localeStore.t('enterPhoneNumber')"
        />
      </div>

      <div class="form-section card">
        <div class="form-label">{{ localeStore.t('homeAddress') }} <span class="required">*</span></div>
        <textarea
          readonly v-model="formData.address"
          class="form-textarea"
          :placeholder="localeStore.t('enterHomeAddress')"
          rows="3"
        ></textarea>
      </div>

      <button class="submit-btn" @click="submitLoanInfo" :disabled="submitting">
        {{ submitting ? localeStore.t('submitting') : localeStore.t('submitApplication') }}
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
