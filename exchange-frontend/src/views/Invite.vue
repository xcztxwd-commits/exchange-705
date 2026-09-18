<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import Tabbar from '@/components/Tabbar.vue'
import request from '@/utils/request'
import { useAuthStore } from '@/store/auth'
import { useLocaleStore } from '@/store/locale'
import QRCode from 'qrcode'

const router = useRouter()
const auth = useAuthStore()
auth.load()

const localeStore = useLocaleStore()
localeStore.loadLocale()

const inviteCode = ref('')
const inviteLink = ref('')
const qrCodeUrl = ref('')
const loading = ref(false)

// 生成邀请链接
function generateInviteLink() {
  const baseUrl = window.location.origin
  const hash = window.location.hash.split('/')[0] || '#'
  return `${baseUrl}${hash}/register?invite=${inviteCode.value}`
}

// 复制邀请链接
async function copyInviteLink() {
  try {
    await navigator.clipboard.writeText(inviteLink.value)
    alert(localeStore.t('inviteLinkCopied'))
  } catch (e) {
    // 降级方案
    const textarea = document.createElement('textarea')
    textarea.value = inviteLink.value
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
    alert(localeStore.t('inviteLinkCopied'))
  }
}

// 加载邀请信息
async function loadInviteInfo() {
  loading.value = true
  try {
    const res: any = await request.get('/user/invite/info')
    inviteCode.value = res.inviteCode || ''
    inviteLink.value = generateInviteLink()
    
    // 生成二维码
    if (inviteLink.value) {
      qrCodeUrl.value = await QRCode.toDataURL(inviteLink.value, {
        width: 200,
        margin: 2,
      })
    }
  } catch (e: any) {
    console.error('Failed to load invite information:', e)
    alert(e?.message || localeStore.t('loadFailed'))
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadInviteInfo()
})
</script>

<template>
  <div class="invite-page">
    <div class="card">
      <!-- 头部 -->
      <div class="header">
        <div class="back-btn" @click="router.back()">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none">
            <path d="M15 18L9 12L15 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <div class="title">{{ localeStore.t('inviteFriend') }}</div>
      </div>

      <!-- 邀请链接 -->
      <div class="invite-link-section">
        <div class="link-box">
          <div class="link-text">{{ inviteLink || localeStore.t('loading') }}</div>
        </div>
        <button class="copy-btn" @click="copyInviteLink" :disabled="!inviteLink">
          {{ localeStore.t('copyInviteLink') }}
        </button>
      </div>

      <!-- 二维码 -->
      <div class="qr-section">
        <div v-if="qrCodeUrl" class="qr-code">
          <img :src="qrCodeUrl" :alt="localeStore.t('inviteQRCode')" />
        </div>
        <div v-else class="qr-loading">{{ localeStore.t('generatingQRCode') }}</div>
      </div>

      <!-- 邀请码 -->
      <div class="invite-code-section">
        <div class="code-box">
          <span class="code-label">{{ localeStore.t('inviteCode') }}:</span>
          <span class="code-value">{{ inviteCode || localeStore.t('loading') }}</span>
        </div>
      </div>

      <!-- 说明文字 -->
      <div class="instruction">
        {{ localeStore.t('scanQRCodeOrEnterInviteCode') }}
      </div>
    </div>

    <Tabbar />
  </div>
</template>

<style scoped>
.invite-page {
  background: #f5f7fb;
  min-height: 100vh;
  padding: 12px;
  padding-bottom: 80px;
  display: flex;
  justify-content: center;
}

.card {
  width: 100%;
  max-width: 430px;
  background: #fff;
  border-radius: 16px;
  padding: 20px;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.06);
}

.header {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 24px;
}

.back-btn {
  position: absolute;
  left: 0;
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: #333;
  border-radius: 8px;
  transition: background-color 0.2s;
}

.back-btn:hover {
  background: #f5f5f5;
}

.title {
  font-size: 18px;
  font-weight: 700;
  color: #333;
}

.invite-link-section {
  margin-bottom: 24px;
}

.link-box {
  background: #f8f8f8;
  border-radius: 12px;
  padding: 12px 16px;
  margin-bottom: 12px;
  word-break: break-all;
}

.link-text {
  font-size: 13px;
  color: #666;
  line-height: 1.5;
}

.copy-btn {
  width: 100%;
  background: #85bd00;
  color: #fff;
  border: none;
  border-radius: 12px;
  padding: 12px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.2s;
}

.copy-btn:hover:not(:disabled) {
  opacity: 0.9;
}

.copy-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.qr-section {
  display: flex;
  justify-content: center;
  margin-bottom: 24px;
}

.qr-code {
  padding: 16px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.qr-code img {
  display: block;
  width: 200px;
  height: 200px;
}

.qr-loading {
  padding: 80px 20px;
  text-align: center;
  color: #999;
  font-size: 14px;
}

.invite-code-section {
  margin-bottom: 24px;
}

.code-box {
  background: #e8f5e9;
  border-radius: 12px;
  padding: 16px;
  text-align: center;
}

.code-label {
  font-size: 14px;
  color: #666;
  margin-right: 8px;
}

.code-value {
  font-size: 18px;
  font-weight: 700;
  color: #2e7d32;
}

.instruction {
  text-align: center;
  font-size: 14px;
  color: #666;
  line-height: 1.6;
}
</style>



