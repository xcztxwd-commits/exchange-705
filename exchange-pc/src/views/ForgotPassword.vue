<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useLocaleStore } from '@/store/locale'
import request from '@/utils/request'

const router = useRouter()
const localeStore = useLocaleStore()
localeStore.loadLocale()

const email = ref('')
const password = ref('')
const confirmPassword = ref('')
const verifyCode = ref('')
const sending = ref(false)
const loading = ref(false)
const showPwd = ref(false)
const showPwd2 = ref(false)

// 页面提示消息
const toastMessage = ref('')
const toastType = ref<'success' | 'error' | ''>('')

function showToast(msg: string, type: 'success' | 'error' = 'error') {
  toastMessage.value = msg
  toastType.value = type
  setTimeout(() => {
    toastMessage.value = ''
    toastType.value = ''
  }, 3000)
}

const sendCode = async () => {
  if (sending.value) return
  if (!email.value) {
    showToast(localeStore.t('pleaseEnterEmail'), 'error')
    return
  }
  sending.value = true
  try {
    await request.post('/auth/sendEmailCode', {
      email: email.value,
      scene: 'forget_password',
    })
    showToast(localeStore.t('codeSentCheckEmail'), 'success')
  } catch (e: any) {
    showToast(e?.message || localeStore.t('sendFailed'), 'error')
  } finally {
    sending.value = false
  }
}

const onSubmit = async () => {
  if (loading.value) return
  if (!email.value || !password.value || !confirmPassword.value || !verifyCode.value) {
    showToast(localeStore.t('pleaseFillAllFields'), 'error')
    return
  }
  if (password.value !== confirmPassword.value) {
    showToast(localeStore.t('passwordsNotMatch'), 'error')
    return
  }
  loading.value = true
  try {
    await request.post('/auth/resetPassword', {
      email: email.value,
      password: password.value,
      confirmPassword: confirmPassword.value,
      verifyCode: verifyCode.value,
    })
    showToast(localeStore.t('resetSuccessLogin'), 'success')
    setTimeout(() => {
      router.replace('/login')
    }, 1500)
  } catch (e: any) {
    showToast(e?.message || localeStore.t('resetFailed'), 'error')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page-shell">
    <div class="auth-card">
      <div class="top-bar">
        <button class="icon-btn" @click="router.back()">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
            <path d="M15 6L9 12L15 18" stroke="#111" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </button>
        <button class="icon-btn" @click="router.push('/language')">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
            <path d="M12 3C16.97 3 21 7.03 21 12C21 16.97 16.97 21 12 21C7.03 21 3 16.97 3 12C3 7.03 7.03 3 12 3Z" stroke="#111" stroke-width="2" />
            <path d="M3 12H21" stroke="#111" stroke-width="2" />
            <path d="M12 3C14.5 6.5 14.5 17.5 12 21C9.5 17.5 9.5 6.5 12 3Z" stroke="#111" stroke-width="2" />
          </svg>
        </button>
      </div>

      <div class="auth-title">{{ localeStore.t('forgotPassword') }}</div>

      <div class="form-group">
        <div class="form-label">{{ localeStore.t('emailLogin') }}</div>
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

      <div class="form-group">
        <div class="form-label">{{ localeStore.t('verifyCode') }}</div>
        <div class="flex-row">
          <input v-model="verifyCode" class="input-box" :placeholder="localeStore.t('verifyCodePlaceholder')" />
          <button class="verify-btn" :disabled="sending" type="button" @click="sendCode">
            {{ sending ? localeStore.t('sending') : localeStore.t('send') }}
          </button>
        </div>
      </div>

      <button class="primary-btn" :disabled="loading" @click="onSubmit">
        {{ loading ? localeStore.t('submitting') : localeStore.t('resetPassword') }}
      </button>
    </div>
    
    <!-- 页面提示消息 -->
    <div v-if="toastMessage" class="toast-message" :class="toastType">
      {{ toastMessage }}
    </div>
  </div>
</template>

<style lang="scss" scoped>
@import '@/styles/variables.scss';

.page-shell {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  padding: 32px 16px;
}

.auth-card {
  width: min(520px, 100%);
  background: $bg-card;
  border-radius: $radius-lg;
  padding: 20px 24px 32px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.05);
  position: relative;
}

.top-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.icon-btn {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  border: 1px solid $border-color;
  background: #f7f8fb;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all $transition-fast;
}

.icon-btn:hover {
  border-color: $primary-color;
}

.auth-title {
  margin: 12px 0 24px;
  font-size: 22px;
  font-weight: 700;
  color: $text-color;
}

.form-group {
  margin-bottom: 18px;
}

.form-label {
  font-size: 15px;
  font-weight: 700;
  color: $primary-color;
  margin-bottom: 10px;
}

.input-box {
  width: 100%;
  background: #f6f6f6;
  border-radius: $radius-sm;
  border: 1px solid transparent;
  padding: 14px 16px;
  font-size: 15px;
  color: $text-color;
  transition: border-color $transition-fast, box-shadow $transition-fast;
}

.input-box:focus {
  outline: none;
  border-color: $primary-color;
  box-shadow: 0 0 0 3px rgba(133, 189, 0, 0.1);
}

.input-with-icon {
  position: relative;
}

.input-icon-btn {
  position: absolute;
  right: 14px;
  top: 50%;
  transform: translateY(-50%);
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 0;
}

.flex-row {
  display: flex;
  gap: 10px;
}

.flex-row .input-box {
  flex: 1;
}

.verify-btn {
  border: none;
  border-radius: $radius-sm;
  background: $primary-color;
  color: #fff;
  font-weight: 700;
  font-size: 14px;
  padding: 14px 20px;
  cursor: pointer;
  white-space: nowrap;
  transition: opacity $transition-fast;
}

.verify-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.primary-btn {
  width: 100%;
  border: none;
  border-radius: $radius-sm;
  background: $primary-color;
  color: #fff;
  font-weight: 700;
  font-size: 16px;
  padding: 14px 12px;
  cursor: pointer;
  transition: opacity $transition-fast, transform $transition-fast;
}

.primary-btn:active {
  transform: scale(0.99);
}

.primary-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 页面提示消息 */
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
  background: #73b100;
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

@media (max-width: 520px) {
  .auth-card {
    padding: 16px;
    border-radius: 16px;
  }

  .page-shell {
    padding: 16px 12px;
  }
}
</style>

