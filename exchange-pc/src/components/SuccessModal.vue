<script setup lang="ts">
import { useRouter } from 'vue-router'

const props = defineProps<{
  visible: boolean
  message?: string
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'confirm'): void
}>()

const router = useRouter()

function handleClose() {
  emit('update:visible', false)
}

function handleConfirm() {
  emit('confirm')
  emit('update:visible', false)
}

function goToOrders() {
  router.push('/orders')
  emit('update:visible', false)
}
</script>

<template>
  <div v-if="visible" class="modal-overlay" @click="handleClose">
    <div class="modal-content" @click.stop>
      <!-- 关闭按钮 -->
      <button class="close-btn" @click="handleClose">×</button>
      
      <!-- 成功图标 -->
      <div class="success-icon">
        <svg viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="80" height="80">
          <path d="M512 64C264.6 64 64 264.6 64 512s200.6 448 448 448 448-200.6 448-448S759.4 64 512 64zm193.5 301.7l-210.6 292a31.8 31.8 0 0 1-51.7 0L318.5 484.9c-3.8-5.3 0-12.7 6.5-12.7h46.9c10.2 0 19.9 4.9 25.9 13.3l71.2 98.8 157.2-218c6-8.3 15.6-13.3 25.9-13.3H699c6.5 0 10.3 7.4 6.5 12.7z" fill="#73b100"/>
        </svg>
      </div>
      
      <!-- 确认消息 -->
      <div class="modal-message">
        {{ message || '您的訂單已確認' }}
      </div>
      
      <!-- 操作按钮 -->
      <div class="modal-buttons">
        <button class="btn-secondary" @click="goToOrders">
          訂單頁面
        </button>
        <button class="btn-primary" @click="handleConfirm">
          確認
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal-content {
  background: #fff;
  border-radius: 16px;
  padding: 32px 24px 24px;
  width: 320px;
  max-width: 90%;
  position: relative;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.2);
}

.close-btn {
  position: absolute;
  top: 12px;
  right: 12px;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #f0f0f0;
  border: none;
  font-size: 20px;
  color: #666;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
}

.close-btn:hover {
  background: #e0e0e0;
}

.success-icon {
  display: flex;
  justify-content: center;
  margin-bottom: 20px;
}

.modal-message {
  text-align: center;
  font-size: 16px;
  color: #000;
  margin-bottom: 24px;
  font-weight: 500;
}

.modal-buttons {
  display: flex;
  gap: 12px;
}

.btn-primary,
.btn-secondary {
  flex: 1;
  padding: 12px 24px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  border: none;
}

.btn-primary {
  background: #73b100;
  color: #fff;
}

.btn-primary:hover {
  background: #5a8a00;
}

.btn-primary:active {
  transform: scale(0.98);
}

.btn-secondary {
  background: #fff;
  color: #73b100;
  border: 1px solid #73b100;
}

.btn-secondary:hover {
  background: #f5f5f5;
}

.btn-secondary:active {
  transform: scale(0.98);
}
</style>



