<script setup lang="ts">
import BusinessPage from '@/advanced/components/business/BusinessPage.vue'
import { readAssetResponse } from '@/advanced/utils/assetResponse'
const { request, error: advancedError, writing: advancedWriting } = useBusinessLifecycle()
import { ref, onMounted } from 'vue'
import { useTrialWallet } from '@/utils/useTrialWallet'
import TrialAccountCard from '@/components/TrialAccountCard.vue'
import CurrencyPicker from '@/advanced/components/business/CurrencyPicker.vue'
import { useFiatCurrency } from '@/utils/fiatCurrency'
const { currency, rate, formatAsset } = useFiatCurrency()
import { useRouter } from 'vue-router'
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
const fundFrozen = ref(0)
const contractBalance = ref(0)
const contractFrozen = ref(0)
const optionBalance = ref(0)
const optionFrozen = ref(0)
const balanceVisible = ref(true)
const loading = ref(true)

// 切换余额显示/隐藏
function toggleBalance() {
  balanceVisible.value = !balanceVisible.value
}

// 加载资产信息
async function loadAssets() {
  loading.value = true
  advancedError.value = ''
  try {
    const user = auth.user
    const userId = user?.id
    if (!userId) throw new Error(localeStore.text('账户信息不可用','Account information unavailable'))

    const owner = auth.token, started = performance.now()
    const res: any = await request.get('/user/assets')
    if (owner !== auth.token) return
    const assets = readAssetResponse(res, ['fundBalance', 'contractBalance', 'optionBalance', 'fundFrozen', 'contractFrozen', 'optionFrozen'],
      localeStore.text('资产数据缺失或无效，请重试。', 'Asset data is missing or invalid. Please retry.'))
    trialWallet.accept(res, started)
    
    if (res && res.success !== false) {
      // 资金账户
      fundBalance.value = assets.fundBalance
      fundFrozen.value = assets.fundFrozen
      
      // 合约账户
      contractBalance.value = assets.contractBalance
      contractFrozen.value = assets.contractFrozen
      
      // 期权账户
      optionBalance.value = assets.optionBalance
      optionFrozen.value = assets.optionFrozen
      
      // 计算总资产
      totalAssets.value = fundBalance.value + contractBalance.value + optionBalance.value + fundFrozen.value + contractFrozen.value + optionFrozen.value
    }
  } catch (e: any) {
    advancedError.value = e.message || localeStore.t('loadAssetsFailed')
    console.error(localeStore.t('loadAssetsFailed'), e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadAssets()
})

import depositIcon from '@/advanced/assets/business/deposit.svg'
import withdrawIcon from '@/advanced/assets/business/withdraw.svg'
import transferIcon from '@/advanced/assets/business/transfer.svg'
import walletIcon from '@/advanced/assets/business/wallet.svg'
const businessIcons:Record<string,string>={deposit:depositIcon,withdraw:withdrawIcon,transfer:transferIcon,wallet:walletIcon}
</script>
<template>
<BusinessPage :title="localeStore.t('myAssets')" :error="advancedError" :busy="advancedWriting">

<p class="muted">{{ localeStore.text('按可用资金与冻结资金汇总，不混淆净资产快照','Available and frozen funds, separate from net asset snapshots') }}</p>
<section class="card"><h2>{{ localeStore.t('totalAccountAssetsConverted') }}</h2><div class="row"><span>{{ localeStore.t('currency') }}</span><CurrencyPicker v-model="currency" /></div><p v-if="rate===null" role="alert">{{ localeStore.text('汇率暂不可用，请稍后重试','Exchange rate unavailable; please retry later') }}</p><div class="row balance-line"><strong class="large">{{ loading || advancedError ? '—' : balanceVisible ? formatAsset(totalAssets) : '****' }}</strong><button class="text-button" @click="toggleBalance">{{ balanceVisible ? localeStore.text('隐藏','Hide') : localeStore.text('显示','Show') }}</button></div><div class="metrics two"><div class="metric"><small>{{ localeStore.t('available') }}</small><span>{{ loading || advancedError ? '—' : balanceVisible ? formatAsset(fundBalance+contractBalance+optionBalance) : '****' }}</span></div><div class="metric"><small>{{ localeStore.t('frozen') }}</small><span>{{ loading || advancedError ? '—' : balanceVisible ? formatAsset(fundFrozen+contractFrozen+optionFrozen) : '****' }}</span></div></div></section>
<div class="fund-actions"><button v-for="a in [{path:'/deposit',key:'deposit',asset:'deposit'},{path:'/withdraw',key:'withdraw',asset:'withdraw'},{path:'/transfer',key:'transfer',asset:'transfer'},{path:'/wallet',key:'wallet',asset:'wallet'}]" :key="a.path" @click="router.push(a.path)"><span><img :src="businessIcons[a.asset]" alt="" width="24" height="24" /></span>{{ localeStore.t(a.key as MessageKeys) }}</button></div>
<section class="card"><h2>{{ localeStore.text('账户分布','Account allocation') }}</h2><template v-for="a in [{key:'fundAccountTitle',balance:fundBalance,frozen:fundFrozen},{key:'contractAccountTitle',balance:contractBalance,frozen:contractFrozen},{key:'optionAccountTitle',balance:optionBalance,frozen:optionFrozen}]" :key="a.key"><div class="row"><span>{{ localeStore.t(a.key as MessageKeys) }}</span><span>{{ currency }}</span></div><div class="metrics two"><div class="metric"><small>{{ localeStore.t('available') }}</small><span>{{ loading || advancedError ? '—' : balanceVisible ? formatAsset(a.balance) : '****' }}</span></div><div class="metric"><small>{{ localeStore.t('frozen') }}</small><span>{{ loading || advancedError ? '—' : balanceVisible ? formatAsset(a.frozen) : '****' }}</span></div></div><hr class="divider" /></template></section><TrialAccountCard :visible="balanceVisible" /><button v-if="advancedError" @click="advancedError='';loadAssets()">{{ localeStore.text('重试','Retry') }}</button>

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

.fund-actions{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px}.fund-actions button{display:flex;flex-direction:column;align-items:center;gap:8px;background:transparent;font-size:12px;padding:0}.fund-actions span{background:#f5f6f7;border-radius:22px;width:44px;height:44px;display:flex;align-items:center;justify-content:center}.balance-line{padding:0!important}
</style>
