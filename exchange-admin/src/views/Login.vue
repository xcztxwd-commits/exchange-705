<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import request from '@/utils/request'

const router = useRouter()
const auth = useAuthStore()

const loginForm = reactive({
  account: '',
  password: '',
})

const loading = ref(false)

const onSubmit = async () => {
  if (loading.value) return
  
  if (!loginForm.account || !loginForm.password) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  
  loading.value = true
  try {
    const res: any = await request.post('/admin/auth/login', {
      account: loginForm.account,
      password: loginForm.password,
      loginType: 'email',
    })
    console.log('登录响应:', res)
    console.log('用户信息:', res.user)
    auth.setAuth(res.token, res.user)
    ElMessage.success('登录成功')
    // 延迟一下，确保 auth store 更新完成
    setTimeout(() => {
      router.replace('/dashboard')
    }, 100)
  } catch (e: any) {
    ElMessage.error(e?.message || '登录失败，请检查账号密码')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-box">
      <div class="login-header">
        <h2>Exchange 管理后台</h2>
        <p>交易所管理系统</p>
      </div>

      <el-form :model="loginForm" @submit.prevent="onSubmit">
        <el-form-item>
          <el-input
            v-model="loginForm.account"
            placeholder="输入账号或邮箱"
            size="large"
            clearable
          >
            <template #prefix>
              <el-icon><User /></el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item>
          <el-input
            v-model="loginForm.password"
            type="password"
            placeholder="输入密码"
            size="large"
            show-password
            @keyup.enter="onSubmit"
          >
            <template #prefix>
              <el-icon><Lock /></el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            size="large"
            :loading="loading"
            style="width: 100%"
            @click="onSubmit"
          >
            {{ loading ? '登录中...' : '登 录' }}
          </el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #ffffff 0%, #ffffff 100%);
}

.login-box {
  width: 400px;
  background: #fff;
  padding: 40px;
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
}

.login-header {
  text-align: center;
  margin-bottom: 30px;
}

.login-header h2 {
  margin: 0 0 10px;
  font-size: 24px;
  color: #303133;
}

.login-header p {
  margin: 0;
  font-size: 14px;
  color: #909399;
}
</style>
