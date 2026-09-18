<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import Tabbar from '@/components/Tabbar.vue'
import request from '@/utils/request'
import { useAuthStore } from '@/store/auth'
import { getImageUrl } from '@/utils/imageUrl'
import { useLocaleStore } from '@/store/locale'

const router = useRouter()
const auth = useAuthStore()
auth.load()

// 多语言
const localeStore = useLocaleStore()
localeStore.loadLocale()

// 国家区号列表
const countryCodes = [
  { code: '+1', name: '美国/加拿大', flag: '🇺🇸' },
  { code: '+86', name: '中国', flag: '🇨🇳' },
  { code: '+852', name: '香港', flag: '🇭🇰' },
  { code: '+853', name: '澳门', flag: '🇲🇴' },
  { code: '+886', name: '台湾', flag: '🇹🇼' },
  { code: '+81', name: '日本', flag: '🇯🇵' },
  { code: '+82', name: '韩国', flag: '🇰🇷' },
  { code: '+65', name: '新加坡', flag: '🇸🇬' },
  { code: '+60', name: '马来西亚', flag: '🇲🇾' },
  { code: '+66', name: '泰国', flag: '🇹🇭' },
  { code: '+62', name: '印度尼西亚', flag: '🇮🇩' },
  { code: '+63', name: '菲律宾', flag: '🇵🇭' },
  { code: '+84', name: '越南', flag: '🇻🇳' },
  { code: '+44', name: '英国', flag: '🇬🇧' },
  { code: '+33', name: '法国', flag: '🇫🇷' },
  { code: '+49', name: '德国', flag: '🇩🇪' },
  { code: '+39', name: '意大利', flag: '🇮🇹' },
  { code: '+34', name: '西班牙', flag: '🇪🇸' },
  { code: '+31', name: '荷兰', flag: '🇳🇱' },
  { code: '+32', name: '比利时', flag: '🇧🇪' },
  { code: '+41', name: '瑞士', flag: '🇨🇭' },
  { code: '+46', name: '瑞典', flag: '🇸🇪' },
  { code: '+47', name: '挪威', flag: '🇳🇴' },
  { code: '+45', name: '丹麦', flag: '🇩🇰' },
  { code: '+358', name: '芬兰', flag: '🇫🇮' },
  { code: '+7', name: '俄罗斯', flag: '🇷🇺' },
  { code: '+91', name: '印度', flag: '🇮🇳' },
  { code: '+61', name: '澳大利亚', flag: '🇦🇺' },
  { code: '+64', name: '新西兰', flag: '🇳🇿' },
  { code: '+27', name: '南非', flag: '🇿🇦' },
  { code: '+55', name: '巴西', flag: '🇧🇷' },
  { code: '+52', name: '墨西哥', flag: '🇲🇽' },
  { code: '+971', name: '阿联酋', flag: '🇦🇪' },
  { code: '+966', name: '沙特阿拉伯', flag: '🇸🇦' },
  { code: '+974', name: '卡塔尔', flag: '🇶🇦' },
  { code: '+965', name: '科威特', flag: '🇰🇼' },
  { code: '+973', name: '巴林', flag: '🇧🇭' },
  { code: '+968', name: '阿曼', flag: '🇴🇲' },
  { code: '+961', name: '黎巴嫩', flag: '🇱🇧' },
  { code: '+962', name: '约旦', flag: '🇯🇴' },
  { code: '+972', name: '以色列', flag: '🇮🇱' },
  { code: '+90', name: '土耳其', flag: '🇹🇷' },
  { code: '+20', name: '埃及', flag: '🇪🇬' },
  { code: '+234', name: '尼日利亚', flag: '🇳🇬' },
  { code: '+254', name: '肯尼亚', flag: '🇰🇪' },
  { code: '+233', name: '加纳', flag: '🇬🇭' },
  { code: '+212', name: '摩洛哥', flag: '🇲🇦' },
  { code: '+351', name: '葡萄牙', flag: '🇵🇹' },
  { code: '+30', name: '希腊', flag: '🇬🇷' },
  { code: '+353', name: '爱尔兰', flag: '🇮🇪' },
  { code: '+48', name: '波兰', flag: '🇵🇱' },
  { code: '+420', name: '捷克', flag: '🇨🇿' },
  { code: '+36', name: '匈牙利', flag: '🇭🇺' },
  { code: '+40', name: '罗马尼亚', flag: '🇷🇴' },
  { code: '+380', name: '乌克兰', flag: '🇺🇦' },
  { code: '+375', name: '白俄罗斯', flag: '🇧🇾' },
  { code: '+370', name: '立陶宛', flag: '🇱🇹' },
  { code: '+371', name: '拉脱维亚', flag: '🇱🇻' },
  { code: '+372', name: '爱沙尼亚', flag: '🇪🇪' },
  { code: '+356', name: '马耳他', flag: '🇲🇹' },
  { code: '+357', name: '塞浦路斯', flag: '🇨🇾' },
  { code: '+385', name: '克罗地亚', flag: '🇭🇷' },
  { code: '+386', name: '斯洛文尼亚', flag: '🇸🇮' },
  { code: '+421', name: '斯洛伐克', flag: '🇸🇰' },
  { code: '+359', name: '保加利亚', flag: '🇧🇬' },
  { code: '+381', name: '塞尔维亚', flag: '🇷🇸' },
  { code: '+382', name: '黑山', flag: '🇲🇪' },
  { code: '+387', name: '波黑', flag: '🇧🇦' },
  { code: '+389', name: '北马其顿', flag: '🇲🇰' },
  { code: '+383', name: '科索沃', flag: '🇽🇰' },
  { code: '+355', name: '阿尔巴尼亚', flag: '🇦🇱' },
  { code: '+994', name: '阿塞拜疆', flag: '🇦🇿' },
  { code: '+374', name: '亚美尼亚', flag: '🇦🇲' },
  { code: '+995', name: '格鲁吉亚', flag: '🇬🇪' },
  { code: '+998', name: '乌兹别克斯坦', flag: '🇺🇿' },
  { code: '+7', name: '哈萨克斯坦', flag: '🇰🇿' },
  { code: '+996', name: '吉尔吉斯斯坦', flag: '🇰🇬' },
  { code: '+992', name: '塔吉克斯坦', flag: '🇹🇯' },
  { code: '+993', name: '土库曼斯坦', flag: '🇹🇲' },
  { code: '+850', name: '朝鲜', flag: '🇰🇵' },
  { code: '+880', name: '孟加拉国', flag: '🇧🇩' },
  { code: '+92', name: '巴基斯坦', flag: '🇵🇰' },
  { code: '+93', name: '阿富汗', flag: '🇦🇫' },
  { code: '+94', name: '斯里兰卡', flag: '🇱🇰' },
  { code: '+977', name: '尼泊尔', flag: '🇳🇵' },
  { code: '+975', name: '不丹', flag: '🇧🇹' },
  { code: '+960', name: '马尔代夫', flag: '🇲🇻' },
  { code: '+673', name: '文莱', flag: '🇧🇳' },
  { code: '+856', name: '老挝', flag: '🇱🇦' },
  { code: '+855', name: '柬埔寨', flag: '🇰🇭' },
  { code: '+670', name: '东帝汶', flag: '🇹🇱' }
]

const selectedCountryCode = ref('+1') // 默认区号为+1
const showCountryCodeSelector = ref(false)
const countryCodeSearch = ref('')

const formData = ref({
  realName: '',
  idNumber: '',
  phone: '',
  address: ''
})

// 获取选中的国家标志
function getSelectedCountryFlag() {
  const country = countryCodes.find(c => c.code === selectedCountryCode.value)
  return country?.flag || '🇺🇸'
}

// 过滤国家代码列表
const filteredCountryCodes = computed(() => {
  if (!countryCodeSearch.value.trim()) {
    return countryCodes
  }
  const search = countryCodeSearch.value.toLowerCase()
  return countryCodes.filter(country => 
    country.name.toLowerCase().includes(search) ||
    country.code.includes(search)
  )
})

// 选择国家代码
function selectCountryCode(code: string) {
  selectedCountryCode.value = code
  showCountryCodeSelector.value = false
  countryCodeSearch.value = ''
}

// 处理点击外部关闭下拉框
function handleClickOutside(event: Event) {
  const target = event.target as HTMLElement
  const dropdown = document.querySelector('.country-code-dropdown')
  const selector = document.querySelector('.country-code-selector')
  
  if (dropdown && selector && 
      !dropdown.contains(target) && 
      !selector.contains(target)) {
    showCountryCodeSelector.value = false
    countryCodeSearch.value = ''
  }
}



// 图片上传
const frontImageFile = ref<File | null>(null)
const frontImagePreview = ref<string>('')
const backImageFile = ref<File | null>(null)
const backImagePreview = ref<string>('')
const handheldImageFile = ref<File | null>(null)
const handheldImagePreview = ref<string>('')
const frontImageInputRef = ref<HTMLInputElement | null>(null)
const backImageInputRef = ref<HTMLInputElement | null>(null)
const handheldImageInputRef = ref<HTMLInputElement | null>(null)

const loading = ref(false)
const submitting = ref(false)
const verified = ref(false)

// Toast提示
const toastMessage = ref('')
const toastType = ref<'success' | 'error' | ''>('')

function showToast(message: string, type: 'success' | 'error' = 'error') {
  toastMessage.value = message
  toastType.value = type
  setTimeout(() => {
    toastMessage.value = ''
    toastType.value = ''
  }, 3000)
}

// 加载实名认证信息（如果已通过审核，自动填入）
async function loadKycInfo() {
  loading.value = true
  try {
    // 先获取实名认证信息（如果已通过审核）
    const kycRes: any = await request.get('/loan/personal-info/kyc-info')
    if (kycRes && kycRes.success && kycRes.data) {
      const info = kycRes.data
      if (info.realName) formData.value.realName = info.realName
      if (info.idNumber) formData.value.idNumber = info.idNumber
    }
    
    // 再获取贷款个人信息（用于填充电话等）
    const res: any = await request.get('/loan/kyc-info')
    if (res && res.success && res.data) {
      const info = res.data
      if (info.phone) {
        // 解析电话号码，提取区号和号码
        const phone = info.phone
        // 尝试匹配区号（以+开头）
        const match = phone.match(/^(\+\d{1,4})(.+)$/)
        if (match) {
          selectedCountryCode.value = match[1]
          formData.value.phone = match[2]
        } else {
          formData.value.phone = phone
        }
      }
    }
  } catch (e) {
    console.error('加载实名认证信息失败:', e)
  } finally {
    loading.value = false
  }
}

// 选择正面图片
function handleFrontImageSelect(e: Event) {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return
  
  // 检查文件类型
  if (!file.type.startsWith('image/')) {
    showToast(localeStore.t('onlyImageFiles'), 'error')
    return
  }
  
  // 检查文件大小（5MB）
  if (file.size > 5 * 1024 * 1024) {
    showToast(localeStore.t('imageSizeLimit'), 'error')
    return
  }
  
  frontImageFile.value = file
  
  // 创建预览
  const reader = new FileReader()
  reader.onload = (e) => {
    frontImagePreview.value = e.target?.result as string
  }
  reader.readAsDataURL(file)
}

// 选择反面图片
function handleBackImageSelect(e: Event) {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return
  
  // 检查文件类型
  if (!file.type.startsWith('image/')) {
    showToast(localeStore.t('onlyImageFiles'), 'error')
    return
  }
  
  // 检查文件大小（5MB）
  if (file.size > 5 * 1024 * 1024) {
    showToast(localeStore.t('imageSizeLimit'), 'error')
    return
  }
  
  backImageFile.value = file
  
  // 创建预览
  const reader = new FileReader()
  reader.onload = (e) => {
    backImagePreview.value = e.target?.result as string
  }
  reader.readAsDataURL(file)
}

// 触发文件选择
function triggerFrontImageSelect() {
  frontImageInputRef.value?.click()
}

function triggerBackImageSelect() {
  backImageInputRef.value?.click()
}

// 选择手持图片
function handleHandheldImageSelect(e: Event) {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return
  
  // 检查文件类型
  if (!file.type.startsWith('image/')) {
    showToast(localeStore.t('onlyImageFiles'), 'error')
    return
  }
  
  // 检查文件大小（5MB）
  if (file.size > 5 * 1024 * 1024) {
    showToast(localeStore.t('imageSizeLimit'), 'error')
    return
  }
  
  handheldImageFile.value = file
  
  // 创建预览
  const reader = new FileReader()
  reader.onload = (e) => {
    handheldImagePreview.value = e.target?.result as string
  }
  reader.readAsDataURL(file)
}

// 触发手持图片选择
function triggerHandheldImageSelect() {
  handheldImageInputRef.value?.click()
}

// getImageUrl 函数已从 @/utils/imageUrl 导入

// 检查是否已填写个人信息
async function checkPersonalInfo() {
  try {
    const res: any = await request.get('/loan/personal-info/status')
    if (res && res.success) {
      verified.value = res.verified || false
      if (res.data) {
        formData.value = {
          realName: res.data.realName || '',
          idNumber: res.data.idNumber || '',
          phone: '',
          address: res.data.address || ''
        }
        // 解析电话号码
        if (res.data.phone) {
          const phone = res.data.phone
          const match = phone.match(/^(\+\d{1,4})(.+)$/)
          if (match) {
            selectedCountryCode.value = match[1]
            formData.value.phone = match[2]
          } else {
            formData.value.phone = phone
          }
        }
        // 加载已上传的图片（如果已审核通过，显示预览）
        if (res.data.idFrontImage) {
          frontImagePreview.value = getImageUrl(res.data.idFrontImage)
        }
        if (res.data.idBackImage) {
          backImagePreview.value = getImageUrl(res.data.idBackImage)
        }
        if (res.data.handheldImage) {
          handheldImagePreview.value = getImageUrl(res.data.handheldImage)
        }
      }
    }
  } catch (e) {
    console.error('检查个人信息状态失败:', e)
  }
}

// 提交个人信息
async function submitPersonalInfo() {
  if (!formData.value.realName || !formData.value.realName.trim()) {
    showToast(localeStore.t('enterRealName'), 'error')
    return
  }

  if (!formData.value.idNumber || !formData.value.idNumber.trim()) {
    showToast(localeStore.t('enterIdNumber'), 'error')
    return
  }

  if (!formData.value.phone || !formData.value.phone.trim()) {
    showToast(localeStore.t('enterPhoneNumber'), 'error')
    return
  }

  if (!formData.value.address || !formData.value.address.trim()) {
    showToast(localeStore.t('enterHomeAddress'), 'error')
    return
  }

  if (!frontImageFile.value) {
    showToast(localeStore.t('uploadIdFront'), 'error')
    return
  }

  if (!backImageFile.value) {
    showToast(localeStore.t('uploadIdBack'), 'error')
    return
  }

  if (!handheldImageFile.value) {
    showToast(localeStore.t('uploadHandheldId'), 'error')
    return
  }

  submitting.value = true
  try {
    // 先上传图片
    const uploadImage = async (file: File) => {
      const formData = new FormData()
      formData.append('file', file)
      const uploadRes: any = await request.post('/upload/image', formData, {
        headers: { 'Content-Type': 'multipart/form-data' }
      })
      const url = uploadRes.url || uploadRes.data?.url
      if (!url) throw new Error(localeStore.t('imageUploadFailed') || 'Image upload failed')
      return url
    }

    let frontUrl = ''
    let backUrl = ''
    let handheldUrl = ''
    
    try {
      [frontUrl, backUrl, handheldUrl] = await Promise.all([
        uploadImage(frontImageFile.value),
        uploadImage(backImageFile.value),
        uploadImage(handheldImageFile.value)
      ])
    } catch (e: any) {
      showToast(e.message || localeStore.t('imageUploadFailed') || 'Image upload failed', 'error')
      submitting.value = false
      return
    }

    // 创建 FormData，将区号和手机号组合
    const fullPhone = `${selectedCountryCode.value}${formData.value.phone.trim()}`
    const formDataToSend = new FormData()
    formDataToSend.append('realName', formData.value.realName.trim())
    formDataToSend.append('idNumber', formData.value.idNumber.trim())
    formDataToSend.append('phone', fullPhone)
    formDataToSend.append('address', formData.value.address.trim())
    formDataToSend.append('idFrontImage', frontUrl)
    formDataToSend.append('idBackImage', backUrl)
    formDataToSend.append('handheldImage', handheldUrl)
    
    const res: any = await request.post('/loan/personal-info/submit', formDataToSend, {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    })

    if (res && res.success) {
      showToast(localeStore.t('submitSuccessWaitReview'), 'success')
      setTimeout(() => {
        checkPersonalInfo()
      }, 1500)
    } else {
      showToast(res.message || localeStore.t('applicationFailed'), 'error')
    }
  } catch (e: any) {
      showToast(e.response?.data?.message || e.message || localeStore.t('applicationFailed'), 'error')
    console.error('提交个人信息失败:', e)
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadKycInfo()
  checkPersonalInfo()
  document.addEventListener('click', handleClickOutside)
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
})
</script>

<template>
  <div class="loan-personal-info-page">
    <div class="page-header">
      <div class="back-button" @click="router.back()">
        <svg t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
          <path d="M768 903.232l-50.432 56.768L256 512l461.568-448 50.432 56.768L364.928 512z" fill="#333"></path>
        </svg>
      </div>
      <div class="header-title">{{ localeStore.t('loanPersonalInfo') }}</div>
      <div class="header-placeholder"></div>
    </div>

    <div class="page-content">
      <div v-if="verified" class="verified-notice">
        <div class="notice-icon">✓</div>
        <div class="notice-text">{{ localeStore.t('personalInfoApproved') }}</div>
      </div>

      <div class="form-section">
        <div class="form-label">{{ localeStore.t('realName') }} <span class="required">*</span></div>
        <input
          v-model="formData.realName"
          type="text"
          class="form-input"
          :placeholder="localeStore.t('enterRealName')"
          :disabled="verified"
        />
      </div>

      <div class="form-section">
        <div class="form-label">{{ localeStore.t('idNumber') }} <span class="required">*</span></div>
        <input
          v-model="formData.idNumber"
          type="text"
          class="form-input"
          :placeholder="localeStore.t('enterIdNumber')"
          :disabled="verified"
        />
      </div>

      <div class="form-section">
        <div class="form-label">{{ localeStore.t('phoneNumber') }} <span class="required">*</span></div>
        <div class="phone-input-wrapper">
          <div 
            class="country-code-selector" 
            @click="!verified && (showCountryCodeSelector = !showCountryCodeSelector)"
            :class="{ disabled: verified }"
          >
            <span class="country-code-flag">{{ getSelectedCountryFlag() }}</span>
            <span class="country-code-value">{{ selectedCountryCode }}</span>
            <svg class="dropdown-arrow" :class="{ 'rotated': showCountryCodeSelector }" width="12" height="12" viewBox="0 0 12 12" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M3 4.5L6 7.5L9 4.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>
          <input
            v-model="formData.phone"
            type="tel"
            class="form-input phone-input"
            :placeholder="localeStore.t('enterPhoneNumber')"
            :disabled="verified"
          />
          <div v-if="showCountryCodeSelector" class="country-code-dropdown" @click.stop>
            <div class="dropdown-search">
          <input
            v-model="countryCodeSearch" 
            type="text" 
            :placeholder="localeStore.t('searchCountryOrCode')"
            class="search-input"
          />
            </div>
            <div class="dropdown-list">
              <div
                v-for="country in filteredCountryCodes"
                :key="country.code"
                class="dropdown-item"
                :class="{ active: selectedCountryCode === country.code }"
                @click="selectCountryCode(country.code)"
              >
                <span class="item-flag">{{ country.flag }}</span>
                <span class="item-code">{{ country.code }}</span>
                <span class="item-name">{{ country.name }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="form-section">
        <div class="form-label">{{ localeStore.t('homeAddress') }} <span class="required">*</span></div>
        <textarea
          v-model="formData.address"
          class="form-textarea"
          :placeholder="localeStore.t('enterHomeAddress')"
          rows="3"
          :disabled="verified"
        ></textarea>
      </div>

      <!-- 身份证上传 -->
      <div class="form-section">
        <div class="form-label">{{ localeStore.t('uploadIdFrontBack') }} <span class="required">*</span></div>
        <div class="upload-section">
          <!-- 正面 -->
          <div class="upload-item">
            <div 
              class="upload-area"
              :class="{ 'has-image': frontImagePreview }"
              @click="!verified && triggerFrontImageSelect()"
            >
              <img 
                v-if="frontImagePreview" 
                :src="frontImagePreview" 
                class="upload-preview"
                :alt="localeStore.t('uploadIdFront')"
              />
              <img 
                v-else
                src="/img/id-front7ede1e14.png" 
                class="upload-placeholder"
                :alt="localeStore.t('uploadIdFront')"
              />
              <div v-if="!frontImagePreview && !verified" class="upload-plus">+</div>
            </div>
            <div class="upload-label">{{ localeStore.t('uploadIdFront') }}</div>
            <input 
              ref="frontImageInputRef"
              type="file" 
              accept="image/*" 
              style="display: none"
              @change="handleFrontImageSelect"
              :disabled="verified"
            />
          </div>

          <!-- 反面 -->
          <div class="upload-item">
            <div 
              class="upload-area"
              :class="{ 'has-image': backImagePreview }"
              @click="!verified && triggerBackImageSelect()"
            >
              <img 
                v-if="backImagePreview" 
                :src="backImagePreview" 
                class="upload-preview"
                :alt="localeStore.t('uploadIdBack')"
              />
              <img 
                v-else
                src="/img/id-backgroundc00f8b70.png" 
                class="upload-placeholder"
                :alt="localeStore.t('uploadIdBack')"
              />
              <div v-if="!backImagePreview && !verified" class="upload-plus">+</div>
            </div>
            <div class="upload-label">{{ localeStore.t('uploadIdBack') }}</div>
            <input 
              ref="backImageInputRef"
              type="file" 
              accept="image/*" 
              style="display: none"
              @change="handleBackImageSelect"
              :disabled="verified"
            />
          </div>
        </div>
      </div>

      <!-- 手持身份证上传 -->
      <div class="form-section">
        <div class="form-label">{{ localeStore.t('uploadHandheldId') }} <span class="required">*</span></div>
        <div class="upload-section">
          <div class="upload-item">
            <div 
              class="upload-area"
              :class="{ 'has-image': handheldImagePreview }"
              @click="!verified && triggerHandheldImageSelect()"
            >
              <img 
                v-if="handheldImagePreview" 
                :src="handheldImagePreview" 
                class="upload-preview"
                :alt="localeStore.t('uploadHandheldId')"
              />
              <img 
                v-else
                src="/img/id-front7ede1e14.png" 
                class="upload-placeholder"
                :alt="localeStore.t('uploadHandheldId')"
              />
              <div v-if="!handheldImagePreview && !verified" class="upload-plus">+</div>
            </div>
            <div class="upload-label">{{ localeStore.t('uploadHandheldId') }}</div>
            <input 
              ref="handheldImageInputRef"
              type="file" 
              accept="image/*" 
              style="display: none"
              @change="handleHandheldImageSelect"
              :disabled="verified"
            />
          </div>
        </div>
      </div>

      <button 
        v-if="!verified"
        class="submit-btn" 
        @click="submitPersonalInfo" 
        :disabled="submitting || loading"
      >
        {{ submitting ? localeStore.t('submitting') : localeStore.t('submitReview') }}
      </button>
    </div>

    <Tabbar />

    <!-- Toast提示 -->
    <div v-if="toastMessage" :class="['toast-message', toastType]">
      {{ toastMessage }}
    </div>
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

.verified-notice {
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

.country-code-selector {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 12px 12px;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  background: #fff;
  cursor: pointer;
  min-width: 100px;
  justify-content: space-between;
  user-select: none;
}

.country-code-selector.disabled {
  background: #f5f5f5;
  cursor: not-allowed;
  color: #999;
}

.country-code-flag {
  font-size: 18px;
  line-height: 1;
}

.country-code-value {
  font-size: 14px;
  font-weight: 500;
  color: #333;
  flex: 1;
}

.dropdown-arrow {
  width: 12px;
  height: 12px;
  color: #666;
  transition: transform 0.2s;
}

.dropdown-arrow.rotated {
  transform: rotate(180deg);
}

.phone-input {
  flex: 1;
}

.country-code-dropdown {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  right: 0;
  background: #fff;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  z-index: 1000;
  max-height: 300px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.dropdown-search {
  padding: 12px;
  border-bottom: 1px solid #e0e0e0;
}

.search-input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid #e0e0e0;
  border-radius: 6px;
  font-size: 14px;
  box-sizing: border-box;
}

.search-input:focus {
  outline: none;
  border-color: #2abf4b;
}

.dropdown-list {
  flex: 1;
  overflow-y: auto;
  max-height: 250px;
}

.dropdown-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  cursor: pointer;
  transition: background 0.2s;
  border-bottom: 1px solid #f5f5f5;
}

.dropdown-item:last-child {
  border-bottom: none;
}

.dropdown-item:hover {
  background: #f5f5f5;
}

.dropdown-item.active {
  background: #e8f5e9;
  color: #2abf4b;
}

.item-flag {
  font-size: 20px;
  line-height: 1;
  width: 24px;
  text-align: center;
}

.item-code {
  font-size: 14px;
  font-weight: 500;
  color: #333;
  min-width: 50px;
}

.item-name {
  font-size: 14px;
  color: #666;
  flex: 1;
}

.dropdown-item.active .item-code,
.dropdown-item.active .item-name {
  color: #2abf4b;
}
</style>

