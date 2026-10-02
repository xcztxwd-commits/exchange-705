<script setup lang="ts">
import BusinessPage from '@/advanced/components/business/BusinessPage.vue'
const { request, error: advancedError, writing: advancedWriting } = useBusinessLifecycle()
import ProtectedImage from '@/components/ProtectedImage.vue'
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useBusinessLifecycle } from '@/advanced/components/business/useBusinessLifecycle'
import { useAuthStore } from '@/store/auth'
import { getImageUrl } from '@/utils/imageUrl'
import { useLocaleStore } from '@/store/locale'
import { formatDateTime, getSystemTimezone } from '@/utils/dateTime'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
auth.load()

// 多语言
const localeStore = useLocaleStore()
localeStore.loadLocale()

const loan = ref<any>(null)
const loading = ref(true)

// 使用 formatDateTime 作为 formatDate（它返回的是 YYYY-MM-DD HH:mm:ss 格式，英国时区）
const formatDate = formatDateTime

// 获取时区标识（英国时区，自动处理夏令时）
function getSystemTimeZoneLabel(date?: Date): string {
  const targetDate = date || new Date()
  // 使用 Intl.DateTimeFormat 获取英国时区的标识（自动处理夏令时）
  const formatter = new Intl.DateTimeFormat('en-GB', {
    timeZone: getSystemTimezone(),
    timeZoneName: 'short'
  })
  const parts = formatter.formatToParts(targetDate)
  const timeZoneName = parts.find(p => p.type === 'timeZoneName')?.value || 'GMT'
  return timeZoneName
}

// 格式化金额
function formatMoney(v: number | string | undefined | null) {
  const n = Number(v || 0)
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// LoanRecord rates are already percentage points, matching LoanSetting and LoanService.
function formatRate(v: number | string | undefined | null) {
  const n = Number(v || 0)
  return n.toFixed(2)
}

// 获取名字（从realName中提取）
function getFirstName(realName: string | null | undefined) {
  if (!realName) return '1'
  // 如果realName是中文，取第一个字作为名字
  if (/[\u4e00-\u9fa5]/.test(realName)) {
    return realName.charAt(0) || '1'
  }
  // 如果是英文，取第一个单词
  const parts = realName.split(' ')
  return parts[0] || '1'
}

// 获取姓氏（从realName中提取）
function getLastName(realName: string | null | undefined) {
  if (!realName) return '1'
  // 如果realName是中文，取除第一个字外的部分作为姓氏
  if (/[\u4e00-\u9fa5]/.test(realName)) {
    return realName.substring(1) || '1'
  }
  // 如果是英文，取最后一个单词
  const parts = realName.split(' ')
  return parts[parts.length - 1] || '1'
}

// 获取签名图片URL（使用统一的 getImageUrl 函数）
function getSignatureImageUrl(url: string | null | undefined) {
  return getImageUrl(url)
}

// 处理图片加载错误
function handleImageError(e: Event) {
  const img = e.target as HTMLImageElement
  img.style.display = 'none'
}

// 加载贷款信息
async function loadLoan() {
  const loanId = route.query.id
  if (!loanId) {
    alert(localeStore.t('loanIdNotExists'))
    router.back()
    return
  }

  try {
    const res: any = await request.get(`/loan/${loanId}`)
    if (res && res.success && res.data) {
      loan.value = res.data
    } else {
      alert(localeStore.t('loadLoanInfoFailed'))
      router.back()
    }
  } catch (e: any) {
    alert(e.message || localeStore.t('loadFailed'))
    router.back()
  } finally {
    loading.value = false
  }
}

// 跳转到签名页面
function goToSign() {
  if (!loan.value) return
  // 如果已经签署，不允许再次签署
  if (loan.value.contractSigned) {
    return
  }
  router.push({
    path: '/loan/sign',
    query: { id: loan.value.id.toString() }
  })
}

onMounted(() => {
  loadLoan()
})
</script>

<template>
<BusinessPage :title="localeStore.t('loanAgreement')" :error="advancedError" :busy="advancedWriting">
<div class="contract-wrapper" v-if="loan && !loading">
      <div class="contract-box card">
        <div class="contract-title">{{ localeStore.t('loanAgreement') }} #{{ loan.id }}</div>
        <div class="contract-date">{{ formatDate(loan.createdAt) }} {{ loan.createdAt ? getSystemTimeZoneLabel(new Date(loan.createdAt)) : '' }}</div>
        <div class="contract-subtitle">(「{{ localeStore.t('effectiveDate') }}」){{ localeStore.t('signedByBothParties') }}:</div>

        <div class="contract-section">
          <div class="section-title">{{ localeStore.t('borrower') }}</div>
          <div v-if="localeStore.locale !== 'ja'" class="info-item">
            <span class="info-label">{{ localeStore.t('firstName') }}:</span>
            <span class="info-value">{{ getFirstName(loan.realName) }}</span>
          </div>
          <div v-if="localeStore.locale !== 'ja'" class="info-item">
            <span class="info-label">{{ localeStore.t('lastName') }}:</span>
            <span class="info-value">{{ getLastName(loan.realName) }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">{{ localeStore.t('address') }}:</span>
            <span class="info-value">{{ loan.address || localeStore.t('notConfigured') }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">{{ localeStore.t('phone') }}:</span>
            <span class="info-value">{{ loan.phone || localeStore.t('notConfigured') }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">{{ localeStore.t('name') }}:</span>
            <span class="info-value">{{ loan.realName || localeStore.t('notConfigured') }}</span>
          </div>
          <div class="section-note">{{ localeStore.t('bothParties') }}</div>
        </div>

      <div class="contract-section">
        <div class="section-title">{{ localeStore.t('repaymentTerms') }}</div>
        <div class="info-item">
          <span class="info-label">{{ localeStore.t('loanAmount') }}:</span>
          <span class="info-value highlight">{{ formatMoney(loan.amount) }}</span>
        </div>
        <div class="section-text">
          {{ localeStore.text('借款人同意償還借款 {amount}。', 'The borrower agrees to repay {amount} (the loan).', { amount: formatMoney(loan.amount) }) }}
        </div>
      </div>

      <div class="contract-section">
        <div class="section-title">{{ localeStore.t('terms') }}</div>
        <div class="info-item">
          <span class="info-label">{{ localeStore.t('interestRate') }}:</span>
          <span class="info-value highlight">{{ formatRate(loan.dailyRate) }}%</span>
        </div>
        <div class="section-text">
          {{ localeStore.text('雙方同意日利率為 {rate}%。', 'The agreed daily interest rate is {rate}%.', { rate: formatRate(loan.dailyRate) }) }}
        </div>
        <div class="info-item">
          <span class="info-label">{{ localeStore.t('loanTerm') }}:</span>
          <span class="info-value highlight">{{ loan.days }}{{ localeStore.t('daysUnit') }}</span>
        </div>
        <div class="section-text">
          {{ localeStore.text('借款期限為 {days} 天。', 'The loan term is {days} days.', { days: loan.days }) }}
        </div>
        <div class="section-text">
          {{ localeStore.t('repaymentMethodDescription') }}
        </div>
        <div class="section-text">
          {{ localeStore.text('借款人同意到期前償還本金 {principal} 及利息 {interest}。', 'The borrower agrees to repay principal of {principal} and interest of {interest} by maturity.', { principal: formatMoney(loan.amount), interest: formatMoney(loan.totalInterest) }) }}
        </div>
      </div>

      <div class="contract-section">
        <div class="section-title">{{ localeStore.t('repaymentApplicability') }}</div>
        <div class="info-item">
          <span class="info-label">{{ localeStore.t('overdueFeeLabel') }}</span>
          <span class="info-value highlight">{{ formatRate(loan.overdueRate ?? 0.25) }}%</span>
        </div>
        <div class="section-text">
          {{ localeStore.text('逾期還款每天產生 {rate}% 的逾期費用，按日計算；這是逾期費而非罰金。', 'Late repayment incurs a daily late fee of {rate}%, calculated daily. This is a late fee, not a fine.', { rate: formatRate(loan.overdueRate ?? 0.25) }) }}
        </div>
      </div>

      <div class="contract-section">
        <div class="section-title">{{ localeStore.t('earlyRepayment') }}</div>
        <div class="section-text">
          {{ localeStore.t('earlyRepaymentDescription') }}
        </div>
      </div>

      <div class="contract-section">
        <div class="section-title">{{ localeStore.t('default') }}</div>
        <div class="section-text">
          {{ localeStore.t('defaultDescription') }}
        </div>
      </div>

      <div class="contract-section">
        <div class="section-title">{{ localeStore.t('fees') }}</div>
        <div class="section-text">
          {{ localeStore.t('feesDescription') }}
        </div>
      </div>

      <div class="contract-section">
        <div class="section-title">{{ localeStore.t('severability') }}</div>
        <div class="section-text">
          {{ localeStore.t('severabilityDescription') }}
        </div>
      </div>

      <div class="contract-section">
        <div class="section-title">{{ localeStore.t('legalEffect') }}</div>
        <div class="section-text">
          {{ localeStore.t('legalEffectDescription') }}
        </div>
      </div>

      <div class="contract-section">
        <div class="section-title">{{ localeStore.t('otherTerms') }}</div>
        <div class="section-text">
          {{ localeStore.t('otherTermsDescription') }}
        </div>
      </div>

      <div class="contract-section">
        <div class="section-title">{{ localeStore.t('governingLaw') }}</div>
        <div class="section-text">
          {{ localeStore.t('governingLawDescription') }}
        </div>
      </div>

      <div class="contract-section">
        <div class="section-title">{{ localeStore.t('completeAgreement') }}</div>
        <div class="section-text">
          {{ localeStore.t('completeAgreementDescription') }}
        </div>
      </div>

      <div class="contract-section" v-if="loan.contractSigned">
        <div class="section-text" style="margin-top: 24px;">
          {{ localeStore.t('bothPartiesAgreeToSign') }}
        </div>
        <div class="signature-section">
          <div class="signature-info">
            <div class="signature-label">{{ localeStore.t('name') }}: {{ loan.realName || localeStore.t('notConfigured') }}</div>
            <div class="signature-label">{{ localeStore.t('borrower') }}</div>
            <div class="signature-label">{{ localeStore.t('borrower') }}{{ localeStore.t('signature') }}:</div>
          </div>
          <div class="signature-image" v-if="loan.signatureImage">
            <ProtectedImage :src="getSignatureImageUrl(loan.signatureImage)" :alt="localeStore.t('signature')" @error="handleImageError" />
          </div>
          <div class="signature-date" v-if="loan.updatedAt">
            {{ localeStore.t('signDate') }}: {{ formatDate(loan.updatedAt) }} {{ loan.updatedAt ? getSystemTimeZoneLabel(new Date(loan.updatedAt)) : '' }}
          </div>
        </div>
      </div>
      </div>
    </div>

    <div class="contract-footer" v-if="loan && !loading && !loan.contractSigned">
      <button class="sign-btn" @click="goToSign">{{ localeStore.t('signature') }}</button>
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
