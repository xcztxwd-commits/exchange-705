<script setup lang="ts">
import BusinessPage from '@/advanced/components/business/BusinessPage.vue'
import { readAssetResponse } from '@/advanced/utils/assetResponse'
const { request, error: advancedError, writing: advancedWriting } = useBusinessLifecycle()
import { ref, onMounted } from 'vue'
import { useTrialWallet } from '@/utils/useTrialWallet'
import { useRouter } from 'vue-router'
import TrialAccountCard from '@/components/TrialAccountCard.vue'
import { useBusinessLifecycle } from '@/advanced/components/business/useBusinessLifecycle'
import { useAuthStore } from '@/store/auth'
import { useLocaleStore, type MessageKeys } from '@/store/locale'

const router = useRouter()
const auth = useAuthStore()
const trialWallet = useTrialWallet()
auth.load()

const localeStore = useLocaleStore()
localeStore.loadLocale()

// 资产信息
const totalAssets = ref(0)
const fundBalance = ref(0)
const contractBalance = ref(0)
const optionBalance = ref(0)
const balanceVisible = ref(true)
const loading = ref(true)

// 格式化金额
function formatMoney(v: number) {
  return v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 切换余额显示/隐藏
function toggleBalance() {
  balanceVisible.value = !balanceVisible.value
}

// 加载资产信息
async function loadAssets() {
  loading.value = true
  advancedError.value = ''
  try {
    const owner = auth.token, started = performance.now()
    const res: any = await request.get('/user/assets')
    if (owner !== auth.token) return
    const assets = readAssetResponse(res, ['fundBalance', 'contractBalance', 'optionBalance'],
      localeStore.text('资产数据缺失或无效，请重试。', 'Asset data is missing or invalid. Please retry.'))
    trialWallet.accept(res, started)
    
    if (res && res.success !== false) {
      fundBalance.value = assets.fundBalance
      contractBalance.value = assets.contractBalance
      optionBalance.value = assets.optionBalance
      totalAssets.value = fundBalance.value + contractBalance.value + optionBalance.value
    }
  } catch (e: any) {
    advancedError.value = e.message || localeStore.t('loadAssetsFailed')
    console.error('加载资产信息失败:', e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadAssets()
})
</script>
<template>
<BusinessPage :title="localeStore.t('wallet')" :error="advancedError" :busy="advancedWriting">

<section class="card"><h2>{{ localeStore.text('可用余额 / USD','Available balance / USD') }}</h2><div class="row balance-line"><strong class="large">{{ loading || advancedError ? '—' : balanceVisible ? formatMoney(totalAssets) : '****' }}</strong><button class="text-button" @click="toggleBalance">{{ balanceVisible ? localeStore.text('隐藏','Hide') : localeStore.text('显示','Show') }}</button></div><div class="metrics"><div v-for="a in [{key:'fundAccountTitle',balance:fundBalance},{key:'contractAccountTitle',balance:contractBalance},{key:'optionAccountTitle',balance:optionBalance}]" :key="a.key" class="metric"><small>{{ localeStore.t(a.key as MessageKeys) }}</small><span>{{ loading || advancedError ? '—' : balanceVisible ? formatMoney(a.balance) : '****' }}</span></div></div></section>
<section class="card"><h2>{{ localeStore.text('收款方式','Payment methods') }}</h2><button class="row" @click="router.push('/wallet/bind-bank-card')"><span>{{ localeStore.t('bindBankCard') }}</span><span>{{ localeStore.text('管理','Manage') }} ›</span></button><p class="muted">{{ localeStore.text('收款人 · 账号 · 币种 · 银行信息','Recipient · Account · Currency · Bank') }}</p><hr class="divider" /><button class="row" @click="router.push('/wallet/bind-digital-currency')"><span>{{ localeStore.t('bindDigitalCurrencyAddress') }}</span><span>{{ localeStore.text('管理','Manage') }} ›</span></button><p class="muted">{{ localeStore.text('币种 · 网络 · 钱包地址','Currency · Network · Wallet address') }}</p></section><TrialAccountCard :visible="balanceVisible" /><p class="notice">{{ localeStore.text('体验金资产独立，绑定信息使用原有安全校验','Trial funds are separate; payment details retain existing security checks') }}</p><button v-if="advancedError" @click="advancedError='';loadAssets()">{{ localeStore.text('重试','Retry') }}</button>

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

.balance-line{padding:0!important}
</style>
