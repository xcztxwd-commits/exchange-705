<template>
  <div class="change-password-page">
    <div class="page-header">
      <div class="back-button" @click="router.back()">
        <svg t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
          <path d="M768 903.232l-50.432 56.768L256 512l461.568-448 50.432 56.768L364.928 512z" fill="#333"></path>
        </svg>
      </div>
      <div class="header-title">{{ localeStore.t('changePassword') }}</div>
      <div class="header-placeholder"></div>
    </div>

    <div class="change-password-content">
      <!-- 验证码输入 -->
      <div class="form-item">
        <div class="form-label">{{ localeStore.t('verifyCode') }}</div>
        <div class="code-input-wrapper">
          <input 
            type="text" 
            v-model="verifyCode" 
            class="code-input" 
            :placeholder="localeStore.t('verifyCodePlaceholder')"
            maxlength="6"
          />
          <button 
            class="send-button" 
            @click="sendCode" 
            :disabled="sending || countdown > 0"
          >
            {{ countdown > 0 ? `${countdown}${localeStore.t('seconds')}` : localeStore.t('send') }}
          </button>
        </div>
      </div>

      <!-- 新密码输入 -->
      <div class="form-item">
        <div class="form-label">{{ localeStore.t('newPassword') }}</div>
        <div class="password-input-wrapper">
          <input 
            type="password" 
            v-model="password" 
            class="password-input" 
            :placeholder="localeStore.t('passwordPlaceholder')"
            v-if="!showPassword"
          />
          <input 
            type="text" 
            v-model="password" 
            class="password-input" 
            :placeholder="localeStore.t('passwordPlaceholder')"
            v-else
          />
          <button 
            class="password-toggle" 
            @click="showPassword = !showPassword"
          >
            <svg v-if="showPassword" t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="20" height="20">
              <path d="M512 298.666667c-162.133333 0-285.866667 68.266667-375.466667 213.333333 89.6 145.066667 213.333333 213.333333 375.466667 213.333333s285.866667-68.266667 375.466667-213.333333c-89.6-145.066667-213.333333-213.333333-375.466667-213.333333z m0 469.333333c-183.466667 0-328.533333-85.333333-426.666667-256 98.133333-170.666667 243.2-256 426.666667-256s328.533333 85.333333 426.666667 256c-98.133333 170.666667-243.2 256-426.666667 256z m0-170.666667c46.933333 0 85.333333-38.4 85.333333-85.333333s-38.4-85.333333-85.333333-85.333333-85.333333 38.4-85.333333 85.333333 38.4 85.333333 85.333333 85.333333z" fill="#999"></path>
            </svg>
            <svg v-else t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="20" height="20">
              <path d="M508.8 704c-70.4 0-128-57.6-128-128 0-12.8 2.133333-25.6 5.333333-38.4l-70.4-70.4c-25.6 38.4-38.4 83.2-38.4 128 0 140.8 115.2 256 256 256 44.8 0 89.6-12.8 128-38.4l-70.4-70.4c-12.8 3.2-25.6 5.333333-38.4 5.333333z m256-128c0 12.8-2.133333 25.6-5.333333 38.4l70.4 70.4c25.6-38.4 38.4-83.2 38.4-128 0-140.8-115.2-256-256-256-44.8 0-89.6 12.8-128 38.4l70.4 70.4c12.8-3.2 25.6-5.333333 38.4-5.333333 70.4 0 128 57.6 128 128z" fill="#999"></path>
              <path d="M764.8 576c-12.8-32-32-57.6-57.6-83.2l-70.4-70.4c25.6-19.2 44.8-44.8 57.6-76.8l128-128-57.6-57.6-128 128c-32 12.8-57.6 32-76.8 57.6l-70.4-70.4c-25.6-25.6-51.2-44.8-83.2-57.6l-128-128-57.6 57.6 128 128c-12.8 32-12.8 64 0 96l-128 128 57.6 57.6 128-128c32 12.8 64 12.8 96 0l70.4 70.4c25.6 25.6 51.2 44.8 83.2 57.6l128 128 57.6-57.6-128-128c12.8-32 12.8-64 0-96z" fill="#999"></path>
            </svg>
          </button>
        </div>
      </div>

      <!-- 确认密码输入 -->
      <div class="form-item">
        <div class="form-label">{{ localeStore.t('confirmPassword') }}</div>
        <div class="password-input-wrapper">
          <input 
            type="password" 
            v-model="confirmPassword" 
            class="password-input" 
            :placeholder="localeStore.t('confirmPasswordPlaceholder')"
            v-if="!showConfirmPassword"
          />
          <input 
            type="text" 
            v-model="confirmPassword" 
            class="password-input" 
            :placeholder="localeStore.t('confirmPasswordPlaceholder')"
            v-else
          />
          <button 
            class="password-toggle" 
            @click="showConfirmPassword = !showConfirmPassword"
          >
            <svg v-if="showConfirmPassword" t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="20" height="20">
              <path d="M512 298.666667c-162.133333 0-285.866667 68.266667-375.466667 213.333333 89.6 145.066667 213.333333 213.333333 375.466667 213.333333s285.866667-68.266667 375.466667-213.333333c-89.6-145.066667-213.333333-213.333333-375.466667-213.333333z m0 469.333333c-183.466667 0-328.533333-85.333333-426.666667-256 98.133333-170.666667 243.2-256 426.666667-256s328.533333 85.333333 426.666667 256c-98.133333 170.666667-243.2 256-426.666667 256z m0-170.666667c46.933333 0 85.333333-38.4 85.333333-85.333333s-38.4-85.333333-85.333333-85.333333-85.333333 38.4-85.333333 85.333333 38.4 85.333333 85.333333 85.333333z" fill="#999"></path>
            </svg>
            <svg v-else t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="20" height="20">
              <path d="M508.8 704c-70.4 0-128-57.6-128-128 0-12.8 2.133333-25.6 5.333333-38.4l-70.4-70.4c-25.6 38.4-38.4 83.2-38.4 128 0 140.8 115.2 256 256 256 44.8 0 89.6-12.8 128-38.4l-70.4-70.4c-12.8 3.2-25.6 5.333333-38.4 5.333333z m256-128c0 12.8-2.133333 25.6-5.333333 38.4l70.4 70.4c25.6-38.4 38.4-83.2 38.4-128 0-140.8-115.2-256-256-256-44.8 0-89.6 12.8-128 38.4l70.4 70.4c12.8-3.2 25.6-5.333333 38.4-5.333333 70.4 0 128 57.6 128 128z" fill="#999"></path>
              <path d="M764.8 576c-12.8-32-32-57.6-57.6-83.2l-70.4-70.4c25.6-19.2 44.8-44.8 57.6-76.8l128-128-57.6-57.6-128 128c-32 12.8-57.6 32-76.8 57.6l-70.4-70.4c-25.6-25.6-51.2-44.8-83.2-57.6l-128-128-57.6 57.6 128 128c-12.8 32-12.8 64 0 96l-128 128 57.6 57.6 128-128c32 12.8 64 12.8 96 0l70.4 70.4c25.6 25.6 51.2 44.8 83.2 57.6l128 128 57.6-57.6-128-128c12.8-32 12.8-64 0-96z" fill="#999"></path>
            </svg>
          </button>
        </div>
      </div>

      <!-- 提交按钮 -->
      <button class="submit-button" @click="submitChangePassword" :disabled="submitting">
        {{ submitting ? localeStore.t('submitting') : localeStore.t('confirm') }}
      </button>
    </div>

    <!-- Toast 提示 -->
    <div v-if="toastMessage" class="toast-message" :class="toastType">
      {{ toastMessage }}
    </div>

    <Tabbar />
  </div>
</template>

<script setup lang="ts">
import { ref, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import Tabbar from '@/components/Tabbar.vue'
import request from '@/utils/request'
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
      countdownTimer = window.setInterval(() => {
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

<style scoped>
.change-password-page {
  min-height: 100vh;
  background: #f8f8f8;
  padding-bottom: 80px;
}

/* 顶部导航 */
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

/* 内容区域 */
.change-password-content {
  padding: 20px 16px;
}

.form-item {
  background: #fff;
  border-radius: 12px;
  padding: 16px;
  margin-bottom: 12px;
}

.form-label {
  font-size: 14px;
  color: #666;
  margin-bottom: 12px;
}

/* 验证码输入 */
.code-input-wrapper {
  display: flex;
  gap: 12px;
  align-items: center;
}

.code-input {
  flex: 1;
  padding: 12px;
  background: #f5f7fb;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  color: #333;
  outline: none;
}

.send-button {
  padding: 12px 24px;
  background: #73b100;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
}

.send-button:active:not(:disabled) {
  opacity: 0.8;
}

.send-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 密码输入 */
.password-input-wrapper {
  position: relative;
  display: flex;
  align-items: center;
}

.password-input {
  flex: 1;
  padding: 12px 40px 12px 12px;
  background: #f5f7fb;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  color: #333;
  outline: none;
}

.password-toggle {
  position: absolute;
  right: 12px;
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 0;
}

.password-toggle:active {
  opacity: 0.8;
}

/* 提交按钮 */
.submit-button {
  width: 100%;
  padding: 14px;
  background: #73b100;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  margin-top: 12px;
}

.submit-button:active:not(:disabled) {
  opacity: 0.8;
}

.submit-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* Toast 提示 */
.toast-message {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  padding: 12px 24px;
  background: rgba(0, 0, 0, 0.8);
  color: #fff;
  border-radius: 8px;
  font-size: 14px;
  z-index: 9999;
  max-width: 80%;
  text-align: center;
}

.toast-message.success {
  background: rgba(115, 177, 0, 0.9);
}

.toast-message.error {
  background: rgba(255, 68, 68, 0.9);
}
</style>

