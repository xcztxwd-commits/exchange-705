<script setup lang="ts">
import ProtectedImage from '@/components/ProtectedImage.vue'
import { computed, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import request from '@/utils/request'
import { useLocaleStore } from '@/store/locale'
import { getImageUrl } from '@/utils/imageUrl'
import Tabbar from '@/components/Tabbar.vue'
const router = useRouter()
const localeStore = useLocaleStore()
const loading = ref(true)
const submitting = ref(false)
const kycVerified = ref(false)
const status = ref('NOT_SUBMITTED')
const remark = ref('')
const message = ref('')
const form = ref({ realName: '', idNumber: '', phone: '', address: '', idFrontImage: '', idBackImage: '', handheldImage: '' })
const handheldFile = ref<File | null>(null)
const locked = computed(() => loading.value || !kycVerified.value || ['PENDING', 'APPROVED'].includes(status.value))
const statusText = computed(() => ({
  PENDING: localeStore.text('貸款資料審核中', 'Loan details under review'), APPROVED: localeStore.text('貸款資料已通過', 'Loan details approved'),
  REJECTED: localeStore.text('貸款資料已拒絕，請修改後重新提交', 'Loan details rejected. Update and resubmit.'),
  NEEDS_UPDATE: localeStore.text('請依據已審核的實名身份，重新提交完整貸款資料。', 'Resubmit complete loan details using your approved identity.')
}[status.value] || ''))
async function load() {
  loading.value = true
  try {
    const res: any = await request.get('/loan/personal-info/status')
    if (!res?.success) throw new Error(res?.message || localeStore.t('applicationFailed'))
    kycVerified.value = res.kycVerified === true
    status.value = res.status || 'NOT_SUBMITTED'
    remark.value = res.reviewRemark || ''
    form.value = { realName: '', idNumber: '', phone: '', address: '', idFrontImage: '', idBackImage: '', handheldImage: '', ...res.data }
  } catch (e: any) { message.value = e.message; kycVerified.value = false }
  finally { loading.value = false }
}
function selectHandheld(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (!file.type.startsWith('image/') || file.size > 5 * 1024 * 1024) {
    message.value = localeStore.t('imageSizeLimit'); input.value = ''; return
  }
  handheldFile.value = file
}
async function submit() {
  if (locked.value || submitting.value) return
  if (!form.value.phone.trim() || !form.value.address.trim() || (!handheldFile.value && !form.value.handheldImage)) {
    message.value = localeStore.t('pleaseFillCompletePersonalInfo'); return
  }
  submitting.value = true; message.value = ''
  try {
    const data = new FormData()
    data.append('phone', form.value.phone.trim())
    data.append('address', form.value.address.trim())
    if (handheldFile.value) data.append('handheldImageFile', handheldFile.value)
    else data.append('handheldImage', form.value.handheldImage)
    const res: any = await request.post('/loan/personal-info/submit', data)
    if (!res?.success) throw new Error(res?.message || localeStore.t('applicationFailed'))
    message.value = localeStore.t('submitSuccessWaitReview')
    handheldFile.value = null
    await load()
  } catch (e: any) { message.value = e.message }
  finally { submitting.value = false }
}
onMounted(load)
</script>

<template>
  <div class="loan-personal-info-page">
    <div class="page-header">
      <button class="back-button" @click="router.back()">‹</button>
      <div class="header-title">{{ localeStore.text('貸款資料審核', 'Loan details review') }}</div>
      <div class="header-placeholder"></div>
    </div>
    <div class="page-content">
      <p v-if="loading">{{ localeStore.t('loading') }}</p>
      <div v-else-if="!kycVerified" class="verified-notice">
        <p>{{ localeStore.text('請先完成帳戶實名認證，再補充貸款資料。', 'Complete account identity verification before submitting loan details.') }}</p>
        <button class="submit-btn" @click="router.push('/verification')">{{ localeStore.t('verification') }}</button>
      </div>
      <template v-else>
        <p>{{ localeStore.text('姓名、證件號及正反面照片沿用已審核的實名資料；僅需補充聯絡方式、地址及手持證件照。', 'Your approved name, ID number and ID photos are reused. Add contact details, address and a photo holding your ID.') }}</p>
        <p v-if="statusText" role="status">{{ statusText }}</p>
        <p v-if="remark">{{ remark }}</p>
        <div class="form-section"><label class="form-label">{{ localeStore.t('realName') }}<input :value="form.realName" class="form-input" readonly /></label></div>
        <div class="form-section"><label class="form-label">{{ localeStore.t('idNumber') }}<input :value="form.idNumber" class="form-input" readonly /></label></div>
        <div class="upload-section">
          <ProtectedImage v-if="form.idFrontImage" :src="getImageUrl(form.idFrontImage)" :alt="localeStore.t('uploadIdFront')" style="width:45%;object-fit:contain" />
          <ProtectedImage v-if="form.idBackImage" :src="getImageUrl(form.idBackImage)" :alt="localeStore.t('uploadIdBack')" style="width:45%;object-fit:contain" />
        </div>
        <div class="form-section"><label class="form-label">{{ localeStore.t('phoneNumber') }}<input v-model="form.phone" type="tel" maxlength="32" class="form-input" :disabled="locked" :placeholder="localeStore.text('含國家區號，例如 +81…', 'Include country code, e.g. +81…')" /></label></div>
        <div class="form-section"><label class="form-label">{{ localeStore.t('homeAddress') }}<textarea v-model="form.address" maxlength="500" class="form-textarea" :disabled="locked"></textarea></label></div>
        <div class="form-section">
          <label class="form-label">{{ localeStore.t('uploadHandheldId') }}<input type="file" accept="image/*" :disabled="locked" @change="selectHandheld" /></label>
          <ProtectedImage v-if="form.handheldImage" :src="getImageUrl(form.handheldImage)" :alt="localeStore.t('uploadHandheldId')" style="max-width:100%;max-height:180px" />
        </div>
        <button v-if="!locked" class="submit-btn" :disabled="submitting" @click="submit">{{ submitting ? localeStore.t('submitting') : localeStore.t('submitReview') }}</button>
      </template>
      <p v-if="message" role="alert">{{ message }}</p>
    </div>
    <Tabbar />
  </div>
</template>

<style scoped>
.loan-personal-info-page {
  min-height: 100vh;
  background: #f5f5f5;
  padding-bottom: 80px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px;
  background: #fff;
  border-bottom: 1px solid #eee;
}

.back-button {
  border: 0;
  background: transparent;
  font-size: 26px;
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

.page-content {
  padding: 20px 16px;
}

.page-content > p { font-size: 14px; line-height: 1.6; margin-bottom: 16px; }
.form-label { display: block; }
.form-label > input, .form-label > textarea { margin-top: 8px; }
.upload-section { margin-bottom: 20px; }
.verified-notice {
  flex-direction: column;
  background: #e8f5e9;
  border: 1px solid #2abf4b;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 24px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.notice-icon {
  width: 32px;
  height: 32px;
  background: #2abf4b;
  color: #fff;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  font-weight: bold;
}

.notice-text {
  font-size: 14px;
  color: #2abf4b;
  font-weight: 500;
}

.form-section {
  margin-bottom: 24px;
}

.form-label {
  font-size: 14px;
  color: #333;
  margin-bottom: 8px;
  font-weight: 500;
}

.required {
  color: #ff4444;
}

.form-input,
.form-textarea {
  width: 100%;
  padding: 12px 16px;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  font-size: 16px;
  background: #fff;
  box-sizing: border-box;
}

.form-input:disabled,
.form-textarea:disabled {
  background: #f5f5f5;
  color: #999;
  cursor: not-allowed;
}

.form-input::placeholder,
.form-textarea::placeholder {
  color: #999;
}

.form-textarea {
  resize: vertical;
  min-height: 80px;
}

.submit-btn {
  width: 100%;
  padding: 16px;
  background: #2abf4b;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  margin-top: 20px;
}

.submit-btn:disabled {
  background: #ccc;
  cursor: not-allowed;
}

/* Toast提示 */
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
  background: #2abf4b;
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

/* 上传区域 */
.upload-section {
  margin-top: 8px;
}

.upload-item {
  margin-bottom: 16px;
}

.upload-area {
  position: relative;
  width: 100%;
  height: 180px;
  border: 1px dashed #e0e0e0;
  border-radius: 8px;
  background: #fafafa;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  overflow: hidden;
}

.upload-area.has-image {
  border-color: #2abf4b;
}

.upload-area:active:not(.has-image) {
  background: #f0f0f0;
}

.upload-preview {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.upload-placeholder {
  width: 80px;
  height: 80px;
  opacity: 0.5;
}

.upload-plus {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  font-size: 48px;
  color: #999;
  font-weight: 300;
}

.upload-label {
  text-align: center;
  margin-top: 8px;
  font-size: 12px;
  color: #666;
}

/* 手机号输入区域 */
.phone-input-wrapper {
  position: relative;
  display: flex;
  gap: 8px;
  align-items: stretch;
}

.country-code-select { flex: 0 0 112px; width: 112px; }

.phone-input {
  flex: 1;
  min-width: 0;
}

</style>
