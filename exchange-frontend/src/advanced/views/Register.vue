<script setup lang="ts">
import BusinessPage from '@/advanced/components/business/BusinessPage.vue'
const { request, error: advancedError, writing: advancedWriting, setTimeout } = useBusinessLifecycle()
import { canStartBusiness, tenantFeatures } from '@/utils/tenantFeatures'
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useLocaleStore } from '@/store/locale'
import { useAuthStore } from '@/store/auth'
import { accountMode } from '@/utils/accountMode'
import { resumableClaim, steadyWall } from '@/utils/claimContinuation'
import { useBusinessLifecycle } from '@/advanced/components/business/useBusinessLifecycle'
import RegistrationCaptcha from '@/components/RegistrationCaptcha.vue'
import RegistrationProfileFields from '@/components/RegistrationProfileFields.vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const localeStore = useLocaleStore()
localeStore.loadLocale()

const captcha = ref<InstanceType<typeof RegistrationCaptcha> | null>(null)
const profile = ref<InstanceType<typeof RegistrationProfileFields> | null>(null)
const captchaReady = ref(false)
const email = ref('')
const password = ref('')
const confirmPassword = ref('')
const inviteCode = ref('')
const inviteLocked = ref(false)
const loading = ref(false)
const showPwd = ref(false)
const showPwd2 = ref(false)

const toastMessage = ref('')
const toastType = ref<'success' | 'error'>('error')
let toastTimer = 0
function showToastMessage(message: string, type: 'success' | 'error' = 'error') {
  window.clearTimeout(toastTimer)
  toastMessage.value = message
  toastType.value = type
  toastTimer = setTimeout(() => { toastMessage.value = '' }, 3000)
}

// 从路由读取邀请链接参数并锁定
onMounted(() => {
  const qInvite = (route.query.invite as string) || (route.query.invitationCode as string)
  if (qInvite) {
    inviteCode.value = qInvite
    inviteLocked.value = true
  }
})

const onSubmit = async () => {
  if (loading.value) return
  if (!email.value || !password.value || !confirmPassword.value) {
    showToastMessage(localeStore.t('pleaseEnterAllRequiredFields'), 'error')
    return
  }
  if (password.value !== confirmPassword.value) {
    showToastMessage(localeStore.t('passwordsNotMatch'), 'error')
    return
  }
  if (password.value.length < 6) {
    showToastMessage(localeStore.t('passwordTooShort'), 'error')
    return
  }
  let profileFields: Record<string, string>
  try { profileFields = profile.value?.payload() || {} }
  catch (e: any) { showToastMessage(e.message, 'error'); return }
  const challenge = captcha.value?.submission()
  if (!challenge) return
  const tenant = tenantFeatures.value?.tenantId || 0
  const submittedEmail = email.value.trim().toLowerCase()
  const pending = !auth.token && !localStorage.getItem('token') && route.path === '/register'
    && canStartBusiness('registration') && accountMode() === 'REAL'
    ? resumableClaim(localStorage, tenant, 0, 'REAL', steadyWall()) : null
  loading.value = true
  try {
    const response: any = await request.post('/auth/register', {
      ...challenge,
      ...profileFields,
      email: email.value,
      password: password.value,
      confirmPassword: confirmPassword.value,
      invitationCode: inviteCode.value.trim() || undefined,
    })
    const user = response?.user
    const current = pending && Number.isSafeInteger(user?.id) && user.id > 0
      ? resumableClaim(localStorage, tenant, user.id, 'REAL', steadyWall()) : null
    // Only the same live anonymous action may consume the server-issued registration session.
    const continued = Boolean(pending && pending.user == null && current
      && current.campaign === pending.campaign && current.actionId === pending.actionId
      && current.created === pending.created && !auth.token && !localStorage.getItem('token')
      && route.path === '/register' && tenantFeatures.value?.tenantId === tenant
      && canStartBusiness('registration') && accountMode() === 'REAL'
      && sessionStorage.getItem(`account-mode:${tenant}:${user.id}`) !== 'DEMO'
      && user.tenantId === tenant && user.email === submittedEmail
      && typeof response.token === 'string' && /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(response.token)
      && Number.isSafeInteger(response.expire) && response.expire > Date.now())
    if (continued) auth.setAuth(response.token, user)
    showToastMessage(localeStore.t(continued ? 'loginSuccess' : 'registerSuccess'), 'success')
    setTimeout(() => {
      router.replace(continued ? '/home' : '/login')
    }, 500)
  } catch (e: any) {
    await captcha.value?.registrationFailed(e)
    showToastMessage(e?.message || localeStore.t('registerFail'), 'error')
  } finally {
    loading.value = false
  }
}
</script>

<template>
<BusinessPage :title="localeStore.t('emailRegister')" :error="advancedError" :busy="advancedWriting" :nav="false">
<div class="auth-card"><img src="@/advanced/assets/business/logo.svg" alt="FOREX EXCHANGE" />
      

      <h2>{{ localeStore.text('创建账户','Create account') }}</h2>

      <div class="form-group">
        <div class="form-label">{{ localeStore.t('emailRegister') }}</div>
        <input v-model="email" class="input-box" :placeholder="localeStore.t('emailPlaceholder')" type="email" />
      </div>

      <div class="form-group">
        <div class="form-label">{{ localeStore.t('password') }}</div>
        <div class="input-with-icon">
          <input
            v-model="password"
            class="input-box"
            :placeholder="localeStore.t('passwordPlaceholder')"
            :type="showPwd ? 'text' : 'password'"
          />
          <button class="input-icon-btn" type="button" @click="showPwd = !showPwd">
            <svg v-if="showPwd" width="20" height="20" viewBox="0 0 24 24" fill="none">
              <path
                d="M3 12C3 12 6.5 5 12 5C17.5 5 21 12 21 12C21 12 17.5 19 12 19C6.5 19 3 12 3 12Z"
                stroke="#111"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
              <circle cx="12" cy="12" r="3" stroke="#111" stroke-width="2" />
            </svg>
            <svg v-else width="20" height="20" viewBox="0 0 24 24" fill="none">
              <path
                d="M3 3L21 21M10.58 10.58C10.21 10.95 10 11.45 10 12C10 13.1 10.9 14 12 14C12.55 14 13.05 13.79 13.42 13.42M17.94 17.94C16.67 18.62 14.95 19 13 19C7.5 19 4 12 4 12C4.78 10.54 5.76 9.27 6.86 8.22M9.9 6.18C10.57 6.06 11.27 6 12 6C17.5 6 21 12 21 12C20.55 12.85 20.03 13.62 19.47 14.3"
                stroke="#111"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
          </button>
        </div>
      </div>

      <div class="form-group">
        <div class="form-label">{{ localeStore.t('confirmPassword') }}</div>
        <div class="input-with-icon">
          <input
            v-model="confirmPassword"
            class="input-box"
            :placeholder="localeStore.t('confirmPasswordPlaceholder')"
            :type="showPwd2 ? 'text' : 'password'"
          />
          <button class="input-icon-btn" type="button" @click="showPwd2 = !showPwd2">
            <svg v-if="showPwd2" width="20" height="20" viewBox="0 0 24 24" fill="none">
              <path
                d="M3 12C3 12 6.5 5 12 5C17.5 5 21 12 21 12C21 12 17.5 19 12 19C6.5 19 3 12 3 12Z"
                stroke="#111"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
              <circle cx="12" cy="12" r="3" stroke="#111" stroke-width="2" />
            </svg>
            <svg v-else width="20" height="20" viewBox="0 0 24 24" fill="none">
              <path
                d="M3 3L21 21M10.58 10.58C10.21 10.95 10 11.45 10 12C10 13.1 10.9 14 12 14C12.55 14 13.05 13.79 13.42 13.42M17.94 17.94C16.67 18.62 14.95 19 13 19C7.5 19 4 12 4 12C4.78 10.54 5.76 9.27 6.86 8.22M9.9 6.18C10.57 6.06 11.27 6 12 6C17.5 6 21 12 21 12C20.55 12.85 20.03 13.62 19.47 14.3"
                stroke="#111"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
          </button>
        </div>
      </div>

      <RegistrationProfileFields ref="profile" /><p class="notice">{{ localeStore.text('注册资料字段随租户配置显示，必填校验与身份流程保留','Required profile fields and identity checks follow your account configuration') }}</p>

      <RegistrationCaptcha ref="captcha" :disabled="loading" @ready="captchaReady = $event" />

      <details class="form-group registration-invite">
        <summary>{{ localeStore.text('已有邀请码', 'Have an invitation code?') }}</summary>
        <input
          v-model="inviteCode"
          class="input-box"
          :aria-label="localeStore.t('inviteCode')"
          :readonly="inviteLocked"
          :placeholder="inviteLocked ? localeStore.t('inviteCodeFilled') : localeStore.t('inviteCodeEmpty')"
        />
      </details>

      <button v-if="canStartBusiness('registration')" class="primary" :disabled="loading || !captchaReady || !profile?.ready" @click="onSubmit">
        {{ loading ? localeStore.t('submitting') : localeStore.t('register') }}
      </button>

      <div class="section-tip">
        {{ localeStore.t('gotoLogin') }}
        <a class="link-primary" @click="router.push('/login')"> {{ localeStore.t('login') }} </a>
      </div>
    </div>
<div v-if="toastMessage" class="toast-message advanced-register-toast" :class="toastType" role="status">{{ toastMessage }}</div>
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

.auth-card{display:flex;flex-direction:column;gap:20px}.input-with-icon{position:relative}.input-with-icon input{padding-right:52px!important}.input-icon-btn{position:absolute;right:4px;top:2px;padding:10px!important;background:transparent!important}.section-tip{text-align:center;font-size:12px;color:#707780}.link-primary{color:#736582;cursor:pointer}
</style>
