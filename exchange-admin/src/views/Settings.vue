<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import request, { rawRequest } from '@/utils/request'
import { shareTemplateLanguages as videoLanguages } from '../../../exchange-frontend/src/utils/shareTemplateDesign'
import { useTenantPolicies } from '@/composables/useTenantPolicies'
import TenantPolicyNotice from '@/components/TenantPolicyNotice.vue'
import MarketDepthHealth from '@/components/MarketDepthHealth.vue'
import SupportChannelSettings from '@/components/SupportChannelSettings.vue'
import { can } from '@/utils/access'
const {snapshot,policyError,policyReady,reloadPolicies,editable,policyLabel}=useTenantPolicies('settings')
import { playProtectedAudio } from '@/utils/audioUrl'

interface ConfigItem {
  key: string
  value: string
  description: string
}

type RegistrationFieldPolicy = { enabled: boolean; required: boolean }
const registrationFields = ref<{ phone: RegistrationFieldPolicy; annualIncome: RegistrationFieldPolicy }>({
  phone: { enabled: true, required: false }, annualIncome: { enabled: true, required: false },
})
function setRegistrationEnabled(field: RegistrationFieldPolicy, enabled: boolean) {
  field.enabled = enabled
  if (!enabled) field.required = false
}

const loading = ref(false)
const activeTab = ref('mail')
const externalServiceConfigVisible = computed(() => snapshot.value?.features.external_support === true
  && snapshot.value.supportChannel !== 'internal'
  && !snapshot.value.configs.some(config => config.key === 'customer.service.link' && config.denied))
const supportChannelsVisible = computed(() => policyReady.value && can('support_settings:view'))
const serviceConfigVisible = computed(() => externalServiceConfigVisible.value || supportChannelsVisible.value)
watch([serviceConfigVisible, activeTab], ([visible, tab]) => {
  if (!visible && tab === 'service') activeTab.value = 'mail'
}, { flush: 'sync' })
const uploadingSound = ref<string | null>(null)

const mailConfig = ref<ConfigItem[]>([
  { key: 'mail.host', value: '', description: 'SMTP服务器地址' },
  { key: 'mail.port', value: '', description: 'SMTP端口' },
  { key: 'mail.username', value: '', description: 'SMTP用户名' },
  { key: 'mail.password', value: '', description: 'SMTP密码' },
  { key: 'mail.from', value: '', description: '发件人邮箱' },
])

const smsConfig = ref<ConfigItem[]>([
  { key: 'sms.provider', value: 'disabled', description: '短信供应商' },
  { key: 'sms.api_key', value: '', description: '供应商密钥（加密存储，未实现真实发送）' },
  { key: 'sms.template_code', value: '', description: '供应商模板（未验收，不用于登录或注册）' },
])
const smsStatus=ref<any>(null), smsBusy=ref(false), smsError=ref(''), smsRequest=ref(''), smsCode=ref('')
const smsRecipient=ref('+12025550100')
async function checkSms(){smsError.value='';try{smsStatus.value=await request.get('/admin/config/sms/status')}catch(e:any){smsError.value=e.message||'短信配置状态不可用'}}
async function testSms(){if(smsBusy.value)return;smsBusy.value=true;smsError.value='';smsRequest.value='';smsCode.value='';try{await checkSms();const r:any=await request.post('/admin/config/sms/test',{recipient:smsRecipient.value,purpose:'CONFIG_TEST'});smsRequest.value=r.requestId;ElMessage.success('仅写入受限本地 sink；未向真实用户发送短信')}catch(e:any){smsError.value=e.message||'本地短信契约未就绪'}finally{smsBusy.value=false}}
async function consumeSms(){if(smsBusy.value||!smsRequest.value)return;smsBusy.value=true;smsError.value='';try{await request.post('/admin/config/sms/consume',{requestId:smsRequest.value,recipient:smsRecipient.value,purpose:'CONFIG_TEST',code:smsCode.value});smsRequest.value='';smsCode.value='';ElMessage.success('CONFIG_TEST 单次消费通过；不代表真实供应商已验收')}catch(e:any){smsError.value=e.message||'验证码未通过'}finally{smsBusy.value=false}}
const riskConfig = ref<ConfigItem[]>([
  { key: 'risk.withdraw.min', value: '', description: '最小提现金额' },
  { key: 'risk.withdraw.max', value: '', description: '最大提现金额' },
  { key: 'risk.withdraw.daily_limit', value: '', description: '每日提现限额' },
])

const marketConfig = ref<ConfigItem[]>([
  { key: 'market.quote.token', value: '', description: '行情接口 Token（Forex 行情）' },
  // 注意：API地址由后端统一管理，无需配置
  // API基础地址：由后端使用 Forex 行情接口统一配置
])

const advancedEntryEnabled = ref(true)
const videoIntroUrl = ref('')
const videoSettings = ref<{ defaultLocale: string; videos: Record<string, string> }>({ defaultLocale: 'en', videos: {} })
const videoConfigured = ref(false), videoLanguage = ref('en'), uploadingVideo = ref(false), videoProgress = ref(0)
const previewingVideo = ref(false), videoPreviewOpen = ref(false), videoPreviewUrl = ref('')
const currentVideo = computed(() => videoSettings.value.videos[videoLanguage.value] || '')
function clearVideoPreview() {
  if (videoPreviewUrl.value) URL.revokeObjectURL(videoPreviewUrl.value)
  videoPreviewUrl.value = ''
}
onBeforeUnmount(clearVideoPreview)
watch(videoLanguage, () => { videoPreviewOpen.value = false; clearVideoPreview() })
async function uploadVideo(file: File) {
  if (loading.value || uploadingVideo.value || !editable('home.video.settings') || !can('settings:save')) return
  if (!/\.(mp4|webm)$/i.test(file.name) || !['video/mp4', 'video/webm'].includes(file.type)) {
    ElMessage.error('仅支持 MP4 或 WebM 视频'); return
  }
  if (!file.size || file.size > 100 * 1024 * 1024) { ElMessage.error('视频不能为空，且大小不能超过100MB'); return }
  const language = videoLanguage.value
  uploadingVideo.value = true; videoProgress.value = 0
  try {
    const data = new FormData(); data.append('file', file)
    const result: any = await request.post('/admin/videos/upload', data, {
      timeout: 300000,
      onUploadProgress: event => { videoProgress.value = Math.min(99, Math.round(event.loaded * 100 / (event.total || file.size))) },
    })
    if (!result?.success || !result.url) throw new Error(result?.message || '上传失败')
    videoSettings.value.videos[language] = result.url
    videoConfigured.value = true; videoProgress.value = 100
    videoPreviewOpen.value = false; clearVideoPreview()
    ElMessage.success('视频上传成功，点击「保存配置」后生效')
  } catch (e: any) { ElMessage.error(e.message || '视频上传失败') }
  finally { uploadingVideo.value = false }
}
function removeVideo() {
  if (!editable('home.video.settings') || uploadingVideo.value) return
  delete videoSettings.value.videos[videoLanguage.value]
  videoConfigured.value = true; videoPreviewOpen.value = false; clearVideoPreview()
}
async function previewVideo() {
  if (!currentVideo.value || previewingVideo.value) return
  const url = currentVideo.value
  previewingVideo.value = true
  try {
    const response = await rawRequest.get(url, { responseType: 'blob', timeout: 300000 })
    if (url !== currentVideo.value) return
    clearVideoPreview(); videoPreviewUrl.value = URL.createObjectURL(response.data); videoPreviewOpen.value = true
  } catch (e: any) { ElMessage.error(e.message || '视频预览失败') }
  finally { previewingVideo.value = false }
}
const tradeKycRequired = ref(true)
const conversionHours = ref(8)
const defaultConversionCurrencies = ['USD', 'EUR', 'JPY', 'GBP', 'CNY', 'CHF', 'AUD', 'CAD', 'HKD', 'SGD']
const conversionCurrencies = ref([...defaultConversionCurrencies])
const currencyOptions = [
  ['USD', '美元'], ['EUR', '欧元'], ['JPY', '日元'], ['GBP', '英镑'], ['CNY', '人民币'],
  ['CHF', '瑞士法郎'], ['AUD', '澳元'], ['CAD', '加元'], ['HKD', '港元'], ['SGD', '新加坡元'],
  ['NZD', '新西兰元'], ['SEK', '瑞典克朗'], ['NOK', '挪威克朗'], ['DKK', '丹麦克朗'],
  ['KRW', '韩元'], ['INR', '印度卢比'], ['MYR', '马来西亚林吉特'], ['THB', '泰铢'],
  ['IDR', '印尼盾'], ['TWD', '新台币'], ['AED', '阿联酋迪拉姆'], ['SAR', '沙特里亚尔'],
  ['MXN', '墨西哥比索'], ['BRL', '巴西雷亚尔'], ['ZAR', '南非兰特']
]

const serviceConfig = ref<ConfigItem[]>([
  { key: 'customer.service.link', value: '', description: '客服链接（在线客服URL）' },
  { key: 'complaint.email', value: '', description: '投诉邮箱' },
])

const soundConfig = ref<ConfigItem[]>([
  { key: 'notification.sound.withdraw', value: '', description: '提现提示音' },
  { key: 'notification.sound.deposit', value: '', description: '充值提示音' },
  { key: 'notification.sound.kyc', value: '', description: '实名提示音' },
  { key: 'notification.sound.order', value: '', description: '订单提示音' },
])

const domainConfig = ref<ConfigItem[]>([
  { key: 'domain.whitelist', value: '', description: '域名白名单（每行一个域名，支持通配符如 *.example.com）' },
])

const systemConfig = ref<ConfigItem[]>([
  { key: 'site.name', value: '', description: '平台名称' },
  { key: 'system.timezone', value: 'Europe/London', description: '系统 K 线图时区（如 Europe/London 或 Asia/Shanghai）' },
])

// 实时更新开关
const realtimeUpdate = ref({
  enabled: true,
  messageInterval: 5000,
  orderInterval: 5000
})

const loadConfigs = async () => {
  loading.value = true
  try {
    await reloadPolicies()
    const res: any = await request.get('/admin/config/list')
    const registrationConfig: any = await request.get('/admin/config/get', { params: { key: 'registration.fields' } })
    if (registrationConfig?.value) {
      const value = JSON.parse(registrationConfig.value)
      if (typeof value?.phone?.enabled !== 'boolean' || typeof value?.phone?.required !== 'boolean'
        || typeof value?.annualIncome?.enabled !== 'boolean' || typeof value?.annualIncome?.required !== 'boolean'
        || value.phone.required && !value.phone.enabled || value.annualIncome.required && !value.annualIncome.enabled)
        throw new Error('注册字段配置无效')
      registrationFields.value = value
    }
    if (Array.isArray(res)) {
      videoConfigured.value = false
      videoSettings.value = { defaultLocale: 'en', videos: {} }
      res.forEach((item: any) => {
        if (item.configKey === 'ui.advanced.enabled') advancedEntryEnabled.value = item.configValue !== 'false'
        if (item.configKey === 'home.video.url') videoIntroUrl.value = item.configValue || ''
        if (item.configKey === 'home.video.settings' && item.configValue) {
          videoSettings.value = JSON.parse(item.configValue)
          videoConfigured.value = true
        }
        if (item.configKey === 'trade.kyc.required') tradeKycRequired.value = item.configValue !== 'false'
        if (item.configKey === 'market.conversion.currencies') conversionCurrencies.value = [...new Set(['USD', ...String(item.configValue || '').split(',').filter(Boolean)])]
        if (item.configKey === 'market.conversion.cache-hours') conversionHours.value = Number(item.configValue) || 8
        const mailItem = mailConfig.value.find((c) => c.key === item.configKey)
        if (mailItem) {
          mailItem.value = item.configValue || ''
        }
        const smsItem = smsConfig.value.find((c) => c.key === item.configKey)
        if (smsItem) smsItem.value = item.configValue || ''
        const riskItem = riskConfig.value.find((c) => c.key === item.configKey)
        if (riskItem) {
          riskItem.value = item.configValue || ''
        }
        const marketItem = marketConfig.value.find((c) => c.key === item.configKey)
        if (marketItem) {
          marketItem.value = item.configValue || ''
        }
        const serviceItem = serviceConfig.value.find((c) => c.key === item.configKey)
        if (serviceItem) {
          serviceItem.value = item.configValue || ''
        }
        const soundItem = soundConfig.value.find((c) => c.key === item.configKey)
        if (soundItem) {
          soundItem.value = item.configValue || ''
        }
        const domainItem = domainConfig.value.find((c) => c.key === item.configKey)
        if (domainItem) {
          domainItem.value = item.configValue || ''
        }
        const systemItem = systemConfig.value.find((c) => c.key === item.configKey)
        if (systemItem) {
          systemItem.value = item.configValue || (item.configKey === 'system.timezone' ? 'Europe/London' : '')
        }
      })
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

// 上传提示音文件
const handleSoundUpload = async (configKey: string, file: File) => {
  if (!editable(configKey)) return
  uploadingSound.value = configKey
  try {
    const formData = new FormData()
    formData.append('file', file)

    // 检查文件类型
    if (!file.type.startsWith('audio/')) {
      ElMessage.error('只能上传音频文件')
      return
    }

    // 检查文件大小（5MB）
    if (file.size > 5 * 1024 * 1024) {
      ElMessage.error('文件大小不能超过5MB')
      return
    }

    const res: any = await request.post('/upload/audio', formData, {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    })

    if (res && res.success && res.url) {
      const soundItem = soundConfig.value.find((c) => c.key === configKey)
      if (soundItem) {
        soundItem.value = res.url
        ElMessage.success('上传成功')
      }
    } else {
      ElMessage.error(res?.message || '上传失败')
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '上传失败')
  } finally {
    uploadingSound.value = null
  }
}

// 测试播放提示音
const testSound = async (soundUrl: string) => {
  if (!soundUrl?.trim()) { ElMessage.warning('请先上传提示音文件'); return }
  try { await playProtectedAudio(soundUrl) }
  catch (error: any) { ElMessage.error(error.name === 'NotAllowedError' ? '请先点击页面任意位置后再试听' : (error.message || '音频不可用')) }
}

// 清除提示音（关闭提示音）
const clearSound = (configKey: string) => {
  if (!editable(configKey)) return
  const soundItem = soundConfig.value.find((c) => c.key === configKey)
  if (soundItem) {
    soundItem.value = ''
    ElMessage.success('已清除提示音，该类型消息将不再播放提示音')
  }
}

const saveConfigs = async () => {
  if (!policyReady.value || uploadingVideo.value) return
  if (videoConfigured.value && editable('home.video.settings') && Object.keys(videoSettings.value.videos).length
    && !videoSettings.value.videos[videoSettings.value.defaultLocale]) {
    ElMessage.error('请先为默认回退语言上传视频，或选择已上传的语言'); activeTab.value = 'video'; return
  }
  conversionCurrencies.value = [...new Set(['USD', ...conversionCurrencies.value.map(code => code.trim().toUpperCase())])]
  if (conversionCurrencies.value.length > 30 || conversionCurrencies.value.some(code => !/^[A-Z]{3}$/.test(code))) {
    ElMessage.error('最多选择 30 种货币，请使用三位货币代码')
    return
  }
  if (!Number.isInteger(conversionHours.value) || conversionHours.value < 1 || conversionHours.value > 168) {
    ElMessage.error('汇率更新间隔请输入 1–168 的整数小时')
    return
  }
  if (registrationFields.value.phone.required && !registrationFields.value.phone.enabled
    || registrationFields.value.annualIncome.required && !registrationFields.value.annualIncome.enabled) {
    ElMessage.error('请先开启字段，再设为必填')
    return
  }
  loading.value = true
  try {
    // 过滤掉ws_url配置（前端会自动根据分类选择WebSocket地址）
    const allConfigs = [
      { key: 'ui.advanced.enabled', value: String(advancedEntryEnabled.value), description: '高级版入口' },
      { key: 'home.video.url', value: videoIntroUrl.value.trim(), description: '视频简介地址' },
      ...(videoConfigured.value ? [{ key: 'home.video.settings', value: JSON.stringify(videoSettings.value), description: '宣传视频语言与默认回退配置' }] : []),
      { key: 'trade.kyc.required', value: String(tradeKycRequired.value), description: '未实名不可交易' },
      { key: 'registration.fields', value: JSON.stringify(registrationFields.value), description: '注册业务资料字段' },
      { key: 'market.conversion.currencies', value: conversionCurrencies.value.join(','), description: '预缓存币种（兑美元）' },
      { key: 'market.conversion.cache-hours', value: String(conversionHours.value), description: '结算汇率更新间隔（小时）' },
      ...mailConfig.value,
      ...smsConfig.value,
      ...riskConfig.value,
      ...marketConfig.value,
      ...(externalServiceConfigVisible.value ? serviceConfig.value : []),
      ...soundConfig.value,
      ...domainConfig.value,
      ...systemConfig.value
    ].filter((c) => editable(c.key) && c.key !== 'market.alltick.ws_url' && c.key !== 'market.alltick.api_key') // 过滤已废弃的配置项

    const payload = allConfigs.map((c) => ({
      key: c.key,
      value: c.value,
      description: c.description,
    }))

    await request.post('/admin/config/saveBatch', payload)
    ElMessage.success('配置保存成功')
  } catch (e: any) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadConfigs()
})
</script>

<template>
  <div class="settings-page" :class="{ 'depth-market-active': activeTab === 'market' }">
    <TenantPolicyNotice :snapshot="snapshot" :error="policyError"/><el-card shadow="never">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="邮件配置" name="mail">
          <el-form label-width="150px">
            <el-form-item
              v-for="cfg in mailConfig"
              :key="cfg.key"
              :label="cfg.description + policyLabel(cfg.key)"
            >
              <el-input
                v-model="cfg.value" :disabled="!editable(cfg.key)"
                :type="cfg.key.includes('password') ? 'password' : 'text'"
                :placeholder="'请输入' + cfg.description"
                clearable
                show-password
              />
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="结算汇率" name="conversion">
          <el-form label-width="180px">
            <el-form-item :label="'缓存币种（兑美元）' + policyLabel('market.conversion.currencies')">
              <el-select v-model="conversionCurrencies" :disabled="!editable('market.conversion.currencies')" multiple filterable allow-create :multiple-limit="30" :aria-label="'缓存币种（兑美元）' + policyLabel('market.conversion.currencies')" style="width: min(720px, 100%)">
                <el-option v-for="[code, name] in currencyOptions" :key="code" :label="`${code} · ${name}`" :value="code" :disabled="code === 'USD'" />
              </el-select>
              <el-button v-permission="'settings:save'" link :disabled="!editable('market.conversion.currencies')" @click="conversionCurrencies = [...defaultConversionCurrencies]">恢复默认币种</el-button>
            </el-form-item>
            <p>美元为基准：1 单位所选币种 = 对应美元金额，USD 固定为 1。默认包含人民币和新加坡元，可搜索选择或输入三位货币代码。现有交易及充值所需汇率仍自动缓存；未被业务使用的取消币种停止预热，旧缓存到期失效。</p>
            <el-form-item :label="'汇率更新间隔（小时）' + policyLabel('market.conversion.cache-hours')">
              <el-input-number v-model="conversionHours" :disabled="!editable('market.conversion.cache-hours')" :min="1" :max="168" :step="1" :precision="0" :aria-label="'汇率更新间隔（小时）' + policyLabel('market.conversion.cache-hours')" />
            </el-form-item>
            <el-alert type="info" :closable="false" title="法币充值/提现固定换汇缓存：默认 8 小时，范围 1–168 小时，保存后按原始时间戳判断到期。合约保证金、盈亏和权益使用独立的实时汇率，超过 60 秒或行情不可用时暂停相关计算，不使用此长周期缓存。" />
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="版本设置" name="edition">
          <el-form label-width="180px">
            <el-form-item :label="'高级版入口' + policyLabel('ui.advanced.enabled')">
              <el-switch v-permission="'settings:save'" v-model="advancedEntryEnabled"
                :disabled="loading || !editable('ui.advanced.enabled')" active-text="开启" inactive-text="关闭" />
            </el-form-item>
            <el-alert type="info" :closable="false" title="关闭后，经典版「我的」不再显示高级版入口；已进入高级版的用户仍可返回经典版。修改后请保存配置，用户重新进入「我的」时生效。" />
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="宣传视频" name="video">
          <el-form label-width="150px">
            <el-form-item :label="'默认回退语言' + policyLabel('home.video.settings')">
              <el-select v-model="videoSettings.defaultLocale" aria-label="默认回退语言"
                :disabled="loading || uploadingVideo || !editable('home.video.settings') || !can('settings:save')" style="width: 260px" @change="videoConfigured = true">
                <el-option v-for="[code, name] in videoLanguages" :key="code" :value="code"
                  :label="name + (videoSettings.videos[code] ? '（已上传）' : '（未上传）')" />
              </el-select>
            </el-form-item>
            <el-form-item label="上传语言">
              <el-select v-model="videoLanguage" aria-label="上传语言" :disabled="uploadingVideo" style="width: 260px">
                <el-option v-for="[code, name] in videoLanguages" :key="code" :value="code"
                  :label="name + (videoSettings.videos[code] ? '（已上传）' : '（未上传）')" />
              </el-select>
            </el-form-item>
            <el-form-item label="该语言视频">
              <div class="video-upload-controls">
                <el-input :model-value="currentVideo ? (currentVideo.endsWith('.webm') ? 'WebM 视频已上传' : 'MP4 视频已上传') : ''" readonly aria-label="该语言视频" placeholder="未上传，将使用默认回退语言的视频" />
                <el-upload v-permission="'settings:save'" :http-request="(options: any) => uploadVideo(options.file)"
                  :show-file-list="false" accept="video/mp4,video/webm,.mp4,.webm"
                  :disabled="loading || uploadingVideo || !editable('home.video.settings')">
                  <el-button type="primary" :loading="uploadingVideo" :disabled="loading || !editable('home.video.settings')">{{ currentVideo ? '替换视频' : '上传视频' }}</el-button>
                </el-upload>
                <el-button :loading="previewingVideo" :disabled="!currentVideo || uploadingVideo" @click="previewVideo">预览</el-button>
                <el-button v-permission="'settings:save'" type="danger" :disabled="!currentVideo || loading || uploadingVideo || !editable('home.video.settings')" @click="removeVideo">移除</el-button>
              </div>
              <el-progress v-if="uploadingVideo" :percentage="videoProgress" style="width: 100%; max-width: 720px; margin-top: 12px" />
            </el-form-item>
            <el-alert type="info" :closable="false" title="支持 MP4、WebM，单个视频最多100MB。按用户当前语言播放；该语言未上传时，使用默认回退语言。回退语言必须已上传视频。上传、替换和移除后点击「保存配置」生效。" />
            <el-form-item v-if="videoIntroUrl" :label="'原有通用视频地址' + policyLabel('home.video.url')" style="margin-top: 20px">
              <el-input v-model="videoIntroUrl" :disabled="loading || !editable('home.video.url')" aria-label="原有通用视频地址" clearable />
              <p>未设置多语言视频时沿用此地址；设置后使用上方语言配置。</p>
            </el-form-item>
          </el-form>
          <el-dialog v-model="videoPreviewOpen" title="宣传视频预览" width="min(840px, 92vw)" destroy-on-close @closed="clearVideoPreview">
            <video v-if="videoPreviewUrl" :src="videoPreviewUrl" controls playsinline preload="metadata" style="width: 100%; max-height: 70vh; background: #000" />
          </el-dialog>
        </el-tab-pane>
        <el-tab-pane label="平台名称/时区设置" name="timezone">
          <el-form label-width="450px" label-position="left">
            <el-form-item
              v-for="cfg in systemConfig"
              :key="cfg.key"
              :label="cfg.description + policyLabel(cfg.key)"
            >
              <el-input
                v-model="cfg.value" :disabled="!editable(cfg.key)"
                :placeholder="'请输入' + cfg.description"
                clearable
                style="width: 300px;"
              />
            </el-form-item>
            <el-alert
              type="info"
              :closable="false"
              style="margin-top: 20px"
            >
              <template #title>
                <div style="line-height: 1.6">
                  <p><strong>时区配置说明：</strong></p>
                  <p>• 此配置控制前台 K 线图表和部分时间显示所使用的时区。</p>
                  <p>• 常用时区：<strong>Europe/London</strong> (英国伦敦), <strong>Asia/Shanghai</strong> (中国北京), <strong>America/New_York</strong> (美国纽约)。</p>
                  <p>• 请确保输入的是标准 IANA 时区标识符。</p>
                </div>
              </template>
            </el-alert>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="注册字段" name="registration">
          <el-alert type="info" :closable="false" title="邮箱、密码、图形验证码和租户校验始终保留；手机号和年收入默认开启、选填。" />
          <el-form label-width="140px" style="margin-top: 18px">
            <el-form-item v-for="(field, name) in registrationFields" :key="name"
              :label="(name === 'phone' ? '手机号' : '年收入') + policyLabel('registration.fields')">
              <el-switch v-permission="'settings:save'" :model-value="field.enabled" :disabled="loading || !editable('registration.fields')"
                active-text="开启" inactive-text="关闭" @update:model-value="setRegistrationEnabled(field, $event)" />
              <el-checkbox v-model="field.required" style="margin-left: 22px"
                :disabled="loading || !field.enabled || !editable('registration.fields')">注册必填</el-checkbox>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="风控配置" name="risk">
          <el-form label-width="200px">
            <el-form-item :label="'未实名不可交易' + policyLabel('trade.kyc.required')">
              <el-switch v-permission="'settings:save'" v-model="tradeKycRequired" :disabled="loading || !editable('trade.kyc.required')" active-text="开启" inactive-text="关闭" />
            </el-form-item>
            <el-alert type="info" :closable="false" title="开启后，真实账户必须通过实名审核才能执行交易操作，体验金不豁免；未实名将跳转实名页并弹窗提示。关闭仅取消交易实名门槛，不影响提现、贷款实名要求。保存后生效。" />
          </el-form>
          <el-form label-width="150px">
            <el-form-item
              v-for="cfg in riskConfig"
              :key="cfg.key"
              :label="cfg.description + policyLabel(cfg.key)"
            >
              <el-input
                v-model="cfg.value" :disabled="!editable(cfg.key)"
                :placeholder="'请输入' + cfg.description"
                clearable
              />
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="行情配置" name="market">
          <MarketDepthHealth v-if="activeTab === 'market'" :can-edit="editable('market.depth.enabled')" />
          <el-form label-width="150px">
            <el-form-item
              v-for="cfg in marketConfig"
              :key="cfg.key"
              :label="cfg.description + policyLabel(cfg.key)"
            >
              <el-input
                v-model="cfg.value" :disabled="!editable(cfg.key)"
                :type="cfg.key.includes('appcode') || cfg.key.includes('api_key') ? 'password' : 'text'"
                :placeholder="'请输入' + cfg.description"
                clearable
                show-password
              />
            </el-form-item>
            <el-alert
              type="info"
              :closable="false"
              style="margin-top: 20px"
            >
              <template #title>
                <div style="line-height: 1.6">
                  <p><strong>行情配置说明：</strong></p>
                  <p>• 当前系统已使用 <strong>Forex 行情接口</strong> 获取K线和最新价格，不再使用阿里云或 Alltick 行情。</p>
                  <p>• <strong>行情接口 Token</strong>：请在此填写 Forex 行情服务提供的 token，后端会自动使用该 token 调用行情接口。</p>
                  <p>• <strong>API基础地址</strong>：由后端在配置文件中统一维护，前端和管理端无需配置。</p>
                  <p>• 修改并保存后，新 token 将立即生效，影响前端所有价格和K线请求。</p>
                </div>
              </template>
            </el-alert>
          </el-form>
        </el-tab-pane>

        <el-tab-pane v-if="serviceConfigVisible" label="客服配置" name="service">
          <SupportChannelSettings v-if="supportChannelsVisible && activeTab === 'service'" />
          <el-alert type="info" :closable="false" title="服务渠道使用模块内的按钮独立保存；欢迎语、离线回复与客服提示音仍在「客服与消息设置」配置。" style="margin-bottom: 20px" />
          <el-form v-if="externalServiceConfigVisible" class="external-service-config" label-width="150px">
            <el-form-item
              v-for="cfg in serviceConfig"
              :key="cfg.key"
              :label="cfg.description + policyLabel(cfg.key)"
            >
              <el-input
                v-model="cfg.value" :disabled="!editable(cfg.key)"
                :type="cfg.key.includes('email') ? 'email' : 'text'"
                :placeholder="'请输入' + cfg.description"
                clearable
              />
            </el-form-item>
            <el-alert
              type="info"
              :closable="false"
              style="margin-top: 20px"
            >
              <template #title>
                <div style="line-height: 1.6">
                  <p><strong>客服配置说明：</strong></p>
                  <p>• <strong>客服链接</strong>：支持 HTTPS 公网地址，无需加入域名白名单；不接受无协议地址、相对路径、内网地址或含凭据的 URL</p>
                  <p>• <strong>投诉邮箱</strong>：接收用户投诉的邮箱地址，用户可以在投诉邮箱页面复制此邮箱</p>
                  <p>• 配置保存后，用户端页面将自动显示相应的客服信息</p>
                </div>
              </template>
            </el-alert>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="短信契约 / 未正式发送" name="sms">
          <el-alert type="warning" :closable="false" title="真实供应商未指定，正式发送 blocked。仅 CONFIG_TEST 本地 sink，不用于注册、登录或真实用户；须运维显式启用并预建受限目录。"/>
          <el-form label-width="180px"><el-form-item label="供应商"><el-select v-model="smsConfig[0]!.value" :disabled="!editable('sms.provider')"><el-option value="disabled" label="关闭"/><el-option value="local-sink" label="本地测试 sink（不外发）"/><el-option value="external-blocked" label="真实供应商待指定 / blocked"/></el-select></el-form-item><el-form-item v-for="cfg in smsConfig.slice(1)" :key="cfg.key" :label="cfg.description+policyLabel(cfg.key)"><el-input v-model="cfg.value" :disabled="!editable(cfg.key)" :type="cfg.key==='sms.api_key'?'password':'text'" autocomplete="off"/></el-form-item></el-form>
          <p>先保存配置，再查询实际服务端状态。API 返回值和审计均不含验证码或完整号码；仅运维可读受限 sink。</p>
          <el-button v-permission="'settings:view'" @click="checkSms">核查服务端状态</el-button><p v-if="smsStatus">{{smsStatus.status}}；真实供应商已验收：{{smsStatus.realSupplierVerified?'是':'否'}}</p><el-alert v-if="smsError" :title="smsError" type="error" :closable="false"/>
          <el-form label-width="180px"><el-form-item label="合成测试号码"><el-input v-model="smsRecipient" maxlength="12" placeholder="+12025550100 至 +12025550199"/></el-form-item><el-button v-permission="'settings:save'" :loading="smsBusy" @click="testSms">仅发送到本地 sink</el-button><template v-if="smsRequest"><p>请求编号 {{smsRequest}}；180 秒过期，单次消费，不外发。</p><el-form-item label="sink 验证码"><el-input v-model="smsCode" type="password" maxlength="6" autocomplete="off"/></el-form-item><el-button v-permission="'settings:save'" :loading="smsBusy" @click="consumeSms">消费 CONFIG_TEST</el-button></template></el-form>
        </el-tab-pane>

        <el-tab-pane label="域名检测" name="domain">
          <el-form label-width="200px">
            <el-form-item
              v-for="cfg in domainConfig"
              :key="cfg.key"
              :label="cfg.description + policyLabel(cfg.key)"
            >
              <el-input
                v-model="cfg.value" :disabled="!editable(cfg.key)"
                type="textarea"
                :rows="10"
                placeholder="请输入域名白名单，每行一个域名&#10;例如：&#10;example.com&#10;www.example.com&#10;*.example.com"
                clearable
              />
            </el-form-item>
            <el-alert
              type="info"
              :closable="false"
              style="margin-top: 20px"
            >
              <template #title>
                <div style="line-height: 1.6">
                  <p><strong>域名检测说明：</strong></p>
                  <p>• 域名白名单用于判断用户是否为"真人"或"假人"</p>
                  <p>• 如果用户登录的域名在白名单中，标记为"真人"</p>
                  <p>• 如果用户登录的域名不在白名单中，标记为"假人"</p>
                  <p>• 支持通配符匹配，如 *.example.com 可以匹配所有 example.com 的子域名</p>
                  <p>• 每行一个域名，支持用逗号或换行符分隔</p>
                  <p>• 用户列表会显示"登录域名:xx 真人/假人"</p>
                </div>
              </template>
            </el-alert>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="提示音配置" name="sound">
          <el-form label-width="150px">
            <el-form-item
              v-for="cfg in soundConfig"
              :key="cfg.key"
              :label="cfg.description + policyLabel(cfg.key)"
            >
              <div style="display: flex; gap: 12px; align-items: center; width: 100%;">
                <el-input
                  v-model="cfg.value" :disabled="!editable(cfg.key)"
                  placeholder="提示音文件URL（上传后自动填充）"
                  clearable
                  style="flex: 1"
                  readonly
                />
                <el-upload v-permission="'settings:save'" :disabled="!editable(cfg.key)"
                  :http-request="(options: any) => handleSoundUpload(cfg.key, options.file)"
                  :show-file-list="false"
                  accept="audio/*"
                >
                  <el-button v-permission="'settings:save'"
                    type="primary"
                    :loading="uploadingSound === cfg.key" :disabled="!editable(cfg.key)"
                    size="default"
                  >
                    上传提示音
                  </el-button>
                </el-upload>
                <el-button v-permission="'settings:sound_preview'"
                  type="success"
                  @click="testSound(cfg.value)"
                  :disabled="!cfg.value || cfg.value.trim() === ''"
                >
                  试听
                </el-button>
                <el-button v-permission="'settings:save'"
                  v-if="cfg.value && cfg.value.trim() !== ''"
                  type="danger"
                  @click="clearSound(cfg.key)" :disabled="!editable(cfg.key)"
                  size="default"
                >
                  清除
                </el-button>
              </div>
            </el-form-item>
            <el-alert
              type="info"
              :closable="false"
              style="margin-top: 20px"
            >
              <template #title>
                <div style="line-height: 1.6">
                  <p><strong>提示音配置说明：</strong></p>
                  <p>• 支持上传音频文件（MP3、WAV等格式）</p>
                  <p>• 文件大小限制：5MB</p>
                  <p>• <strong>开启/关闭提示音：</strong>如果设置了提示音文件URL，表示开启该类型消息的提示音；如果未设置（清空），表示关闭该类型消息的提示音</p>
                  <p>• 当有新的待处理消息时，系统会自动播放对应已开启的提示音</p>
                  <p>• 提示音会在消息数量增加时自动播放</p>
                  <p>• 点击"清除"按钮可以关闭该类型消息的提示音</p>
                </div>
              </template>
            </el-alert>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="系统信息" name="sysinfo">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="系统名称">Exchange 交易所管理系统</el-descriptions-item>
            <el-descriptions-item label="系统版本">v1.0.0</el-descriptions-item>
            <el-descriptions-item label="后端框架">Spring Boot 2.7.18</el-descriptions-item>
            <el-descriptions-item label="前端框架">Vue 3 + Element Plus</el-descriptions-item>
            <el-descriptions-item label="UI组件">Element Plus</el-descriptions-item>
            <el-descriptions-item label="数据库">MySQL 8.0</el-descriptions-item>
            <el-descriptions-item label="Java版本">Java 1.8</el-descriptions-item>
            <el-descriptions-item label="授权方式">JWT Token</el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>
      </el-tabs>

      <div style="margin-top: 20px; text-align: center">
        <el-button v-permission="'settings:save'" type="primary" :loading="loading" :disabled="!policyReady || uploadingVideo" @click="saveConfigs">
          保存配置
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.settings-page {
  padding: 0;
}
.video-upload-controls { display: flex; flex-wrap: wrap; gap: 10px; width: 100%; max-width: 900px; }
.video-upload-controls .el-input { flex: 1 1 320px; }
.video-upload-controls .el-button + .el-button { margin-left: 0; }
@media (max-width: 768px) {
  /* Keep the depth panel reachable through the existing admin main-area scrollbar. */
  .settings-page.depth-market-active { min-width: 360px; }
}
</style>
