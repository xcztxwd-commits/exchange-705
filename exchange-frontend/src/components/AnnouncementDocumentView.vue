<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import type { AnnouncementDocument } from '../utils/announcementDesign'

type AnnouncementAction = 'read' | 'close' | 'link'
const props = defineProps<{ document: AnnouncementDocument; busy?: boolean; runAction?: (action: AnnouncementAction, target?: string) => Promise<void> }>()
const emit = defineEmits<{ (e: 'action', action: AnnouncementAction, target?: string): void }>()
const running = ref(false), error = ref('')
let alive = true
onBeforeUnmount(() => { alive = false })

async function perform(actions: { type: AnnouncementAction; target?: string }[]) {
  if (props.busy || running.value) return
  running.value = true
  error.value = ''
  try {
    for (const action of actions) {
      if (!alive) return
      if (props.runAction) await props.runAction(action.type, action.target)
      else emit('action', action.type, action.target)
    }
  } catch (cause) {
    if (alive) error.value = cause instanceof Error ? cause.message : String(cause)
  } finally { running.value = false }
}
</script>

<template>
  <section class="announcement-document">
    <header class="announcement-header">
      <span class="announcement-mark" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="m4 10 13-5v14L4 14zM4 10H2v4h2M6 15l1 5h4l-2-4M20 8l2-1m-2 9 2 1m-2-5h2"/></svg></span>
      <strong :title="document.label">{{ document.label }}</strong>
      <button class="announcement-close" type="button" :aria-label="document.acknowledgeLabel" :disabled="busy || running" @click="perform([{ type: 'close' }])"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg></button>
    </header>
    <div class="announcement-content" tabindex="0">
      <div class="announcement-hero">
        <div class="announcement-milestone"><strong>{{ document.milestone }}</strong><span>{{ document.milestoneLabel }}</span></div>
        <h1 id="activity-title">{{ document.title }}</h1>
        <p class="announcement-subtitle">{{ document.subtitle }}</p>
        <p class="announcement-period"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" aria-hidden="true"><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M7 3v4m10-4v4M3 11h18"/></svg><span>{{ document.period }}</span></p>
      </div>
      <p class="announcement-greeting">{{ document.greeting }}</p>
      <p class="announcement-intro">{{ document.intro }}</p>
      <table class="announcement-rewards" :aria-label="document.rewardHeading">
        <thead><tr><th scope="col">{{ document.depositHeading }}</th><th scope="col">{{ document.rewardHeading }}</th></tr></thead>
        <tbody><tr v-for="(tier, index) in document.tiers" :key="index"><td>{{ tier.deposit }}<small>{{ document.currency }}</small></td><td>{{ tier.reward }}<small>{{ document.currency }}</small></td></tr></tbody>
      </table>
      <p class="announcement-currency">{{ document.currencyNote }}</p>
      <section class="announcement-rules" :aria-label="document.rulesHeading">
        <h3>{{ document.rulesHeading }}</h3>
        <div class="announcement-rule"><span aria-hidden="true">01</span><p>{{ document.tierRule }}</p></div>
        <aside class="announcement-withdrawal" :aria-label="document.withdrawalHeading">
          <h4>{{ document.withdrawalHeading }}</h4><p>{{ document.withdrawalRule }}</p><p><strong>{{ document.withdrawalRights }}</strong></p>
        </aside>
        <div class="announcement-rule"><span aria-hidden="true">02</span><p>{{ document.tradingRule }}</p></div>
        <div class="announcement-rule"><span aria-hidden="true">03</span><p>{{ document.durationRule }}</p></div>
        <div class="announcement-rule"><span aria-hidden="true">04</span><p>{{ document.supportRule }}</p></div>
      </section>
      <p class="announcement-signoff">{{ document.signoff }}</p>
    </div>
    <footer class="announcement-footer">
      <p class="announcement-footnote">{{ document.footerNote }}</p>
      <div class="announcement-actions">
        <button type="button" class="announcement-secondary" :disabled="busy || running" @click="perform([{ type: 'read' }, { type: 'link', target: '/customer-service' }])">{{ document.supportLabel }}</button>
        <button type="button" class="announcement-primary" :disabled="busy || running" @click="perform([{ type: 'read' }, { type: 'close' }])">{{ document.acknowledgeLabel }}</button>
      </div>
      <p v-if="error" class="announcement-error" role="alert">{{ error }}</p>
    </footer>
  </section>
</template>

<style scoped>
.announcement-document { container-type: inline-size; display: flex; flex-direction: column; height: var(--announcement-height, min(940px, calc(100dvh - 48px))); max-height: inherit; min-height: 0; width: 100%; overflow: hidden; color: #283126; background: #fff; font: 13px/1.6 -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", "Hiragino Kaku Gothic ProN", "Yu Gothic", sans-serif; }
.announcement-document, .announcement-document * { box-sizing: border-box; }
.announcement-document { overflow-wrap: anywhere; }
.announcement-header > strong { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.announcement-footer { max-height: 45%; overflow: auto; }
.announcement-actions { flex-shrink: 1 !important; min-width: 0; max-width: 100%; }
.announcement-actions > button { min-width: 0; overflow-wrap: anywhere; white-space: normal; }
.announcement-document button { font: inherit; cursor: pointer; }
.announcement-document button:disabled { opacity: .55; cursor: not-allowed; }
.announcement-document button:focus-visible, .announcement-content:focus-visible { outline: 3px solid #75a937; outline-offset: -3px; }
.announcement-header { display: flex; align-items: center; gap: 10px; flex-shrink: 0; min-height: 66px; padding: 12px 24px; border-bottom: 1px solid #e6ebe2; }
.announcement-header > strong { font-size: 13px; font-weight: 650; }
.announcement-mark { display: grid; place-items: center; width: 30px; height: 30px; border-radius: 8px; background: #edf4e5; color: #6d983e; }
.announcement-mark svg { width: 18px; height: 18px; }
.announcement-close { display: grid; place-items: center; flex-shrink: 0; width: 36px; height: 36px; margin-left: auto; padding: 7px; border: 0; border-radius: 50%; background: transparent; color: #788170; }
.announcement-close:hover { background: #f0f4eb; }
.announcement-close svg { width: 20px; height: 20px; }
.announcement-content { flex: 1; min-height: 0; padding: 0 38px 25px; overflow-y: auto; overscroll-behavior: contain; scrollbar-width: thin; scrollbar-color: #cddac1 #f7f9f5; }
.announcement-hero { position: relative; margin: 0 -38px 25px; padding: 28px 38px 24px; overflow: hidden; background: linear-gradient(115deg, #f3f8ec, #f9fbf4); }
.announcement-hero::after { content: ''; position: absolute; right: -75px; top: -165px; width: 360px; height: 360px; border: 1px solid #e1ebd5; border-radius: 50%; box-shadow: 0 0 0 38px #e7efdc55, 0 0 0 78px #e7efdc38; pointer-events: none; }
.announcement-hero > * { position: relative; z-index: 1; }
.announcement-milestone { display: flex; align-items: baseline; gap: 9px; color: #6a8453; font-size: 12px; }
.announcement-milestone strong { font-size: 23px; line-height: 1; color: #769c4e; font-weight: 750; font-variant-numeric: tabular-nums; letter-spacing: -1px; }
.announcement-hero h1 { margin: 15px 0 5px; font-size: 32px; line-height: 1.3; letter-spacing: -.8px; font-weight: 750; color: #283b1c; }
.announcement-subtitle { margin: 0; color: #64804d; font-size: 16px; line-height: 1.6; }
.announcement-period { display: flex; align-items: flex-start; gap: 8px; margin: 18px 0 0; color: #657458; font-size: 12px; line-height: 1.6; }
.announcement-period svg { flex-shrink: 0; width: 15px; height: 15px; margin-top: 2px; }
.announcement-greeting { margin: 0 0 10px; font-size: 14px; font-weight: 650; white-space: pre-wrap; }
.announcement-intro { margin: 0; color: #687260; font-size: 13px; line-height: 1.9; white-space: pre-wrap; }
.announcement-rewards { width: 100%; margin: 22px 0 10px; border: 1px solid #e0e9d7; border-collapse: separate; border-spacing: 0; border-radius: 12px; overflow: hidden; table-layout: fixed; }
.announcement-rewards th { padding: 12px 20px; background: #eff5e7; color: #60764d; font-size: 12px; font-weight: 600; text-align: left; line-height: 1.6; }
.announcement-rewards th:last-child, .announcement-rewards td:last-child { text-align: right; }
.announcement-rewards td { padding: 13px 20px; border-top: 1px solid #eaf0e5; color: #4b5841; font-size: 16px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.announcement-rewards td:last-child { color: #557f2c; font-size: 20px; font-weight: 750; letter-spacing: -.4px; }
.announcement-rewards tr:last-child td { background: #f7faef; }
.announcement-rewards small { margin-left: 5px; font-size: 10px; font-weight: 500; letter-spacing: 0; color: #64705a; }
.announcement-currency { margin: 0 0 24px; text-align: right; color: #697461; font-size: 11px; }
.announcement-rules { margin-top: 24px; }
.announcement-rules h3 { margin: 0 0 14px; color: #36442d; font-size: 14px; font-weight: 700; }
.announcement-rule { display: grid; grid-template-columns: 25px 1fr; gap: 10px; margin-bottom: 15px; }
.announcement-rule > span { padding-top: 2px; color: #8eaa71; font-size: 11px; font-weight: 700; }
.announcement-rule p { margin: 0; color: #6c7565; font-size: 12px; line-height: 1.9; white-space: pre-wrap; }
.announcement-withdrawal { margin: 18px 0; padding: 16px 18px; border-left: 3px solid #c9a25c; border-radius: 0 8px 8px 0; background: #faf7f0; }
.announcement-withdrawal h4 { margin: 0 0 6px; color: #91733c; font-size: 12px; }
.announcement-withdrawal p { margin: 0; color: #7b715e; font-size: 12px; line-height: 1.9; white-space: pre-wrap; }
.announcement-withdrawal strong { font-weight: 650; color: #6f633f; }
.announcement-signoff { margin: 22px 0 0; color: #707a68; font-size: 12px; line-height: 1.9; white-space: pre-wrap; }
.announcement-footer { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 10px 20px; flex-shrink: 0; padding: 17px 28px; border-top: 1px solid #e6ebe2; background: #fff; }
.announcement-footnote { margin: 0; max-width: 270px; font-size: 11px; line-height: 1.7; color: #68715e; }
.announcement-actions { display: flex; gap: 10px; flex-shrink: 0; }
.announcement-primary, .announcement-secondary { min-height: 46px; padding: 12px 23px; border: 1px solid transparent; border-radius: 9px; font-size: 14px !important; font-weight: 650 !important; }
.announcement-primary { background: #81b539; color: #17240c; }
.announcement-primary:hover { background: #91c54a; }
.announcement-secondary { background: #fff; border-color: #dce4d5; color: #516444; }
.announcement-secondary:hover { background: #f5f8f1; }
.announcement-error { flex-basis: 100%; margin: 0; color: #a34433; font-size: 12px; line-height: 1.5; }
@container (max-width: 600px) {
  .announcement-header { min-height: 60px; padding: 10px 16px; gap: 7px; }
  .announcement-mark { width: 27px; height: 27px; }
  .announcement-header > strong { font-size: 12px; }
  .announcement-close { width: 30px; height: 34px; }
  .announcement-content { padding: 0 22px 23px; }
  .announcement-hero { margin: 0 -22px 23px; padding: 25px 22px 23px; }
  .announcement-milestone { font-size: 10px; }
  .announcement-milestone strong { font-size: 20px; }
  .announcement-hero h1 { margin-top: 14px; font-size: 26px; }
  .announcement-subtitle { font-size: 14px; }
  .announcement-period { margin-top: 14px; font-size: 10px; }
  .announcement-intro { font-size: 12px; }
  .announcement-rewards { margin-top: 18px; }
  .announcement-rewards th { padding: 10px 12px; font-size: 11px; }
  .announcement-rewards td { padding: 12px; font-size: 14px; }
  .announcement-rewards td:last-child { font-size: 19px; }
  .announcement-rewards small { display: block; margin: 2px 0 0; font-size: 9px; }
  .announcement-footer { flex-direction: column-reverse; flex-wrap: nowrap; gap: 9px; padding: 13px 18px max(12px, env(safe-area-inset-bottom)); }
  .announcement-actions { width: 100%; }
  .announcement-actions button { flex: 1; min-width: 0; padding: 12px 8px; font-size: 12px !important; }
  .announcement-footnote { max-width: none; text-align: center; font-size: 10px; }
}
@container (max-width: 350px) {
  .announcement-header { padding-right: 9px; padding-left: 12px; }
  .announcement-mark { display: none; }
  .announcement-hero h1 { font-size: 23px; }
  .announcement-content { padding-right: 18px; padding-left: 18px; }
  .announcement-hero { margin-right: -18px; margin-left: -18px; padding-right: 18px; padding-left: 18px; }
}
</style>
