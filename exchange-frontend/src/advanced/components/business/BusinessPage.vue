<script setup lang="ts">
import AdvancedLayout from '../AdvancedLayout.vue'
import { useLocaleStore } from '@/store/locale'
withDefaults(defineProps<{ title: string; error?: string; busy?: boolean; nav?: boolean }>(), { nav: true })
const locale = useLocaleStore()
</script>

<template>
  <AdvancedLayout :title="title" back :nav="nav !== false">
    <main class="advanced-business" :aria-busy="busy || undefined">
      <p v-if="error" class="business-error" role="alert">{{ error }}</p>
      <slot />
      <p class="business-status">{{ locale.text('数据与操作来自当前账户服务', 'Data and actions use the current account service') }}</p>
    </main>
  </AdvancedLayout>
</template>

<style scoped>
.advanced-business{display:flex;flex-direction:column;gap:20px;padding:16px 0;color:#252a30;font-size:14px;line-height:1.45;min-width:0;--business-primary:#d9e6c8;--business-primary-text:#2f4129;--business-line:#e9edef;--business-soft:#f5f6f7;--business-muted:#707780;--business-violet:#736582}
.advanced-business :deep(*){box-sizing:border-box;min-width:0}
.advanced-business :deep(h2),.advanced-business :deep(h3){font-size:17px;font-weight:500;margin:0}
.advanced-business :deep(p){margin:0}
.advanced-business :deep(.card){border:1px solid var(--business-line);border-radius:10px;padding:12px;display:flex;flex-direction:column;gap:12px;background:white}
.advanced-business :deep(.row){display:flex;justify-content:space-between;align-items:center;gap:8px;padding:12px 0;overflow-wrap:anywhere}
.advanced-business :deep(.row> :first-child){flex-shrink:0}
.advanced-business :deep(.row> select){width:auto;max-width:70%;border:0;background:transparent;padding:0;min-height:24px}
.advanced-business :deep(.row> :last-child){text-align:right;font-weight:500}
.advanced-business :deep(.metrics){display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}
.advanced-business :deep(.metrics.two){grid-template-columns:repeat(2,minmax(0,1fr))}
.advanced-business :deep(.metric){display:flex;flex-direction:column;gap:5px;overflow-wrap:anywhere;font-variant-numeric:tabular-nums}
.advanced-business :deep(.metric small),.advanced-business :deep(.muted){font-size:12px;color:var(--business-muted);font-weight:400}
.advanced-business :deep(.large){font-size:30px;font-weight:500;overflow-wrap:anywhere;font-variant-numeric:tabular-nums}
.advanced-business :deep(.field){display:flex;flex-direction:column;gap:8px;font-size:12px;color:var(--business-muted)}
.advanced-business :deep(input:not([type=checkbox]):not([type=file])),.advanced-business :deep(textarea),.advanced-business :deep(select){width:100%;min-height:50px;border:1px solid var(--business-line);border-radius:10px;background:var(--business-soft);color:#252a30;padding:12px 14px;font:inherit;font-size:14px;outline-offset:2px}
.advanced-business :deep(input:disabled),.advanced-business :deep(textarea:disabled){opacity:.65}
.advanced-business :deep(input[type=file]){max-width:100%;font-size:12px}
.advanced-business :deep(.input-row){display:flex;align-items:center;gap:8px;border:1px solid var(--business-line);border-radius:10px;background:var(--business-soft);padding:0 12px}
.advanced-business :deep(.input-row input:not([type=file])){border:0;padding:12px 0;flex:1;width:0;background:transparent}.advanced-business :deep(.input-row button){flex-shrink:0;white-space:nowrap}
.advanced-business :deep(button),.advanced-business :deep(.action){font:inherit;min-height:48px;border:0;border-radius:10px;cursor:pointer;background:var(--business-soft);color:#252a30;padding:10px 14px;text-align:center;text-decoration:none}
.advanced-business :deep(.primary){background:var(--business-primary);color:var(--business-primary-text);font-weight:500}
.advanced-business :deep(.text-button){background:transparent;padding:0 4px;color:var(--business-violet);font-size:12px}
.advanced-business :deep(button:disabled){opacity:.55;cursor:not-allowed}
.advanced-business :deep(.actions){display:flex;gap:8px}.advanced-business :deep(.actions>* ){flex:1}
.advanced-business :deep(button.row){background:transparent;border-radius:0;padding:12px 0;text-align:left;min-height:44px}
.advanced-business :deep(.field button.row){border:1px solid var(--business-line);border-radius:10px;padding:12px 14px;background:var(--business-soft);color:var(--business-muted);min-height:50px}
.advanced-business :deep(.tabs){display:flex;gap:6px}.advanced-business :deep(.tabs button){flex:1;min-height:44px;padding:8px 4px;font-size:12px;color:var(--business-muted);border-radius:7px}
.advanced-business :deep(.tabs .active){background:#f5f2f7;color:var(--business-violet)}
.advanced-business :deep(.notice){background:#f5f2f7;color:var(--business-muted);font-size:12px;border-radius:7px;padding:10px}
.advanced-business :deep(.upload){display:flex;flex-direction:column;align-items:center;gap:8px;background:var(--business-soft);border:1px solid var(--business-line);border-radius:10px;min-height:84px;padding:16px;color:var(--business-muted);cursor:pointer;font-size:12px}
.advanced-business :deep(.upload img){max-width:100%;object-fit:contain}
.advanced-business :deep(.divider){height:1px;background:var(--business-line);border:0;margin:0}
.advanced-business :deep(.empty){padding:28px 12px;text-align:center;color:var(--business-muted)}
.advanced-business :deep(.toast-message){position:fixed;z-index:1200;bottom:calc(90px + env(safe-area-inset-bottom));left:50%;transform:translateX(-50%);max-width:min(90%,380px);padding:12px 16px;background:#252a30;color:#fff;border-radius:10px;text-align:center}
.advanced-business :deep(.modal-overlay),.advanced-business :deep(.confirm-dialog-overlay),.advanced-business :deep(.redeem-dialog-overlay),.advanced-business :deep(.dialog-overlay){position:fixed;inset:0;background:#0006;z-index:1100;display:flex;align-items:flex-end;justify-content:center;padding:16px 16px max(16px,env(safe-area-inset-bottom))}
.advanced-business :deep(.modal-content),.advanced-business :deep(.confirm-dialog),.advanced-business :deep(.redeem-dialog),.advanced-business :deep(.dialog-content){width:min(100%,430px);background:#fff;border-radius:14px;padding:16px;max-height:80dvh;overflow-y:auto}
.advanced-business :deep(.modal-header),.advanced-business :deep(.dialog-footer){display:flex;align-items:center;justify-content:space-between;gap:8px;padding:12px 0}
.advanced-business :deep(.modal-item){min-height:48px;padding:14px;border-bottom:1px solid var(--business-line);cursor:pointer}
.advanced-business :deep(.modal-item.active){color:var(--business-violet);background:#f5f2f7}
.business-error{border:1px solid #eed2d2;border-radius:10px;background:#fff8f8;padding:12px;color:#9a3939;overflow-wrap:anywhere}
.business-status{font-size:12px;color:var(--business-muted)}
@media(max-width:350px){.advanced-business{padding:12px 0}.advanced-business :deep(.large){font-size:26px}}
</style>
