<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import request from '@/utils/request'
import { useAuthStore } from '@/store/auth'
import { getImageUrl } from '@/utils/imageUrl'
import { useLocaleStore } from '@/store/locale'
import { formatDateTime } from '@/utils/dateTime'

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
    timeZone: 'Europe/London',
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

// 格式化利率（转换为百分比）
function formatRate(v: number | string | undefined | null) {
  const n = Number(v || 0)
  return (n * 100).toFixed(2)
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
  <div class="loan-contract-page">
    <div class="page-header">
      <div class="back-button" @click="router.back()">
        <svg t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
          <path d="M768 903.232l-50.432 56.768L256 512l461.568-448 50.432 56.768L364.928 512z" fill="#333"></path>
        </svg>
      </div>
      <div class="header-title">{{ localeStore.t('loan') }}</div>
      <div class="header-placeholder"></div>
    </div>

    <div class="contract-wrapper" v-if="loan && !loading">
      <div class="contract-box">
        <div class="contract-title">{{ localeStore.t('loanAgreement') }}("{{ loan.id }}"){{ localeStore.t('date') }}</div>
        <div class="contract-date">{{ formatDate(loan.createdAt) }} {{ loan.createdAt ? getSystemTimeZoneLabel(new Date(loan.createdAt)) : '' }}</div>
        <div class="contract-subtitle">(「{{ localeStore.t('effectiveDate') }}」){{ localeStore.t('signedByBothParties') }}:</div>

        <div class="contract-section">
          <div class="section-title">{{ localeStore.t('borrower') }}</div>
          <div class="info-item">
            <span class="info-label">{{ localeStore.t('firstName') }}:</span>
            <span class="info-value">{{ getFirstName(loan.realName) }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">{{ localeStore.t('lastName') }}:</span>
            <span class="info-value">{{ getLastName(loan.realName) }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">{{ localeStore.t('address') }}:</span>
            <span class="info-value">{{ loan.address || localeStore.t('defaultAddress') }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">{{ localeStore.t('phone') }}:</span>
            <span class="info-value">{{ loan.phone || localeStore.t('defaultPhone') }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">{{ localeStore.t('name') }}:</span>
            <span class="info-value">{{ loan.realName || '1' }}</span>
          </div>
          <div class="section-note">{{ localeStore.t('bothParties') }}.</div>
        </div>

      <div class="contract-section">
        <div class="section-title">{{ localeStore.t('repaymentTerms') }}</div>
        <div class="info-item">
          <span class="info-label">{{ localeStore.t('loanAmount') }}:</span>
          <span class="info-value highlight">{{ formatMoney(loan.amount) }}</span>
        </div>
        <div class="section-text">
          {{ localeStore.t('borrowerAgreesToRepay') }}<span class="highlight">{{ formatMoney(loan.amount) }}</span>(「{{ localeStore.t('loan') }}」)。
        </div>
      </div>

      <div class="contract-section">
        <div class="section-title">{{ localeStore.t('terms') }}</div>
        <div class="info-item">
          <span class="info-label">{{ localeStore.t('interestRate') }}:</span>
          <span class="info-value highlight">{{ formatRate(loan.dailyRate) }}%</span>
        </div>
        <div class="section-text">
          {{ localeStore.t('bothParties') }}{{ localeStore.t('interestRate') }}<span class="highlight">{{ formatRate(loan.dailyRate) }}%</span>({{ localeStore.t('calculatedDaily') }})。
        </div>
        <div class="info-item">
          <span class="info-label">{{ localeStore.t('loanTerm') }}:</span>
          <span class="info-value highlight">{{ loan.days }}</span>
        </div>
        <div class="section-text">
          {{ localeStore.t('loanTermDays') }}<span class="highlight">{{ loan.days }}</span>{{ localeStore.t('days') }}。
        </div>
        <div class="section-text">
          {{ localeStore.t('repaymentMethodDescription') }}
        </div>
        <div class="section-text">
          {{ localeStore.t('borrowerAgreesToRepayAtMaturity') }}<span class="highlight">{{ formatMoney(loan.amount) }}</span>{{ localeStore.t('principalAnd') }}<span class="highlight">{{ formatMoney(loan.totalInterest) }}</span>{{ localeStore.t('interest') }}
        </div>
      </div>

      <div class="contract-section">
        <div class="section-title">{{ localeStore.t('repaymentApplicability') }}</div>
        <div class="info-item">
          <span class="info-label">{{ localeStore.t('overdueFeeLabel') }}</span>
          <span class="info-value highlight">{{ formatRate(loan.overdueRate || 0.0025) }}%</span>
        </div>
        <div class="section-text">
          {{ localeStore.t('overdueFeeDescription') }}<span class="highlight">{{ formatRate(loan.overdueRate || 0.0025) }}%</span>{{ localeStore.t('overdueFeeDescriptionEnd') }}
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
            <div class="signature-label">{{ localeStore.t('name') }}: {{ loan.realName || '1' }}</div>
            <div class="signature-label">{{ localeStore.t('borrower') }}</div>
            <div class="signature-label">{{ localeStore.t('borrower') }}{{ localeStore.t('signature') }}:</div>
          </div>
          <div class="signature-image" v-if="loan.signatureImage">
            <img :src="getSignatureImageUrl(loan.signatureImage)" :alt="localeStore.t('signature')" @error="handleImageError" />
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
  </div>
</template>

<style scoped>
.loan-contract-page {
  min-height: 100vh;
  background: #fff;
  padding-bottom: 80px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px;
  background: #fff;
  border-bottom: 1px solid #eee;
  position: sticky;
  top: 0;
  z-index: 100;
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

.contract-wrapper {
  padding: 20px 16px;
  min-height: calc(100vh - 60px);
}

.contract-box {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  color: #333;
  line-height: 1.6;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.05);
}

.contract-title {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 8px;
}

.contract-date {
  font-size: 14px;
  color: #2abf4b;
  margin-bottom: 8px;
}

.contract-subtitle {
  font-size: 14px;
  margin-bottom: 24px;
}

.contract-section {
  margin-bottom: 24px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 12px;
  color: #333;
}

.info-item {
  display: flex;
  margin-bottom: 8px;
}

.info-label {
  margin-right: 8px;
  color: #666;
}

.info-value {
  color: #333;
  font-weight: 500;
}

.info-value.highlight {
  color: #2abf4b;
}

.section-text {
  margin-bottom: 12px;
  color: #333;
}

.section-text .highlight {
  color: #2abf4b;
  font-weight: 500;
}

.section-note {
  margin-top: 8px;
  color: #666;
  font-size: 14px;
}

.contract-footer {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 16px;
  background: #fff;
  border-top: 1px solid #eee;
}

.sign-btn {
  width: 100%;
  padding: 14px;
  background: #2abf4b;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
}

.sign-btn.agreement-btn {
  cursor: default;
}

.signature-section {
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid #eee;
}

.signature-info {
  margin-bottom: 16px;
}

.signature-label {
  margin-bottom: 8px;
  color: #333;
  font-size: 14px;
}

.signature-image {
  margin: 16px 0;
  padding: 16px;
  background: #f9f9f9;
  border-radius: 8px;
  text-align: center;
}

.signature-image img {
  max-width: 100%;
  max-height: 200px;
  object-fit: contain;
}

.signature-date {
  margin-top: 16px;
  color: #666;
  font-size: 14px;
  text-align: right;
}
</style>

