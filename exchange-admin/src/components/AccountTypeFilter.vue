<script setup lang="ts">
import type { AccountMode } from '@/utils/accountTableData'
defineProps<{ modelValue: AccountMode[] }>()
const emit = defineEmits<{ (event: 'update:modelValue', value: AccountMode[]): void; (event: 'change'): void }>()
function update(value: any) { emit('update:modelValue', value); emit('change') }
</script>
<template>
  <div class="account-type-filter" style="display:flex;gap:16px;align-items:center;margin:12px 0;flex-wrap:wrap">
    <span>账户类型</span>
    <el-checkbox-group :model-value="modelValue" @update:model-value="update">
      <el-checkbox label="REAL">真实账户</el-checkbox><el-checkbox label="DEMO">模拟账户</el-checkbox>
    </el-checkbox-group>
    <small v-if="modelValue.includes('DEMO')">模拟数据为虚拟资金；按账户类型区分记录，当前筛选仅供查看。</small>
    <small v-if="!modelValue.length">未选择账户类型，不显示数据。</small>
  </div>
</template>
