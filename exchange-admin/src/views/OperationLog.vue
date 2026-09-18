<template>
  <div class="operation-log-page">
    <el-card shadow="never">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>操作日志</span>
        </div>
      </template>
      
      <!-- 搜索栏 -->
      <div style="margin-bottom: 20px;">
        <el-form :inline="true" :model="queryParams">
          <el-form-item label="操作类型">
            <el-select v-model="queryParams.operationType" placeholder="全部" clearable style="width: 150px">
              <el-option label="用户管理" value="用户管理" />
              <el-option label="订单管理" value="订单管理" />
              <el-option label="充值审核" value="充值审核" />
              <el-option label="提现审核" value="提现审核" />
              <el-option label="实名审核" value="实名审核" />
              <el-option label="贷款审核" value="贷款审核" />
              <el-option label="币种管理" value="币种管理" />
              <el-option label="角色管理" value="角色管理" />
              <el-option label="代理管理" value="代理管理" />
            </el-select>
          </el-form-item>
          <el-form-item label="开始时间">
            <el-date-picker
              v-model="queryParams.startTime"
              type="datetime"
              placeholder="选择开始时间"
              format="YYYY-MM-DD HH:mm:ss"
              value-format="YYYY-MM-DDTHH:mm:ss"
              style="width: 200px"
            />
          </el-form-item>
          <el-form-item label="结束时间">
            <el-date-picker
              v-model="queryParams.endTime"
              type="datetime"
              placeholder="选择结束时间"
              format="YYYY-MM-DD HH:mm:ss"
              value-format="YYYY-MM-DDTHH:mm:ss"
              style="width: 200px"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="handleSearch">搜索</el-button>
            <el-button @click="handleReset">重置</el-button>
          </el-form-item>
        </el-form>
      </div>
      
      <!-- 日志列表 -->
      <el-table :data="logList" v-loading="loading" border stripe>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="adminEmail" label="操作人" width="150">
          <template #default="{ row }">
            {{ row.adminEmail || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="operationType" label="操作类型" width="120" />
        <el-table-column prop="operationAction" label="操作动作" width="120" />
        <el-table-column prop="targetType" label="目标类型" width="100">
          <template #default="{ row }">
            {{ row.targetType || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="targetInfo" label="目标信息" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.targetInfo || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="requestMethod" label="请求方法" width="100">
          <template #default="{ row }">
            {{ row.requestMethod || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="ipAddress" label="IP地址" width="130">
          <template #default="{ row }">
            {{ row.ipAddress || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'SUCCESS' ? 'success' : 'danger'">
              {{ row.status === 'SUCCESS' ? '成功' : (row.status === 'FAILED' ? '失败' : row.status || '成功') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="操作时间" width="180">
          <template #default="{ row }">
            {{ formatDateTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="handleViewDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      
      <el-empty v-if="!loading && logList.length === 0" description="暂无操作日志" />
      
      <!-- 分页 -->
      <div style="margin-top: 20px; display: flex; justify-content: flex-end;">
        <el-pagination
          v-model:current-page="queryParams.page"
          v-model:page-size="queryParams.size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>
    
    <!-- 详情对话框 -->
    <el-dialog v-model="detailDialogVisible" title="操作日志详情" width="800px">
      <el-descriptions v-if="currentLog" :column="2" border>
        <el-descriptions-item label="ID">{{ currentLog.id }}</el-descriptions-item>
        <el-descriptions-item label="操作人">{{ currentLog.adminEmail || '-' }}</el-descriptions-item>
        <el-descriptions-item label="操作类型">{{ currentLog.operationType }}</el-descriptions-item>
        <el-descriptions-item label="操作动作">{{ currentLog.operationAction }}</el-descriptions-item>
        <el-descriptions-item label="目标类型">{{ currentLog.targetType || '-' }}</el-descriptions-item>
        <el-descriptions-item label="目标ID">{{ currentLog.targetId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="目标信息" :span="2">{{ currentLog.targetInfo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="请求方法">{{ currentLog.requestMethod || '-' }}</el-descriptions-item>
        <el-descriptions-item label="IP地址">{{ currentLog.ipAddress || '-' }}</el-descriptions-item>
        <el-descriptions-item label="请求URL" :span="2">{{ currentLog.requestUrl || '-' }}</el-descriptions-item>
        <el-descriptions-item label="请求参数" :span="2">
          <pre style="max-height: 200px; overflow: auto;">{{ formatJson(currentLog.requestParams) }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="用户代理" :span="2">{{ currentLog.userAgent || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="currentLog.status === 'SUCCESS' ? 'success' : 'danger'">
            {{ currentLog.status === 'SUCCESS' ? '成功' : '失败' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="操作时间">{{ formatDateTime(currentLog.createdAt) }}</el-descriptions-item>
        <el-descriptions-item v-if="currentLog.errorMessage" label="错误信息" :span="2">
          <pre style="color: #f56c6c; max-height: 200px; overflow: auto;">{{ currentLog.errorMessage }}</pre>
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detailDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'

const loading = ref(false)
const logList = ref<any[]>([])
const total = ref(0)
const queryParams = ref({
  adminId: null as number | null,
  operationType: '',
  startTime: '',
  endTime: '',
  page: 1,
  size: 20,
})

const detailDialogVisible = ref(false)
const currentLog = ref<any>(null)

// 加载日志列表
const loadLogs = async () => {
  loading.value = true
  try {
    const params: any = {
      page: queryParams.value.page - 1,
      size: queryParams.value.size,
    }
    if (queryParams.value.operationType) {
      params.operationType = queryParams.value.operationType
    }
    if (queryParams.value.startTime) {
      params.startTime = queryParams.value.startTime
    }
    if (queryParams.value.endTime) {
      params.endTime = queryParams.value.endTime
    }
    
    const res: any = await request.get('/admin/operation-logs', { params })
    console.log('操作日志API响应:', res)
    
    // 处理响应数据
    if (res) {
      // 如果success为false，显示错误
      if (res.success === false) {
        ElMessage.error(res.message || '加载失败')
        logList.value = []
        total.value = 0
        return
      }
      
      // 如果返回的是数组（直接返回列表的情况）
      if (Array.isArray(res)) {
        logList.value = res
        total.value = res.length
      } 
      // 如果返回的是对象，包含list和total（后端标准格式）
      else if (res.list !== undefined) {
        logList.value = Array.isArray(res.list) ? res.list : []
        total.value = res.total || 0
      }
      // 如果返回的是content（Spring Data Page格式）
      else if (res.content !== undefined) {
        logList.value = Array.isArray(res.content) ? res.content : []
        total.value = res.totalElements || res.total || 0
      }
      // 其他格式，清空列表
      else {
        console.warn('未知的响应格式:', res)
        logList.value = []
        total.value = 0
      }
    } else {
      logList.value = []
      total.value = 0
    }
  } catch (e: any) {
    console.error('加载操作日志失败:', e)
    ElMessage.error(e?.message || '加载失败')
    logList.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  queryParams.value.page = 1
  loadLogs()
}

const handleReset = () => {
  queryParams.value = {
    adminId: null,
    operationType: '',
    startTime: '',
    endTime: '',
    page: 1,
    size: 20,
  }
  loadLogs()
}

const handlePageChange = (page: number) => {
  queryParams.value.page = page
  loadLogs()
}

const handleSizeChange = (size: number) => {
  queryParams.value.size = size
  queryParams.value.page = 1
  loadLogs()
}

const handleViewDetail = (row: any) => {
  currentLog.value = row
  detailDialogVisible.value = true
}

const formatDateTime = (dateTime: string | null | undefined): string => {
  if (!dateTime) return '-'
  return new Date(dateTime).toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).replace(/\//g, '-')
}

const formatJson = (json: string | null | undefined): string => {
  if (!json) return '-'
  try {
    const obj = JSON.parse(json)
    return JSON.stringify(obj, null, 2)
  } catch {
    return json
  }
}

onMounted(() => {
  loadLogs()
})
</script>

<style scoped>
.operation-log-page {
  padding: 0;
}
</style>



