<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useLocaleStore } from '@/store/locale'
import { useAuthStore } from '@/store/auth'
import request from '@/utils/request'
import { loginAccountText, loginText, type LoginReason } from '@/utils/loginFeedback'
import AuthModeSwitch from '@/components/AuthModeSwitch.vue'

const router = useRouter()
const route = useRoute()
const localeStore = useLocaleStore()
localeStore.loadLocale()
const auth = useAuthStore()

const account = ref('')
const password = ref('')
const showPwd = ref(false)
const loading = ref(false)
const feedback = ref<LoginReason | null>(null)
const feedbackText = computed(() => {
  const keys = { disabled: 'accountDisabled', network: 'networkError', success: 'loginSuccess' } as const
  const reason = feedback.value
  return reason === 'missing' ? loginAccountText(localeStore.locale, 'missing') : reason && reason in keys ? localeStore.t(keys[reason as keyof typeof keys]) : loginText(localeStore.locale, reason || 'failed')
})
watch([account, password], () => { if (!loading.value) feedback.value = null })
let disposed = false
onBeforeUnmount(() => { disposed = true })
const onSubmit = async () => {
  if (loading.value) return
  if (!account.value.trim() || !password.value) { feedback.value = 'missing'; return }
  feedback.value = null
  loading.value = true
  try {
    const res: any = await request.post('/auth/login', {
      account: account.value.trim(), password: password.value, loginType: 'account',
    })
    if (disposed) return
    if (typeof res?.token !== 'string' || !res.token.trim() || !res.user?.id || res.success === false) throw new Error('Invalid login response')
    auth.setAuth(res.token, res.user)
    feedback.value = 'success'
    await router.replace('/home')
  } catch (e: any) {
    if (!disposed) feedback.value = e?.loginReason || 'failed'
  } finally {
    if (!disposed && feedback.value !== 'success') loading.value = false
  }
}

const togglePwd = () => {
  showPwd.value = !showPwd.value
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

      <div class="auth-title">{{ loginAccountText(localeStore.locale, 'title') }}</div>
      <AuthModeSwitch />

      <form novalidate @submit.prevent="onSubmit">
      <div class="form-group">
        <label for="login-account" class="form-label">{{ loginAccountText(localeStore.locale, 'title') }}</label>
        <input id="login-account" autocomplete="username" :disabled="loading" :aria-describedby="feedback ? 'login-feedback' : undefined" :aria-invalid="feedback === 'credentials'" v-model="account" class="input-box" :placeholder="loginAccountText(localeStore.locale, 'placeholder')" type="text" />
      </div>

      <div class="form-group">
        <label for="login-password" class="form-label">{{ localeStore.t('password') }}</label>
        <div class="input-with-icon">
          <input
            id="login-password" autocomplete="current-password" :disabled="loading" :aria-describedby="feedback ? 'login-feedback' : undefined" :aria-invalid="feedback === 'credentials'"
            v-model="password"
            class="input-box"
            :placeholder="localeStore.t('passwordPlaceholder')"
            :type="showPwd ? 'text' : 'password'"
          />
          <button :aria-label="localeStore.t('password')" :aria-pressed="showPwd" class="input-icon-btn" type="button" @click="togglePwd">
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

      <div class="recovery-row">
        <RouterLink to="/forgot-password" class="recovery-link">{{ localeStore.t('forgotPassword') }}</RouterLink>
      </div>

      <div v-if="feedback" id="login-feedback" class="login-feedback" :class="{ success: feedback === 'success' }" :role="feedback === 'success' ? 'status' : 'alert'">
        <span class="feedback-icon" aria-hidden="true">{{ feedback === 'success' ? '✓' : '!' }}</span>
        <span>{{ feedbackText }}</span>
      </div>
      <button class="primary-btn" type="submit" :disabled="loading" :aria-busy="loading">
        {{ loading ? localeStore.t('pleaseWait') : localeStore.t('login') }}
      </button>

      </form>
      <div class="auth-footer">
        <span>{{ localeStore.t('newUserJoin') }}</span>
        <RouterLink :to="{ path: '/register', query: route.query }" class="register-link">{{ localeStore.t('register') }}</RouterLink>
      </div>
    </div>
  </div>
</template>

<style scoped>
@import '../styles/variables.scss';

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

.recovery-row {
  display: flex;
  justify-content: flex-end;
  margin: -10px 0 16px;
}

.recovery-link {
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  padding: 8px 0 8px 12px;
  font-size: 14px;
  line-height: 1.5;
  color: #666;
  text-align: end;
}

.auth-footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  align-items: center;
  column-gap: 8px;
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid #e6e8ed;
  color: #666;
  font-size: 13px;
  line-height: 1.6;
  text-align: center;
}

.register-link {
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  padding: 8px 4px;
  color: #507600;
  font-weight: 700;
}

.recovery-link:hover, .register-link:hover { text-decoration: underline; }
.recovery-link:focus-visible, .register-link:focus-visible { outline: 2px solid #406000; outline-offset: 3px; border-radius: 4px; }

.login-feedback {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin: 0 0 16px;
  padding: 12px 14px;
  border: 1px solid #f0d6cc;
  border-radius: 12px;
  background: #fff6f2;
  color: #98452e;
  font-size: 14px;
  line-height: 1.55;
  overflow-wrap: anywhere;
  text-align: start;
}
.feedback-icon { flex: 0 0 20px; width: 20px; height: 20px; margin-top: 1px; border: 1px solid currentColor; border-radius: 50%; text-align: center; font-weight: 700; line-height: 18px; }
.login-feedback.success { background: #f3f8ec; border-color: #d5e7bf; color: #4b721e; }
.input-box[aria-invalid="true"] { border-color: #bd765f; }
.input-box::-ms-reveal, .input-box::-ms-clear { display: none; }
.input-with-icon .input-box { padding-inline-end: 44px; }
.input-icon-btn { left: auto; right: auto; inset-inline-end: 12px; }
.form-label { display: block; }
.primary-btn:disabled { opacity: .65; cursor: wait; }

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
