<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, Edit, Delete, Star, StarFilled, List } from '@element-plus/icons-vue'
import request from '@/utils/request'
import SymbolCatalogDialog from '@/components/SymbolCatalogDialog.vue'
import { getImageUrl } from '@/utils/imageUrl'
import { usePermissions } from '@/composables/usePermissions'

const { hasPermission } = usePermissions()
const canEditSymbol = ref(true)
const canDeleteSymbol = ref(true)

const loadPermissions = async () => {
  canEditSymbol.value = await hasPermission('symbols', 'edit_symbol')
  canDeleteSymbol.value = await hasPermission('symbols', 'delete_symbol')
}

const symbols = ref<any[]>([])
const total = ref(0)
const loading = ref(false)
const dialogVisible = ref(false)
const dialogTitle = ref('新增币种')
const formData = ref<any>({
  id: null,
  symbol: '',
  baseCurrency: '',
  quoteCurrency: 'USD',
  name: '',
  nameEn: '',
  category: 'US',
  iconUrl: '',
  isHot: false,
  isEnabled: true,
  sortOrder: 0,
  pricePrecision: 2,
  volumePrecision: 2,
  minTradeAmount: 0,
  alltickSymbol: '',
  lotSize: 1000, // 每手数量
  feeMultiplier: 30, // 手续费倍数
  maxLeverage: 100, // 用户可选杠杆上限
})

const queryParams = ref({
  category: '',
  page: 0,
  size: 20,
})

const categories = computed(() => [{ label: '全部', value: '' }, ...categoryList.value.map(c => ({label: c.label, value: c.key}))])
const sourceOptions = ref<any[]>([])
const sourceCategories = (source: string) => sourceOptions.value.find(s => s.value === source)?.categories || []
const catalogVisible = ref(false)

const loadSymbols = async () => {
  loading.value = true
  try {
    const res: any = await request.post('/admin/symbols/query', queryParams.value)
    symbols.value = res.list || []
    total.value = res.total || 0
  } catch (e: any) {
    ElMessage.error(e?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  queryParams.value.page = 0
  loadSymbols()
}

const handleReset = () => {
  queryParams.value.category = ''
  queryParams.value.page = 0
  loadSymbols()
}

// ===== 分类管理相关 =====
const categoryDialogVisible = ref(false)
const categoryLoading = ref(false)
const categoryList = ref<any[]>([])
const categoryAllowsLeverage = (key: string) => categoryList.value.find(c => c.key === key)?.leverageEnabled !== false

// ===== 杠杆设置相关 =====
const leverageDialogVisible = ref(false)
const leverageLoading = ref(false)
const leverageForm = ref({
  leverage: 100,
  applyTo: 'selected', // 'selected': 选中币种, 'category': 按分类, 'all': 全部
  category: '',
  symbolIds: [] as number[],
})

const openCategoryDialog = async () => {
  categoryDialogVisible.value = true
  await loadCategoryConfig()
}

const openLeverageDialog = () => {
  leverageForm.value = {
    leverage: 100,
    applyTo: 'selected',
    category: queryParams.value.category || '',
    symbolIds: [],
  }
  leverageDialogVisible.value = true
}

const saveLeverageSettings = async () => {
  try {
    leverageLoading.value = true
    
    const payload: any = {
      leverage: leverageForm.value.leverage,
    }
    
    if (leverageForm.value.applyTo === 'selected') {
      // 获取当前页选中的币种（这里简化处理，实际可以添加多选功能）
      // 暂时提示用户需要先选择币种
      ElMessage.warning('请先选择要设置的币种，或使用"按分类"或"全部"选项')
      return
    } else if (leverageForm.value.applyTo === 'category') {
      payload.category = leverageForm.value.category || queryParams.value.category || ''
    } else {
      // 全部币种
      payload.category = ''
    }
    
    await request.post('/admin/symbols/batchSetLeverage', payload)
    ElMessage.success('杠杆上限设置成功')
    leverageDialogVisible.value = false
    loadSymbols() // 刷新列表
  } catch (e: any) {
    ElMessage.error(e?.message || '设置失败')
  } finally {
    leverageLoading.value = false
  }
}

const loadCategoryConfig = async () => {
  categoryLoading.value = true
  try {
    const [rows, sources]: any = await Promise.all([request.get('/admin/symbols/categories'), request.get('/admin/symbols/catalog/sources')])
    categoryList.value = rows.map((row: any) => ({ ...row, leverageEnabled: row.leverageEnabled !== false }))
    sourceOptions.value = sources
  } catch (e: any) {
    ElMessage.error(e?.message || '加载分类配置失败')
  } finally {
    categoryLoading.value = false
  }
}

const saveCategoryConfig = async () => {
  try {
    categoryLoading.value = true
    categoryList.value = await request.post('/admin/symbols/categories', categoryList.value) as any
    ElMessage.success('分类配置已保存')
    categoryDialogVisible.value = false
  } catch (e: any) {
    ElMessage.error(e?.message || '保存分类配置失败')
  } finally {
    categoryLoading.value = false
  }
}

const handlePageChange = (page: number) => {
  queryParams.value.page = page - 1
  loadSymbols()
}

const handleAdd = async () => {
  await loadCategoryConfig()
  catalogVisible.value = true
}

const handleEdit = (row: any) => {
  dialogTitle.value = '编辑币种'
  formData.value = { ...row, maxLeverage: row.maxLeverage ?? 100 }
  dialogVisible.value = true
}

const handleSave = async () => {
  try {
    await request.post('/admin/symbols/update', formData.value)
    ElMessage.success('更新成功')
    dialogVisible.value = false
    loadSymbols()
  } catch (e: any) {
    ElMessage.error(e?.message || '保存失败')
  }
}

const handleDelete = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确定删除币种 "${row.name}" 吗？`, '提示', {
      type: 'warning',
    })
    await request.post(`/admin/symbols/delete/${row.id}`)
    ElMessage.success('删除成功')
    loadSymbols()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e?.message || '删除失败')
    }
  }
}

const handleToggleHot = async (row: any) => {
  try {
    await request.post(`/admin/symbols/toggleHot/${row.id}`)
    ElMessage.success('热门状态更新成功')
    loadSymbols()
  } catch (e: any) {
    ElMessage.error(e?.message || '更新失败')
  }
}

// 图片上传前验证
function beforeUpload(file: File) {
  const isImage = file.type.startsWith('image/')
  const isLt5M = file.size / 1024 / 1024 < 5

  if (!isImage) {
    ElMessage.error('只能上传图片文件!')
    return false
  }
  if (!isLt5M) {
    ElMessage.error('图片大小不能超过5MB!')
    return false
  }
  return true
}

// 图标上传处理
async function handleIconUpload(options: any) {
  try {
    const uploadFormData = new FormData()
    uploadFormData.append('file', options.file)
    
    const res: any = await request.post('/upload/image', uploadFormData, {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    })
    
    console.log('上传响应:', res)
    
    // 尝试多种可能的响应格式
    const imageUrl = res.url || res.data?.url || res.data
    if (imageUrl) {
      formData.value.iconUrl = imageUrl
      console.log('图标URL已设置:', imageUrl)
      ElMessage.success('图标上传成功')
    } else {
      console.error('无法获取图片URL，响应:', res)
      ElMessage.error('图标上传失败：无法获取图片URL')
    }
  } catch (e: any) {
    console.error('图标上传失败:', e)
    ElMessage.error(e.message || '图标上传失败')
  }
}

// 定时刷新价格数据
let refreshTimer: number | null = null

onMounted(() => {
  loadSymbols()
  loadPermissions()
  loadCategoryConfig()
  // 每30秒自动刷新价格数据
  refreshTimer = window.setInterval(() => {
    loadSymbols()
  }, 30000)
})

onUnmounted(() => {
  if (refreshTimer !== null) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
})
</script>

<template>
  <div class="symbols-page">
    <el-card shadow="never">
      <!-- 搜索栏 -->
      <el-form :inline="true" :model="queryParams">
        <el-form-item label="分类">
          <el-select v-model="queryParams.category" placeholder="全部" clearable style="width: 120px">
            <el-option v-for="cat in categories" :key="cat.value" :label="cat.label" :value="cat.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
          <el-button type="success" :icon="Plus" :disabled="!canEditSymbol" @click="handleAdd">新增币种</el-button>
          <el-button type="warning" :icon="List" @click="openCategoryDialog">分类管理</el-button>
          <el-button type="info" @click="openLeverageDialog">合约杠杆上限</el-button>
        </el-form-item>
      </el-form>

      <!-- 表格 -->
      <el-table
        :data="symbols"
        v-loading="loading"
        stripe
        border
        style="margin-top: 16px"
      >
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="symbol" label="交易对" min-width="170"><template #default="{ row }"><span style="display:flex;align-items:center;gap:8px"><img v-if="row.iconUrl" :src="getImageUrl(row.iconUrl)" alt="" width="28" height="28" />{{ row.symbol }}</span></template></el-table-column>
        <el-table-column prop="name" label="名称" min-width="150" />
        <el-table-column prop="category" label="项目分类" width="100" />
        <el-table-column prop="marketSource" label="行情源" width="100" />
        <el-table-column prop="sourceCategory" label="源分类" width="100" />
        <el-table-column prop="currentPrice" label="当前价格" width="120">
          <template #default="{ row }">
            {{ row.currentPrice || '0.00' }}
          </template>
        </el-table-column>
        <el-table-column prop="priceChangePct24h" label="24h涨跌" width="100">
          <template #default="{ row }">
            <span :style="{ color: (row.priceChangePct24h || 0) >= 0 ? '#2abf4b' : '#e25d4d' }">
              {{ (row.priceChangePct24h || 0) >= 0 ? '+' : '' }}{{ row.priceChangePct24h || '0.00' }}%
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="isHot" label="热门" width="80">
          <template #default="{ row }">
            <el-button
              link
              :icon="row.isHot ? StarFilled : Star"
              :type="row.isHot ? 'warning' : 'info'"
              @click="handleToggleHot(row)"
            >
              {{ row.isHot ? '是' : '否' }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column prop="isEnabled" label="启用" width="80">
          <template #default="{ row }">
            <el-tag :type="row.isEnabled ? 'success' : 'danger'">
              {{ row.isEnabled ? '是' : '否' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sortOrder" label="排序" width="80" />
        <el-table-column prop="maxLeverage" label="杠杆上限" width="100">
          <template #default="{ row }">
            <el-tag type="info">{{ categoryAllowsLeverage(row.category) ? `${row.maxLeverage ?? 100}x` : '无杠杆（1x）' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button 
              v-if="canEditSymbol"
              link 
              type="primary" 
              :icon="Edit" 
              @click="handleEdit(row)"
            >
              编辑
            </el-button>
            <el-button 
              v-if="canDeleteSymbol"
              link 
              type="danger" 
              :icon="Delete" 
              @click="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <el-pagination
        v-if="total > 0"
        style="margin-top: 20px; justify-content: flex-end"
        :current-page="queryParams.page + 1"
        :page-size="queryParams.size"
        :total="total"
        layout="total, prev, pager, next, jumper"
        @current-change="handlePageChange"
      />

      <el-empty v-if="!loading && symbols.length === 0" description="暂无数据" />
    </el-card>

    <!-- 新增/编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="600px">
      <el-form :model="formData" label-width="120px">
        <el-form-item label="交易对符号" required>
          <el-input v-model="formData.symbol" disabled />
        </el-form-item>
        <el-form-item label="基础货币" required>
          <el-input v-model="formData.baseCurrency" disabled />
        </el-form-item>
        <el-form-item label="计价货币" required>
          <el-input v-model="formData.quoteCurrency" disabled />
        </el-form-item>
        <el-form-item label="显示名称" required>
          <el-input v-model="formData.name" placeholder="如 比特币/美元" />
        </el-form-item>
        <el-form-item label="英文名称">
          <el-input v-model="formData.nameEn" placeholder="如 Bitcoin/USD" />
        </el-form-item>
        <el-form-item label="分类" required>
          <el-select v-model="formData.category" style="width: 100%">
            <el-option v-for="cat in categoryList" :key="cat.key" :label="cat.label" :value="cat.key" />
          </el-select>
        </el-form-item>
        <el-form-item label="币种图标">
          <div style="display: flex; gap: 12px; align-items: flex-start;">
            <el-upload
              :http-request="(options: any) => handleIconUpload(options)"
              :show-file-list="false"
              :before-upload="beforeUpload"
              accept="image/*"
            >
              <el-button size="small" type="primary">上传图标</el-button>
            </el-upload>
            <div v-if="formData.iconUrl" style="display: flex; align-items: center; gap: 8px;">
              <img :src="getImageUrl(formData.iconUrl)" style="width: 40px; height: 40px; border-radius: 4px; object-fit: cover;" />
              <el-button size="small" type="danger" @click="formData.iconUrl = ''">删除</el-button>
            </div>
          </div>
          <el-input
            v-model="formData.iconUrl"
            placeholder="或直接输入图标URL"
            style="margin-top: 8px;"
          />
        </el-form-item>
        <el-form-item label="源绑定">
          <span>{{ formData.marketSource }} / {{ formData.sourceCategory }} / {{ formData.alltickSymbol }}</span>
        </el-form-item>
        <el-form-item label="价格精度">
          <el-input-number v-model="formData.pricePrecision" :min="0" :max="8" />
        </el-form-item>
        <el-form-item label="数量精度">
          <el-input-number v-model="formData.volumePrecision" :min="0" :max="8" />
        </el-form-item>
        <el-form-item label="最小交易数量">
          <el-input-number v-model="formData.minTradeAmount" :precision="8" :step="0.00000001" />
        </el-form-item>
        <el-form-item label="排序权重">
          <el-input-number v-model="formData.sortOrder" />
        </el-form-item>
        <el-form-item label="是否热门">
          <el-switch v-model="formData.isHot" />
        </el-form-item>
        <el-form-item label="是否启用">
          <el-switch v-model="formData.isEnabled" />
        </el-form-item>
        <el-divider>合约设置</el-divider>
        <el-form-item label="每手数量">
          <el-input-number v-model="formData.lotSize" :min="1" :precision="0" style="width: 100%" />
          <div style="font-size: 12px; color: #999; margin-top: 4px">每手数量；保证金 = 手数 × 每手数量 × 价格 ÷ 杠杆</div>
        </el-form-item>
        <el-form-item label="手续费倍数">
          <el-input-number v-model="formData.feeMultiplier" :min="0" :precision="2" style="width: 100%" />
          <div style="font-size: 12px; color: #999; margin-top: 4px">手续费倍数，用于计算预估手续费（买入数量 × 手续费倍数）</div>
        </el-form-item>
        <el-form-item label="杠杆上限">
          <el-input-number :disabled="!categoryAllowsLeverage(formData.category)" v-model="formData.maxLeverage" :min="1" :max="100" :precision="0" style="width: 100%" />
          <div style="font-size: 12px; color: #999; margin-top: 4px">用户下单可选1至该上限，默认100倍；降低上限后按上限默认，不影响已有订单</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>

  <!-- 杠杆设置对话框 -->
  <el-dialog v-model="leverageDialogVisible" title="合约杠杆上限设置" width="500px">
    <el-form :model="leverageForm" label-width="120px">
      <el-form-item label="杠杆上限" required>
        <el-input-number 
          v-model="leverageForm.leverage" 
          :min="1" 
          :max="100" 
          :precision="0" 
          style="width: 100%" 
        />
        <div style="font-size: 12px; color: #999; margin-top: 4px">设置用户可选杠杆上限（1-100倍），仅影响新订单</div>
      </el-form-item>
      <el-form-item label="应用到" required>
        <el-radio-group v-model="leverageForm.applyTo">
          <el-radio label="category">按分类</el-radio>
          <el-radio label="all">全部币种</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="leverageForm.applyTo === 'category'" label="选择分类">
        <el-select v-model="leverageForm.category" placeholder="选择分类" style="width: 100%">
          <el-option v-for="cat in categories.filter(c => c.value)" :key="cat.value" :label="cat.label" :value="cat.value" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="leverageDialogVisible = false">取消</el-button>
      <el-button type="primary" @click="saveLeverageSettings" :loading="leverageLoading">保存</el-button>
    </template>
  </el-dialog>

  <!-- 分类管理对话框 -->
  <el-dialog v-model="categoryDialogVisible" title="项目分类与源分类绑定" width="min(1000px, 96vw)">
    <el-alert title="绑定用于新增交易对时自动选择项目分类；手动选择可覆盖。修改绑定不迁移已添加交易对的行情源。关闭杠杆后新单固定1倍，已有持仓不变，高杠杆挂单暂停成交、仍可撤单。" type="info" :closable="false" style="margin-bottom: 16px" />
    <el-table :data="categoryList" v-loading="categoryLoading" border stripe>
      <el-table-column prop="key" label="分类Key" width="120" />
      <el-table-column prop="label" label="显示名称" min-width="140">
        <template #default="{ row }">
          <el-input v-model="row.label" placeholder="显示名称" />
        </template>
      </el-table-column>
      <el-table-column label="行情源" width="150">
        <template #default="{ row }">
          <el-select v-model="row.marketSource" @change="row.sourceCategory = sourceCategories(row.marketSource)[0]?.value">
            <el-option v-for="source in sourceOptions" :key="source.value" :value="source.value" :label="source.label" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="源分类" min-width="210">
        <template #default="{ row }">
          <el-select v-model="row.sourceCategory">
            <el-option v-for="cat in sourceCategories(row.marketSource)" :key="cat.value" :value="cat.value" :label="cat.label" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column prop="sortOrder" label="排序" width="160">
        <template #default="{ row }">
          <el-input-number v-model="row.sortOrder" :min="0" :max="999" />
        </template>
      </el-table-column>
      <el-table-column label="允许杠杆" width="110">
        <template #default="{ row }">
          <el-switch v-model="row.leverageEnabled" :aria-label="`${row.key}允许杠杆`" />
        </template>
      </el-table-column>
      <el-table-column prop="enabled" label="首页显示" width="100">
        <template #default="{ row }">
          <el-switch v-model="row.enabled" />
        </template>
      </el-table-column>
    </el-table>
    <template #footer>
      <span class="dialog-footer">
        <el-button @click="categoryDialogVisible = false">取 消</el-button>
        <el-button type="primary" @click="saveCategoryConfig" :loading="categoryLoading">保 存</el-button>
      </span>
    </template>
  </el-dialog>
  <SymbolCatalogDialog v-model="catalogVisible" :sources="sourceOptions" :categories="categoryList" @added="loadSymbols" />
</template>

<style scoped>
.symbols-page {
  padding: 0;
}
</style>

