<template>
  <div class="statistics-page">
    <el-card shadow="never">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>数据统计</span>
          <div>
            <el-date-picker
              v-model="dateRange"
              type="daterange"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              format="YYYY-MM-DD"
              value-format="YYYY-MM-DD"
              @change="handleDateChange"
              style="width: 300px; margin-right: 10px;"
            />
            <el-button type="primary" @click="loadStatistics">查询</el-button>
          </div>
        </div>
      </template>
      
      <!-- 统计卡片 -->
      <el-row :gutter="20" style="margin-bottom: 20px;">
        <el-col :span="6">
          <el-card shadow="hover">
            <div style="text-align: center;">
              <div style="font-size: 14px; color: #909399; margin-bottom: 10px;">总用户数</div>
              <div style="font-size: 32px; font-weight: 600; color: #409eff;">{{ stats.totalUsers || 0 }}</div>
            </div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="hover">
            <div style="text-align: center;">
              <div style="font-size: 14px; color: #909399; margin-bottom: 10px;">充值总额</div>
              <div style="font-size: 32px; font-weight: 600; color: #67c23a;">{{ formatMoney(stats.totalDeposit) }}</div>
            </div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="hover">
            <div style="text-align: center;">
              <div style="font-size: 14px; color: #909399; margin-bottom: 10px;">提现总额</div>
              <div style="font-size: 32px; font-weight: 600; color: #e6a23c;">{{ formatMoney(stats.totalWithdraw) }}</div>
            </div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="hover">
            <div style="text-align: center;">
              <div style="font-size: 14px; color: #909399; margin-bottom: 10px;">交易总额</div>
              <div style="font-size: 32px; font-weight: 600; color: #f56c6c;">{{ formatMoney(stats.totalTrade) }}</div>
            </div>
          </el-card>
        </el-col>
      </el-row>
      
      <!-- 图表 -->
      <el-card shadow="never" style="margin-top: 20px;">
        <template #header>
          <span>充值与提现趋势</span>
        </template>
        <div ref="chartContainer" style="width: 100%; height: 400px;"></div>
      </el-card>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import request from '@/utils/request'

const dateRange = ref<[string, string] | null>(null)
const stats = ref<any>({})
const loading = ref(false)

// 图表相关
const chartContainer = ref<HTMLDivElement | null>(null)
let chartInstance: echarts.ECharts | null = null

// 格式化金额
const formatMoney = (amount: number | string | null | undefined) => {
  if (!amount) return '¥0'
  const num = Number(amount)
  return '¥' + num.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 加载统计数据
const loadStatistics = async () => {
  loading.value = true
  try {
    const params: any = {}
    if (dateRange.value && dateRange.value.length === 2) {
      params.startDate = dateRange.value[0]
      params.endDate = dateRange.value[1]
    }
    
    const res: any = await request.get('/admin/statistics', { params })
    console.log('统计数据响应:', res)
    
    if (res && res.success) {
      if (res.data) {
        // 直接使用返回的数据
        stats.value = {
          totalUsers: res.data.totalUsers ?? 0,
          totalDeposit: res.data.totalDeposit ?? 0,
          totalWithdraw: res.data.totalWithdraw ?? 0,
          totalTrade: res.data.totalTrade ?? 0,
        }
        console.log('统计数据:', stats.value)
        
        // 更新图表
        if (res.data.chartData) {
          const { dates, depositAmounts, withdrawAmounts } = res.data.chartData
          if (dates && depositAmounts && withdrawAmounts) {
            updateChart(dates, depositAmounts, withdrawAmounts)
          }
        }
      } else {
        console.warn('响应中没有data字段:', res)
        ElMessage.warning('未获取到统计数据')
      }
    } else {
      console.error('API返回失败:', res)
      ElMessage.error(res?.message || '获取统计数据失败')
    }
  } catch (e: any) {
    console.error('加载统计数据异常:', e)
    ElMessage.error(e?.response?.data?.message || e?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

// 更新图表
const updateChart = (dates: string[], depositAmounts: number[], withdrawAmounts: number[]) => {
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
const initChart = () => {
  if (!chartContainer.value) return
  chartInstance = echarts.init(chartContainer.value)
  window.addEventListener('resize', () => {
    chartInstance?.resize()
  })
}

const handleDateChange = () => {
  // 日期变化时自动查询
  loadStatistics()
}

onMounted(() => {
  // 默认查询最近30天
  const end = new Date()
  const start = new Date()
  start.setDate(start.getDate() - 30)
  const startStr = start.toISOString().split('T')[0]
  const endStr = end.toISOString().split('T')[0]
  if (startStr && endStr) {
    dateRange.value = [startStr, endStr]
  }
  
  nextTick(() => {
    initChart()
    loadStatistics()
  })
})
</script>

<style scoped>
.statistics-page {
  padding: 0;
}
</style>

