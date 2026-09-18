<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useLocaleStore } from '@/store/locale'
import { useAuthStore } from '@/store/auth'
import request from '@/utils/request'

const router = useRouter()
const localeStore = useLocaleStore()
localeStore.loadLocale()
const auth = useAuthStore()

const email = ref('')
const password = ref('')
const showPwd = ref(false)
const loading = ref(false)
// 显示提示消息（与其他页面样式一致）
function showToastMessage(message: string, type: 'success' | 'error' = 'error') {
  // 移除之前的提示
  const existingToasts = document.querySelectorAll('.toast-message')
  existingToasts.forEach(toast => {
    if (document.body.contains(toast)) {
      document.body.removeChild(toast)
    }
  })
  
  // 创建新的提示
  const toast = document.createElement('div')
  toast.className = `toast-message ${type}`
  toast.textContent = message
  
  // 直接设置内联样式，确保样式生效
  toast.style.cssText = `
    position: fixed !important;
    top: 50% !important;
    left: 50% !important;
    transform: translate(-50%, -50%) !important;
    padding: 18px 36px !important;
    border-radius: 12px !important;
    font-size: 18px !important;
    font-weight: 600 !important;
    z-index: 99999 !important;
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.3) !important;
    pointer-events: none !important;
    white-space: nowrap !important;
    min-width: 200px !important;
    text-align: center !important;
    opacity: 0 !important;
    transition: opacity 0.3s ease-in-out !important;
    ${type === 'success' ? 'background: #73b100 !important; color: #fff !important;' : 'background: #ff4444 !important; color: #fff !important;'}
  `
  
  document.body.appendChild(toast)
  
  // 强制重排，确保样式应用
  void toast.offsetHeight
  
  // 触发动画 - 使用双重 requestAnimationFrame 确保动画执行
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      toast.style.opacity = '1'
    })
  })
  
  // 自动移除
  setTimeout(() => {
    toast.style.opacity = '0'
    setTimeout(() => {
      if (document.body.contains(toast)) {
        document.body.removeChild(toast)
      }
    }, 300)
  }, 3000) // 显示3秒
}

const onSubmit = async () => {
  if (loading.value) return
  if (!email.value || !password.value) {
    showToastMessage(localeStore.t('pleaseEnterEmailAndPassword'), 'error')
    return
  }
  loading.value = true
  try {
    const res: any = await request.post('/auth/login', {
      account: email.value,
      password: password.value,
      loginType: 'email',
    })
    auth.setAuth(res.token, res.user)
    showToastMessage(localeStore.t('loginSuccess'), 'success')
    setTimeout(() => {
      router.replace('/home')
    }, 500)
  } catch (e: any) {
    showToastMessage(e?.message || localeStore.t('loginFail'), 'error')
  } finally {
    loading.value = false
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

      <div class="auth-title">{{ localeStore.t('emailLogin') }}</div>

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
          <button class="input-icon-btn" type="button" @click="togglePwd">
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

      <div class="link-row">
        <span class="muted">
          {{ localeStore.t('newUserJoin') }}
          <a class="link-primary" @click="router.push('/register')"> {{ localeStore.t('register') }} </a>
        </span>
        <a class="link-primary" @click="router.push('/forgot-password')">{{ localeStore.t('forgotPassword') }}</a>
      </div>

      <button class="primary-btn" :disabled="loading" @click="onSubmit">
        {{ loading ? localeStore.t('pleaseWait') : localeStore.t('login') }}
      </button>

      <!-- <div class="section-tip">{{ localeStore.t('noAccountTip') }}</div> -->
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

.link-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 14px;
  color: $subtext-color;
  margin: 12px 0 18px;
}

.link-primary {
  color: $primary-color;
  font-weight: 700;
}

.muted {
  color: $subtext-color;
}

.section-tip {
  margin-top: 20px;
  text-align: center;
  color: $primary-color;
  font-weight: 700;
}

/* Toast消息样式（与其他页面一致） */
.toast-message {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  padding: 18px 36px;
  border-radius: 12px;
  font-size: 18px;
  font-weight: 600;
  z-index: 99999;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.3);
  pointer-events: none;
  white-space: nowrap;
  min-width: 200px;
  text-align: center;
}

.toast-message.success {
  background: #73b100;
  color: #fff;
}

.toast-message.error {
  background: #ff4444;
  color: #fff;
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