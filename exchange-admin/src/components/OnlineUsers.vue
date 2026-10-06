<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { startReadPolling } from '@/utils/readPolling'
const props = defineProps<{ load: (page: number, size: number, userEmail?: string) => Promise<any>; control?: boolean }>()
const email = ref(''), appliedEmail = ref('')
function search() { appliedEmail.value = email.value.trim(); if (page.value === 1) void read(); else page.value = 1 }
const page = ref(1), rows = ref<any[]>([]), total = ref(0), asOf = ref(''), error = ref('')
let generation = 0, active = true, stop: (() => void) | undefined
const pages: Record<string,string> = { home:'首页',trade:'交易',option:'期权',contract:'合约',assets:'资产',financial:'理财',support:'客服',orders:'订单',profile:'个人中心',deposit:'充值',withdraw:'提现',wallet:'钱包',verification:'实名',transfer:'转账',security:'安全',inbox:'站内信',announcement:'公告',loan:'借贷',search:'搜索',invite:'邀请',settings:'设置',unknown:'未知' }
function time(value?: string | null) {
  if (!value || Number.isNaN(Date.parse(value))) return '未知'
  return /(?:Z|[+-]\d{2}:?\d{2})$/i.test(value)
    ? new Date(value).toLocaleString()
    : value.replace('T', ' ').replace(/\.\d+$/, '')
}
async function read() {
  if (document.visibilityState !== 'visible') return true
  const version = ++generation
  try {
    const response = await props.load(page.value - 1, 20, appliedEmail.value || undefined)
    if (!active || generation !== version) return false
    if (response.success === false) throw new Error(response.message || '在线明细读取失败')
    const data = response.data || response
    if (!Array.isArray(data.items) || !Number.isFinite(Number(data.total))) throw new Error('在线明细响应格式不符')
    rows.value = data.items; total.value = Number(data.total); asOf.value = data.asOf || ''; error.value = ''
    return true
  } catch (e: any) { if (active && generation === version) error.value = e.message || '在线明细读取失败'; return false }
}
watch(page, () => { void read() })
onMounted(() => { stop = startReadPolling(read) })
onUnmounted(() => { active = false; generation++; stop?.() })
</script>
<template>
  <p>最近 5 分钟有效活动的真实用户。最近页面为客户端上报，不代表实时正在浏览；模拟账户不重复计数。未标时区的时间保留服务器时间；已标时区的时间转换为本机时间。IP/地区为最近登录记录。</p>
  <el-alert v-if="error" :title="error + '；以下数据可能已过期。'" type="error" :closable="false" />
  <p v-if="asOf">统计时点：{{ time(asOf) }} · 默认 5 秒刷新</p>
  <el-form inline @submit.prevent="search"><el-form-item label="用户邮箱"><el-input v-model="email" clearable maxlength="254" placeholder="用户邮箱" @clear="search" /></el-form-item><el-button v-permission="control ? 'session:self' : 'users:view'" native-type="submit">搜索</el-button></el-form>
  <admin-table table-key="online-users" :data="rows" row-key="id" border>
    <el-table-column prop="id" label="用户 ID" width="100" />
    <el-table-column prop="userEmail" label="用户邮箱" min-width="200" show-overflow-tooltip />
    <el-table-column prop="userRemark" label="用户备注" min-width="150" show-overflow-tooltip><template #default="scope">{{ scope.row.userRemark || '-' }}</template></el-table-column>
    <el-table-column prop="lastLoginIp" label="IP（地址）" min-width="200" show-overflow-tooltip>
      <template #default="scope">
        <div>{{ scope.row.lastLoginIp || '-' }}</div>
        <div style="color: var(--el-text-color-secondary); font-size: 12px;">{{ scope.row.lastLoginRegion || '-' }}</div>
      </template>
    </el-table-column>
    <el-table-column column-key="recentPage" label="最近页面" width="110"><template #default="scope">{{ pages[scope.row.lastPageCode] || '未知' }}</template></el-table-column>
    <el-table-column column-key="pageTime" label="页面时间" min-width="175"><template #default="scope">{{ time(scope.row.lastPageSeenAt) }}</template></el-table-column>
    <el-table-column column-key="activityTime" label="活动时间" min-width="175"><template #default="scope">{{ time(scope.row.lastActiveAt) }}</template></el-table-column>
    <el-table-column prop="deviceType" label="端类型" width="100" />
  </admin-table>
  <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" />
</template>
