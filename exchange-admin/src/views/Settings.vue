<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import { getAudioUrl } from '@/utils/audioUrl'

interface ConfigItem {
  key: string
  value: string
  description: string
}

const loading = ref(false)
const activeTab = ref('mail')
const uploadingSound = ref<string | null>(null)

const mailConfig = ref<ConfigItem[]>([
  { key: 'mail.host', value: '', description: 'SMTP服务器地址' },
  { key: 'mail.port', value: '', description: 'SMTP端口' },
  { key: 'mail.username', value: '', description: 'SMTP用户名' },
  { key: 'mail.password', value: '', description: 'SMTP密码' },
  { key: 'mail.from', value: '', description: '发件人邮箱' },
])

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
    const res: any = await request.get('/admin/config/list')
    if (Array.isArray(res)) {
      res.forEach((item: any) => {
        if (item.configKey === 'market.conversion.currencies') conversionCurrencies.value = [...new Set(['USD', ...String(item.configValue || '').split(',').filter(Boolean)])]
        if (item.configKey === 'market.conversion.cache-hours') conversionHours.value = Number(item.configValue) || 8
        const mailItem = mailConfig.value.find((c) => c.key === item.configKey)
        if (mailItem) {
          mailItem.value = item.configValue || ''
        }
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
          systemItem.value = item.configValue || 'Europe/London'
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
const testSound = (soundUrl: string) => {
  if (!soundUrl || soundUrl.trim() === '') {
    ElMessage.warning('请先上传提示音文件')
    return
  }
  try {
    // 使用工具函数获取完整的音频URL（生产环境需要完整URL）
    const fullAudioUrl = getAudioUrl(soundUrl)
    const audio = new Audio(fullAudioUrl)
    audio.volume = 0.7
    audio.play().catch(err => {
      // 忽略用户未交互的错误（浏览器安全策略）
      if (err.name !== 'NotAllowedError') {
        console.error('播放音频失败:', err, 'URL:', fullAudioUrl)
        ElMessage.error('播放失败: ' + err.message)
      } else {
        ElMessage.warning('请先点击页面任意位置后再试听')
      }
    })
  } catch (e: any) {
    console.error('创建音频对象失败:', e)
    ElMessage.error('播放失败: ' + e.message)
  }
}

// 清除提示音（关闭提示音）
const clearSound = (configKey: string) => {
  const soundItem = soundConfig.value.find((c) => c.key === configKey)
  if (soundItem) {
    soundItem.value = ''
    ElMessage.success('已清除提示音，该类型消息将不再播放提示音')
  }
}

const saveConfigs = async () => {
  conversionCurrencies.value = [...new Set(['USD', ...conversionCurrencies.value.map(code => code.trim().toUpperCase())])]
  if (conversionCurrencies.value.length > 30 || conversionCurrencies.value.some(code => !/^[A-Z]{3}$/.test(code))) {
    ElMessage.error('最多选择 30 种货币，请使用三位货币代码')
    return
  }
  if (!Number.isInteger(conversionHours.value) || conversionHours.value < 1 || conversionHours.value > 168) {
    ElMessage.error('汇率更新间隔请输入 1–168 的整数小时')
    return
  }
  loading.value = true
  try {
    // 过滤掉ws_url配置（前端会自动根据分类选择WebSocket地址）
    const allConfigs = [
      { key: 'market.conversion.currencies', value: conversionCurrencies.value.join(','), description: '预缓存币种（兑美元）' },
      { key: 'market.conversion.cache-hours', value: String(conversionHours.value), description: '结算汇率更新间隔（小时）' },
      ...mailConfig.value, 
      ...riskConfig.value, 
      ...marketConfig.value, 
      ...serviceConfig.value,
      ...soundConfig.value,
      ...domainConfig.value,
      ...systemConfig.value
    ].filter((c) => c.key !== 'market.alltick.ws_url' && c.key !== 'market.alltick.api_key') // 过滤已废弃的配置项
    
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
  <div class="settings-page">
    <el-card shadow="never">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="邮件配置" name="mail">
          <el-form label-width="150px">
            <el-form-item
              v-for="cfg in mailConfig"
              :key="cfg.key"
              :label="cfg.description"
            >
              <el-input
                v-model="cfg.value"
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
            <el-form-item label="缓存币种（兑美元）">
              <el-select v-model="conversionCurrencies" multiple filterable allow-create :multiple-limit="30" aria-label="缓存币种（兑美元）" style="width: min(720px, 100%)">
                <el-option v-for="[code, name] in currencyOptions" :key="code" :label="`${code} · ${name}`" :value="code" :disabled="code === 'USD'" />
              </el-select>
              <el-button link @click="conversionCurrencies = [...defaultConversionCurrencies]">恢复默认币种</el-button>
            </el-form-item>
            <p>美元为基准：1 单位所选币种 = 对应美元金额，USD 固定为 1。默认包含人民币和新加坡元，可搜索选择或输入三位货币代码。现有交易及充值所需汇率仍自动缓存；未被业务使用的取消币种停止预热，旧缓存到期失效。</p>
            <el-form-item label="汇率更新间隔（小时）">
              <el-input-number v-model="conversionHours" :min="1" :max="168" :step="1" :precision="0" aria-label="汇率更新间隔（小时）" />
            </el-form-item>
            <el-alert type="info" :closable="false" title="默认 8 小时，范围 1–168 小时。期间固定使用缓存汇率计算保证金和盈亏；到期后更新，更新失败时暂停相关结算。保存后按新时长判断是否到期。交易品种的实时价格仍单独校验。" />
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="时区设置" name="timezone">
          <el-form label-width="450px" label-position="left">
            <el-form-item
              v-for="cfg in systemConfig"
              :key="cfg.key"
              :label="cfg.description"
            >
              <el-input
                v-model="cfg.value"
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

        <el-tab-pane label="风控配置" name="risk">
          <el-form label-width="150px">
            <el-form-item
              v-for="cfg in riskConfig"
              :key="cfg.key"
              :label="cfg.description"
            >
              <el-input
                v-model="cfg.value"
                :placeholder="'请输入' + cfg.description"
                clearable
              />
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="行情配置" name="market">
          <el-form label-width="150px">
            <el-form-item
              v-for="cfg in marketConfig"
              :key="cfg.key"
              :label="cfg.description"
            >
              <el-input
                v-model="cfg.value"
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

        <el-tab-pane label="客服配置" name="service">
          <el-form label-width="150px">
            <el-form-item
              v-for="cfg in serviceConfig"
              :key="cfg.key"
              :label="cfg.description"
            >
              <el-input
                v-model="cfg.value"
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
                  <p>• <strong>客服链接</strong>：在线客服的URL地址，用户点击后会打开此链接。可以是完整的URL（如 https://example.com）或相对路径</p>
                  <p>• <strong>投诉邮箱</strong>：接收用户投诉的邮箱地址，用户可以在投诉邮箱页面复制此邮箱</p>
                  <p>• 配置保存后，用户端页面将自动显示相应的客服信息</p>
                </div>
              </template>
            </el-alert>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="域名检测" name="domain">
          <el-form label-width="200px">
            <el-form-item
              v-for="cfg in domainConfig"
              :key="cfg.key"
              :label="cfg.description"
            >
              <el-input
                v-model="cfg.value"
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
              :label="cfg.description"
            >
              <div style="display: flex; gap: 12px; align-items: center; width: 100%;">
                <el-input
                  v-model="cfg.value"
                  placeholder="提示音文件URL（上传后自动填充）"
                  clearable
                  style="flex: 1"
                  readonly
                />
                <el-upload
                  :http-request="(options: any) => handleSoundUpload(cfg.key, options.file)"
                  :show-file-list="false"
                  accept="audio/*"
                >
                  <el-button 
                    type="primary" 
                    :loading="uploadingSound === cfg.key"
                    size="default"
                  >
                    上传提示音
                  </el-button>
                </el-upload>
                <el-button 
                  type="success" 
                  @click="testSound(cfg.value)"
                  :disabled="!cfg.value || cfg.value.trim() === ''"
                >
                  试听
                </el-button>
                <el-button 
                  v-if="cfg.value && cfg.value.trim() !== ''"
                  type="danger" 
                  @click="clearSound(cfg.key)"
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
        <el-button type="primary" :loading="loading" @click="saveConfigs">
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
</style>
