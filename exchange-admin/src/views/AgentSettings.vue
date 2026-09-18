<script setup lang="ts">
import { ref, reactive, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import request from '@/utils/request'

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'updated': []
}>()

const router = useRouter()
const auth = useAuthStore()

// 对话框显示状态
const dialogVisible = ref(false)

watch(() => props.modelValue, (val) => {
  dialogVisible.value = val
  if (val) {
    // 打开对话框时，初始化表单数据
    accountForm.email = auth.user?.email || ''
    passwordForm.oldPassword = ''
    passwordForm.newPassword = ''
    passwordForm.confirmPassword = ''
  }
})

watch(dialogVisible, (val) => {
  emit('update:modelValue', val)
})

// 更改账户名表单（代理使用email作为账户名）
const accountForm = reactive({
  email: ''
})

const accountLoading = ref(false)

// 修改密码表单
const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const passwordLoading = ref(false)

// 账户名表单验证规则
const accountRules = {
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '请输入有效的邮箱地址', trigger: 'blur' }
  ]
}

// 密码表单验证规则
const passwordRules = {
  oldPassword: [
    { required: true, message: '请输入当前密码', trigger: 'blur' }
  ],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '密码长度不能少于6个字符', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请确认新密码', trigger: 'blur' },
    {
      validator: (rule: any, value: string, callback: Function) => {
        if (value !== passwordForm.newPassword) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

const accountFormRef = ref()
const passwordFormRef = ref()
const activeTab = ref('account')

// 更新账户名（邮箱）
const handleUpdateAccount = async () => {
  if (!accountFormRef.value) return
  
  await accountFormRef.value.validate(async (valid: boolean) => {
    if (!valid) return
    
    if (accountForm.email === auth.user?.email) {
      ElMessage.warning('邮箱未发生变化')
      return
    }
    
    accountLoading.value = true
    try {
      const res: any = await request.put('/admin/auth/agent/profile/email', {
        email: accountForm.email
      })
      
      if (res && res.success !== false) {
        ElMessage.success('邮箱更新成功，请重新登录')
        // 关闭对话框
        dialogVisible.value = false
        // 延迟退出登录，让用户看到成功消息
        setTimeout(() => {
          auth.logout()
          router.replace('/login')
        }, 1000)
      } else {
        ElMessage.error(res.message || '更新失败')
      }
    } catch (e: any) {
      ElMessage.error(e?.message || e?.response?.data?.message || '更新失败')
    } finally {
      accountLoading.value = false
    }
  })
}

// 修改密码
const handleChangePassword = async () => {
  if (!passwordFormRef.value) return
  
  await passwordFormRef.value.validate(async (valid: boolean) => {
    if (!valid) return
    
    passwordLoading.value = true
    try {
      const res: any = await request.put('/admin/auth/agent/profile/password', {
        oldPassword: passwordForm.oldPassword,
        newPassword: passwordForm.newPassword
      })
      
      if (res && res.success !== false) {
        ElMessage.success('密码修改成功，请重新登录')
        // 关闭对话框
        dialogVisible.value = false
        // 延迟退出登录，让用户看到成功消息
        setTimeout(() => {
          auth.logout()
          router.replace('/login')
        }, 1000)
      } else {
        ElMessage.error(res.message || '修改失败')
      }
    } catch (e: any) {
      ElMessage.error(e?.message || e?.response?.data?.message || '修改失败')
    } finally {
      passwordLoading.value = false
    }
  })
}

// 关闭对话框
const handleClose = () => {
  dialogVisible.value = false
}
</script>

<template>
  <el-dialog
    v-model="dialogVisible"
    title="代理设置"
    width="500px"
    @close="handleClose"
  >
    <el-tabs v-model="activeTab">
      <!-- 更改邮箱 -->
      <el-tab-pane label="更改邮箱" name="account">
        <el-form
          ref="accountFormRef"
          :model="accountForm"
          :rules="accountRules"
          label-width="120px"
          style="margin-top: 20px"
        >
          <el-form-item label="当前邮箱">
            <span style="color: #909399">{{ auth.user?.email || '-' }}</span>
          </el-form-item>
          <el-form-item label="新邮箱" prop="email">
            <el-input
              v-model="accountForm.email"
              placeholder="请输入新的邮箱地址"
              maxlength="128"
              clearable
            >
              <template #prefix>
                <el-icon><User /></el-icon>
              </template>
            </el-input>
          </el-form-item>
          <el-form-item>
            <el-alert
              title="提示：邮箱将作为登录账户名使用"
              type="info"
              :closable="false"
              show-icon
            />
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- 修改密码 -->
      <el-tab-pane label="修改密码" name="password">
        <el-form
          ref="passwordFormRef"
          :model="passwordForm"
          :rules="passwordRules"
          label-width="120px"
          style="margin-top: 20px"
        >
          <el-form-item label="当前密码" prop="oldPassword">
            <el-input
              v-model="passwordForm.oldPassword"
              type="password"
              placeholder="请输入当前密码"
              show-password
              clearable
            >
              <template #prefix>
                <el-icon><Lock /></el-icon>
              </template>
            </el-input>
          </el-form-item>
          <el-form-item label="新密码" prop="newPassword">
            <el-input
              v-model="passwordForm.newPassword"
              type="password"
              placeholder="请输入新密码（至少6个字符）"
              show-password
              clearable
            >
              <template #prefix>
                <el-icon><Lock /></el-icon>
              </template>
            </el-input>
          </el-form-item>
          <el-form-item label="确认新密码" prop="confirmPassword">
            <el-input
              v-model="passwordForm.confirmPassword"
              type="password"
              placeholder="请再次输入新密码"
              show-password
              clearable
            >
              <template #prefix>
                <el-icon><Lock /></el-icon>
              </template>
            </el-input>
          </el-form-item>
          <el-form-item>
            <el-alert
              title="提示：密码长度不能少于6个字符"
              type="info"
              :closable="false"
              show-icon
            />
          </el-form-item>
        </el-form>
      </el-tab-pane>
    </el-tabs>
    
    <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleClose">取消</el-button>
        <el-button
          v-if="activeTab === 'account'"
          type="primary"
          :loading="accountLoading"
          @click="handleUpdateAccount"
        >
          保存
        </el-button>
        <el-button
          v-if="activeTab === 'password'"
          type="primary"
          :loading="passwordLoading"
          @click="handleChangePassword"
        >
          保存
        </el-button>
      </span>
    </template>
  </el-dialog>
</template>

<style scoped>
.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
</style>

