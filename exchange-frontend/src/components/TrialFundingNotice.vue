<script setup lang="ts">
defineProps<{ title: string; description: string }>()
</script>

<template>
  <aside class="trial-funding-notice" role="status" data-testid="trial-funding-notice">
    <svg class="trial-funding-notice__icon" viewBox="0 0 20 20" fill="none" aria-hidden="true">
      <circle cx="10" cy="10" r="7.25" />
      <path d="M10 9v4m0-6.5v.25" />
    </svg>
    <span class="trial-funding-notice__copy">
      <strong>{{ title }}</strong>
      <span>{{ description }}</span>
    </span>
  </aside>
</template>

<style scoped>
.trial-funding-notice {
  box-sizing: border-box;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
  margin: 8px 0;
  padding: 10px 12px;
  border: 1px solid #eed8a8;
  border-radius: 8px;
  background: #fff8e8;
  color: #805315;
  font-family: inherit;
  line-height: 1.5;
  text-align: start;
  overflow-wrap: anywhere;
}
.trial-funding-notice__icon {
  flex: none;
  width: 16px;
  height: 16px;
  margin-top: 1px;
  stroke: #ad791f;
  stroke-width: 1.5;
  stroke-linecap: round;
}
.trial-funding-notice__copy { display: block; min-width: 0; }
.trial-funding-notice__copy strong { display: block; font-size: 12px; font-weight: 600; }
.trial-funding-notice__copy > span { display: block; margin-top: 3px; font-size: 11px; font-weight: 400; color: #89662e; }
:global(.dark .trial-funding-notice) { border-color: #65512d; background: #30291b; color: #f1cd8b; }
:global(.dark .trial-funding-notice__icon) { stroke: #d7ac5b; }
:global(.dark .trial-funding-notice__copy > span) { color: #d6b781; }
</style>
