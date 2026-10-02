<script setup lang="ts">
import BusinessPage from '@/advanced/components/business/BusinessPage.vue'
const { request, error: advancedError, writing: advancedWriting, setTimeout, setInterval } = useBusinessLifecycle()
import { ref, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useBusinessLifecycle } from '@/advanced/components/business/useBusinessLifecycle'
import { useAuthStore } from '@/store/auth'
import { useLocaleStore } from '@/store/locale'

const router = useRouter()
const auth = useAuthStore()
auth.load()

const localeStore = useLocaleStore()
localeStore.loadLocale()

// 表单数据
const verifyCode = ref('')
const password = ref('')
const confirmPassword = ref('')

// 状态
const sending = ref(false)
const submitting = ref(false)
const showPassword = ref(false)
const showConfirmPassword = ref(false)
const countdown = ref(0)
let countdownTimer: number | null = null

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

// 发送验证码
async function sendCode() {
  if (sending.value || countdown.value > 0) return
  
  sending.value = true
  try {
    const res: any = await request.post('/user/changePassword/sendCode')
    
    if (res && res.success !== false) {
      showToast(localeStore.t('sendCodeSuccess'), 'success')
      // 开始倒计时60秒
      countdown.value = 60
      countdownTimer = setInterval(() => {
        countdown.value--
        if (countdown.value <= 0 && countdownTimer) {
          clearInterval(countdownTimer)
          countdownTimer = null
        }
      }, 1000)
    } else {
      showToast(res.message || localeStore.t('sendFailed'), 'error')
    }
  } catch (e: any) {
    console.error('Failed to send verification code:', e)
    showToast(e.response?.data?.message || e.message || localeStore.t('sendFailed'), 'error')
  } finally {
    sending.value = false
  }
}

// 提交修改密码
async function submitChangePassword() {
  // 验证
  if (!verifyCode.value || !verifyCode.value.trim()) {
    showToast(localeStore.t('verifyCodeRequired'), 'error')
    return
  }
  
  if (!password.value || !password.value.trim()) {
    showToast(localeStore.t('enterNewPasswordRequired'), 'error')
    return
  }
  
  if (password.value.length < 6) {
    showToast(localeStore.t('passwordLengthAtLeast'), 'error')
    return
  }
  
  if (!confirmPassword.value || !confirmPassword.value.trim()) {
    showToast(localeStore.t('confirmPasswordRequired'), 'error')
    return
  }
  
  if (password.value !== confirmPassword.value) {
    showToast(localeStore.t('passwordsNotMatch'), 'error')
    return
  }
  
  submitting.value = true
  
  try {
    const res: any = await request.post('/user/changePassword', {
      verifyCode: verifyCode.value.trim(),
      password: password.value.trim(),
      confirmPassword: confirmPassword.value.trim()
    })
    
    if (res && res.success !== false) {
      showToast(localeStore.t('passwordChangedSuccessLogin'), 'success')
      // 延迟跳转到登录页
      setTimeout(() => {
        auth.logout()
        router.push('/login')
      }, 1500)
    } else {
      showToast(res.message || localeStore.t('changePasswordFailed'), 'error')
    }
  } catch (e: any) {
    console.error('Failed to change password:', e)
    showToast(e.response?.data?.message || e.message || localeStore.t('changePasswordFailed'), 'error')
  } finally {
    submitting.value = false
  }
}

// 清理定时器
onUnmounted(() => {
  if (countdownTimer) {
    clearInterval(countdownTimer)
  }
})
</script>

<template>
<BusinessPage :title="localeStore.t('changePassword')" :error="advancedError" :busy="advancedWriting">
 <section class="card"><h2>{{ localeStore.text('安全验证','Security verification') }}</h2><label class="field">{{ localeStore.t('verifyCode') }}<div class="input-row"><input v-model="verifyCode" inputmode="numeric" autocomplete="one-time-code" maxlength="6" :placeholder="localeStore.t('verifyCodePlaceholder')" /><button class="text-button" :disabled="sending || countdown>0 || advancedWriting" @click="sendCode">{{ countdown>0 ? `${countdown}${localeStore.t('seconds')}` : localeStore.t('send') }}</button></div></label></section>
 <section class="card"><h2>{{ localeStore.t('newPassword') }}</h2><label class="field">{{ localeStore.t('newPassword') }}<div class="input-row"><input v-model="password" :type="showPassword?'text':'password'" autocomplete="new-password" :placeholder="localeStore.t('passwordPlaceholder')" /><button class="text-button" @click="showPassword=!showPassword">{{ showPassword ? localeStore.text('隐藏','Hide') : localeStore.text('显示','Show') }}</button></div></label><label class="field">{{ localeStore.t('confirmPassword') }}<div class="input-row"><input v-model="confirmPassword" :type="showConfirmPassword?'text':'password'" autocomplete="new-password" :placeholder="localeStore.t('confirmPasswordPlaceholder')" /><button class="text-button" @click="showConfirmPassword=!showConfirmPassword">{{ showConfirmPassword ? localeStore.text('隐藏','Hide') : localeStore.text('显示','Show') }}</button></div></label></section>
 <p class="notice">{{ localeStore.text('验证码倒计时、密码校验及错误提示全部保留','Verification code expiry and password checks remain in place') }}</p><button class="primary" :disabled="submitting || advancedWriting" @click="submitChangePassword">{{ submitting ? localeStore.t('submitting') : localeStore.text('确认修改','Confirm change') }}</button><div v-if="toastMessage" class="toast-message" :class="toastType" role="status">{{ toastMessage }}</div>
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
