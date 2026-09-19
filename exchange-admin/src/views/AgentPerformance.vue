<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { rawRequest as axios } from '@/utils/request'
import { ArrowLeft, User, Coin, Money, TrendCharts } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
// 开发环境使用空字符串，让Vite代理处理；生产环境使用生产API域名（不包含/api后缀）
const getApiBase = () => {
  if (import.meta.env.DEV) {
    return ''
  }
  const baseUrl = import.meta.env.VITE_API_BASE_URL || 'https://api1.m.ydgggd.com'
  // 确保不包含 /api 后缀，因为路径中会添加 /api
  return baseUrl.replace(/\/api\/?$/, '')
}
const API_BASE = getApiBase()

const agentId = ref<number>(Number(route.params.id))
const agentInfo = ref<any>({})
const performance = ref<any>({})
const loading = ref(false)

// 下级用户列表
const subordinates = ref<any[]>([])
const subordinatesLoading = ref(false)

// 获取代理信息
const fetchAgentInfo = async () => {
  try {
    const response = await axios.get(`${API_BASE}/api/admin/users/${agentId.value}`)
    if (response.data) {
      agentInfo.value = response.data
    }
  } catch (error: any) {
    console.error('获取代理信息失败:', error)
  }
}

// 获取业绩数据
const fetchPerformance = async () => {
  loading.value = true
  try {
    const response = await axios.get(`${API_BASE}/api/admin/agents/${agentId.value}/performance`)
    if (response.data.success) {
      performance.value = response.data.data
    } else {
      ElMessage.error(response.data.message || '获取业绩数据失败')
    }
  } catch (error: any) {
    console.error('获取业绩数据失败:', error)
    // 使用模拟数据
    performance.value = {
      subordinateCount: 0,
      subordinateTotalDeposit: 0,
      subordinateTotalWithdraw: 0,
      totalTrade: 0,
    }
  } finally {
    loading.value = false
  }
}

// 获取下级用户列表
const fetchSubordinates = async () => {
  subordinatesLoading.value = true
  try {
    const response = await axios.get(`${API_BASE}/api/admin/users/${agentId.value}/subordinates`)
    if (response.data && response.data.list) {
      subordinates.value = response.data.list
    } else if (Array.isArray(response.data)) {
      subordinates.value = response.data
    } else {
      subordinates.value = []
    }
  } catch (error: any) {
    console.error('获取下级用户失败:', error)
    subordinates.value = []
  } finally {
    subordinatesLoading.value = false
  }
}

// 格式化数字（保留2位小数）
const formatNumber = (num: any) => {
  if (!num && num !== 0) return '0.00'
  const n = typeof num === 'string' ? parseFloat(num) : num
  return n.toFixed(2)
}

// 返回
const goBack = () => {
  router.push('/agents')
}

onMounted(() => {
  fetchAgentInfo()
  fetchPerformance()
  fetchSubordinates()
})
</script>

<template>
  <div class="agent-performance">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <el-button :icon="ArrowLeft" @click="goBack">返回</el-button>
          <span class="card-title">代理业绩 - {{ agentInfo.email }}</span>
        </div>
      </template>

      <!-- 代理基本信息 -->
      <el-descriptions title="代理信息" :column="3" border>
        <el-descriptions-item label="ID">{{ agentInfo.id }}</el-descriptions-item>
        <el-descriptions-item label="邮箱">{{ agentInfo.email }}</el-descriptions-item>
        <el-descriptions-item label="昵称">{{ agentInfo.nickname || '-' }}</el-descriptions-item>
        <el-descriptions-item label="推广码">{{ agentInfo.myInviteCode }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag v-if="agentInfo.status === 'active'" type="success">正常</el-tag>
          <el-tag v-else type="danger">{{ agentInfo.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="注册时间">{{ agentInfo.createdAt }}</el-descriptions-item>
      </el-descriptions>

      <!-- 业绩统计 -->
      <el-divider />
      <h3 style="margin-bottom: 20px;">业绩统计</h3>
      
      <el-row :gutter="20" v-loading="loading">
        <el-col :span="8">
          <el-card shadow="hover" class="stat-card">
            <div class="stat-icon" style="background: #ecf5ff; color: #409eff;">
              <el-icon :size="30"><User /></el-icon>
            </div>
            <div class="stat-content">
              <div class="stat-label">下级用户数</div>
              <div class="stat-value">{{ performance.subordinateCount || 0 }}人</div>
            </div>
          </el-card>
        </el-col>
        
        <el-col :span="8">
          <el-card shadow="hover" class="stat-card">
            <div class="stat-icon" style="background: #f0f9ff; color: #67c23a;">
              <el-icon :size="30"><Coin /></el-icon>
            </div>
            <div class="stat-content">
              <div class="stat-label">下级用户累计充值</div>
              <div class="stat-value">{{ formatNumber(performance.subordinateTotalDeposit) }} USDT</div>
            </div>
          </el-card>
        </el-col>
        
        <el-col :span="8">
          <el-card shadow="hover" class="stat-card">
            <div class="stat-icon" style="background: #fef0f0; color: #f56c6c;">
              <el-icon :size="30"><Money /></el-icon>
            </div>
            <div class="stat-content">
              <div class="stat-label">下级用户累计提现</div>
              <div class="stat-value">{{ formatNumber(performance.subordinateTotalWithdraw) }} USDT</div>
            </div>
          </el-card>
        </el-col>
        
        <el-col :span="8">
          <el-card shadow="hover" class="stat-card">
            <div class="stat-icon" style="background: #f4f4f5; color: #909399;">
              <el-icon :size="30"><TrendCharts /></el-icon>
            </div>
            <div class="stat-content">
              <div class="stat-label">累计交易量</div>
              <div class="stat-value">{{ formatNumber(performance.totalTrade) }} USDT</div>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <!-- 下级用户列表 -->
      <el-divider />
      <h3 style="margin-bottom: 20px;">下级用户列表</h3>
      
      <el-table :data="subordinates" v-loading="subordinatesLoading" stripe border>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="email" label="邮箱" min-width="180" />
        <el-table-column prop="nickname" label="昵称" width="120">
          <template #default="{ row }">
            {{ row.nickname || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="用户类型" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.userType === 'agent'" type="warning">代理</el-tag>
            <el-tag v-else type="success">普通用户</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.status === 'active'" type="success">正常</el-tag>
            <el-tag v-else type="danger">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="注册时间" width="180" />
      </el-table>
      
      <el-empty v-if="!subordinatesLoading && subordinates.length === 0" description="暂无下级用户" />
    </el-card>
  </div>
</template>

<style scoped>
.agent-performance {
  padding: 0;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 15px;
}

.card-title {
  font-size: 18px;
  font-weight: bold;
}

.stat-card {
  cursor: pointer;
  transition: all 0.3s;
}

.stat-card:hover {
  transform: translateY(-5px);
}

.stat-card :deep(.el-card__body) {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 20px;
}

.stat-icon {
  width: 60px;
  height: 60px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.stat-content {
  flex: 1;
}

.stat-label {
  font-size: 14px;
  color: #909399;
  margin-bottom: 8px;
}

.stat-value {
  font-size: 24px;
  font-weight: bold;
  color: #303133;
}
</style>
