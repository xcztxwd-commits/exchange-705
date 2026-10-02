<script setup lang="ts">
import BusinessPage from '@/advanced/components/business/BusinessPage.vue'
const { request, error: advancedError, writing: advancedWriting } = useBusinessLifecycle()
import ProtectedImage from '@/components/ProtectedImage.vue'
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useBusinessLifecycle } from '@/advanced/components/business/useBusinessLifecycle'
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
  return new URL(router.resolve({ path: '/register', query: { invite: inviteCode.value, edition: 'advanced' } }).href, window.location.origin).href
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
    inviteLink.value = inviteCode.value ? generateInviteLink() : ''
    
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
<BusinessPage :title="localeStore.t('inviteFriends')" :error="advancedError" :busy="advancedWriting || loading">
 <section class="card"><h2>{{ localeStore.t('inviteFriend') }}</h2><h2>{{ localeStore.text('分享邀请链接','Share your invitation') }}</h2><p class="muted">{{ localeStore.text('邀请信息使用您账户的实际邀请码与链接。','Your invitation uses your account’s actual referral code and link.') }}</p><div class="referral-qr"><ProtectedImage v-if="qrCodeUrl" :src="qrCodeUrl" :alt="localeStore.t('inviteQRCode')" style="width:160px;height:160px" /><p v-else class="muted">{{ loading ? localeStore.t('generatingQRCode') : '—' }}</p></div><div class="row"><span>{{ localeStore.t('inviteCode') }}</span><span>{{ loading || advancedError ? '—' : inviteCode || '—' }}</span></div><label class="field">{{ localeStore.text('邀请链接','Referral link') }}<input :value="inviteLink" readonly :placeholder="loading ? localeStore.t('loading') : '—'" /></label></section><button class="primary" :disabled="!inviteLink || loading || !!advancedError" @click="copyInviteLink">{{ localeStore.t('copyInviteLink') }}</button><section class="card"><h2>{{ localeStore.text('使用说明','Instructions') }}</h2><p class="muted">{{ localeStore.t('scanQRCodeOrEnterInviteCode') }}</p></section><button v-if="advancedError" @click="loadInviteInfo">{{ localeStore.text('重试','Retry') }}</button>
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

.referral-qr{display:flex;align-items:center;justify-content:center;padding:24px;background:#f5f6f7}
</style>
