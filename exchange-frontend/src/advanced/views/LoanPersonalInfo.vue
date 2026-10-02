<script setup lang="ts">
import BusinessPage from '@/advanced/components/business/BusinessPage.vue'
const { request, error: advancedError, writing: advancedWriting } = useBusinessLifecycle()
import ProtectedImage from '@/components/ProtectedImage.vue'
import { computed, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useBusinessLifecycle } from '@/advanced/components/business/useBusinessLifecycle'
import { useLocaleStore } from '@/store/locale'
import { getImageUrl } from '@/utils/imageUrl'
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
<BusinessPage :title="localeStore.t('loanInfo')" :error="advancedError" :busy="advancedWriting">
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
        <div class="form-section card"><label class="form-label">{{ localeStore.t('realName') }}<input :value="form.realName" class="form-input" readonly /></label></div>
        <div class="form-section card"><label class="form-label">{{ localeStore.t('idNumber') }}<input :value="form.idNumber" class="form-input" readonly /></label></div>
        <div class="upload-section card">
          <ProtectedImage v-if="form.idFrontImage" :src="getImageUrl(form.idFrontImage)" :alt="localeStore.t('uploadIdFront')" style="width:45%;object-fit:contain" />
          <ProtectedImage v-if="form.idBackImage" :src="getImageUrl(form.idBackImage)" :alt="localeStore.t('uploadIdBack')" style="width:45%;object-fit:contain" />
        </div>
        <div class="form-section card"><label class="form-label">{{ localeStore.t('phoneNumber') }}<input v-model="form.phone" type="tel" maxlength="32" class="form-input" :disabled="locked" :placeholder="localeStore.text('含國家區號，例如 +81…', 'Include country code, e.g. +81…')" /></label></div>
        <div class="form-section card"><label class="form-label">{{ localeStore.t('homeAddress') }}<textarea v-model="form.address" maxlength="500" class="form-textarea" :disabled="locked"></textarea></label></div>
        <div class="form-section card">
          <label class="form-label">{{ localeStore.t('uploadHandheldId') }}<input type="file" accept="image/*" :disabled="locked" @change="selectHandheld" /></label>
          <ProtectedImage v-if="form.handheldImage" :src="getImageUrl(form.handheldImage)" :alt="localeStore.t('uploadHandheldId')" style="max-width:100%;max-height:180px" />
        </div>
        <button v-if="!locked" class="submit-btn" :disabled="submitting" @click="submit">{{ submitting ? localeStore.t('submitting') : localeStore.t('submitReview') }}</button>
      </template>
      <p v-if="message" role="alert">{{ message }}</p>
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
