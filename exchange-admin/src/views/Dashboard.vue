<script setup lang="ts">
import { ref, onMounted, computed, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import request from '@/utils/request'
import { useAuthStore } from '@/store/auth'

const auth = useAuthStore()
// 判断当前登录用户是否是代理
const isAgent = computed(() => auth.user?.userType === 'agent')

// 图表相关
const chartContainer = ref<HTMLDivElement | null>(null)
let chartInstance: echarts.ECharts | null = null

// 根据用户类型初始化不同的统计指标
const stats = ref([
  { title: '用户总数', value: '0', icon: 'User', color: '#409eff' },
  { title: '今日交易', value: '0', icon: 'Money', color: '#67c23a' },
  { title: '交易金额', value: '¥0', icon: 'TrendCharts', color: '#e6a23c' },
  { title: '活跃用户', value: '0', icon: 'DataAnalysis', color: '#f56c6c' },
])

// 初始化统计指标标题（根据用户类型）
function initStatsTitles() {
  if (isAgent.value) {
    stats.value = [
      { title: '下级用户', value: '0', icon: 'User', color: '#409eff' },
      { title: '下级用户交易订单', value: '0', icon: 'Money', color: '#67c23a' },
      { title: '下级用户交易金额', value: '¥0', icon: 'TrendCharts', color: '#e6a23c' },
    ]
  } else {
    stats.value = [
      { title: '用户总数', value: '0', icon: 'User', color: '#409eff' },
      { title: '今日交易', value: '0', icon: 'Money', color: '#67c23a' },
      { title: '交易金额', value: '¥0', icon: 'TrendCharts', color: '#e6a23c' },
      { title: '活跃用户', value: '0', icon: 'DataAnalysis', color: '#f56c6c' },
    ]
  }
}

// 格式化金额
function formatMoney(amount: number | string | null | undefined) {
  if (!amount) return '¥0'
  const num = Number(amount)
  return '¥' + num.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 加载统计数据
async function loadStats() {
  try {
    const res: any = await request.get('/admin/dashboard/stats')
    if (res && res.success && res.data) {
      const data = res.data
      if (isAgent.value) {
        // 代理显示：下级用户、下级用户交易订单、下级用户交易金额
        if (stats.value[0]) {
          stats.value[0].value = String(data.subordinateCount || 0)
        }
        if (stats.value[1]) {
          stats.value[1].value = String(data.subordinateOrderCount || 0)
        }
        if (stats.value[2]) {
          stats.value[2].value = formatMoney(data.subordinateOrderAmount)
        }
      } else {
        // 管理员显示：用户总数、今日交易、交易金额、活跃用户
        if (stats.value[0]) {
          stats.value[0].value = String(data.totalUsers || 0)
        }
        if (stats.value[1]) {
          stats.value[1].value = String(data.todayTransactions || 0)
        }
        if (stats.value[2]) {
          stats.value[2].value = formatMoney(data.todayAmount)
        }
        if (stats.value[3]) {
          stats.value[3].value = String(data.activeUsers || 0)
        }
      }
    }
  } catch (e: any) {
    console.error('加载统计数据失败:', e)
    // 不显示错误提示，保持默认值
  }
}

// 加载图表数据
async function loadChartData() {
  try {
    const res: any = await request.get('/admin/dashboard/chart-data')
    if (res && res.success && res.data) {
      const { dates, depositAmounts, withdrawAmounts } = res.data
      updateChart(dates, depositAmounts, withdrawAmounts)
    }
  } catch (e: any) {
    console.error('加载图表数据失败:', e)
  }
}

// 更新图表
function updateChart(dates: string[], depositAmounts: number[], withdrawAmounts: number[]) {
  if (!chartInstance || !chartContainer.value) return
  
  const option = {
    tooltip: {
      trigger: 'axis',
      axisPointer: {
        type: 'cross'
      }
    },
    legend: {
      data: ['充值', '提现']
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '3%',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: dates
    },
    yAxis: {
      type: 'value'
    },
    series: [
      {
        name: '充值',
        type: 'line',
        data: depositAmounts,
        itemStyle: {
          color: '#f56c6c'
        },
        lineStyle: {
          color: '#f56c6c'
        }
      },
      {
        name: '提现',
        type: 'line',
        data: withdrawAmounts,
        itemStyle: {
          color: '#409eff'
        },
        lineStyle: {
          color: '#409eff'
        }
      }
    ]
  }
  
  chartInstance.setOption(option)
}

// 初始化图表
function initChart() {
  if (!chartContainer.value) return
  
  chartInstance = echarts.init(chartContainer.value)
  
  // 监听窗口大小变化
  window.addEventListener('resize', () => {
    chartInstance?.resize()
  })
}

onMounted(() => {
  initStatsTitles()
  loadStats()
  nextTick(() => {
    initChart()
    loadChartData()
  })
})
</script>

<template>
  <div class="dashboard">
    <el-row :gutter="20">
      <el-col :xs="24" :sm="12" :lg="6" v-for="(item, index) in stats" :key="index">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-content">
            <div class="stat-left">
              <div class="stat-title">{{ item.title }}</div>
              <div class="stat-value">{{ item.value }}</div>
            </div>
            <div class="stat-icon" :style="{ background: item.color }">
              <el-icon size="30">
                <component :is="item.icon" />
              </el-icon>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="24">
        <el-card>
          <template #header>
            <span>充值与提现统计</span>
          </template>
          <div ref="chartContainer" style="width: 100%; height: 400px;"></div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.dashboard {
  padding: 0;
}

.stat-card {
  margin-bottom: 20px;
  transition: all 0.3s;
}

.stat-card:hover {
  transform: translateY(-4px);
}

.stat-content {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.stat-left {
  flex: 1;
}

.stat-title {
  font-size: 14px;
  color: #909399;
  margin-bottom: 10px;
}

.stat-value {
  font-size: 28px;
  font-weight: 600;
  color: #303133;
}

.stat-icon {
  width: 60px;
  height: 60px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
}
</style>