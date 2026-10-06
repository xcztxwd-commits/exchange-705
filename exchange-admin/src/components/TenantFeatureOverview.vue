<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ArrowRight, Coin, Connection, CreditCard, Headset, Link, Lock, Message, Monitor, Money, Present, Refresh, Timer, TrendCharts, User, Wallet } from '@element-plus/icons-vue'
import { useTenantPolicies } from '@/composables/useTenantPolicies'
import { useAuthStore } from '@/store/auth'

const { snapshot, policyError, reloadPolicies } = useTenantPolicies('dashboard')
const auth = useAuthStore(), loading = ref(false), filter = ref('all'), selected = ref<string | null>(null)
const features = [
  { key: 'registration', name: '用户注册', icon: User, summary: '开放新用户注册入口', details: ['允许新用户通过注册页面创建账户。', '图形验证码、注册限流及必要资料校验仍按网站安全配置执行。'] },
  { key: 'option', name: '期权交易', icon: Timer, summary: '按期限与方向进行交易', details: ['允许用户使用已配置的期权品种、期限与交易参数创建订单。', '已有订单按原有规则到期结算，历史订单仍可按账号权限查询。'] },
  { key: 'contract', name: '合约交易', icon: TrendCharts, summary: '杠杆交易与持仓管理', details: ['允许用户在已配置的合约品种上开仓，使用对应杠杆与保证金规则。', '未开放时限制新增开仓；已有持仓的平仓、结算与历史查询按原有规则办理。'] },
  { key: 'financial', name: '理财产品', icon: Coin, summary: '理财产品展示与申购', details: ['允许用户申购已上架的理财产品，按产品规则计算期限与收益。', '已有理财订单的到期处理及历史查询不因关闭新增授权而被删除。'] },
  { key: 'loan', name: '贷款服务', icon: CreditCard, summary: '申请、审核与贷款管理', details: ['允许开展贷款申请及相关新增业务，申请条件与额度仍由贷款方案决定。', '已有贷款的还款、记录查询等存量处理按原有规则办理。'] },
  { key: 'activity', name: '活动与福利', icon: Present, summary: '活动展示与福利领取', details: ['支持活动内容、定向发送及用户领取活动福利。', '开放授权不等于自动发布活动；实际展示仍取决于活动状态、发送对象及领取规则。'] },
  { key: 'simulation', name: '模拟交易', icon: Monitor, summary: '独立模拟环境体验', details: ['允许用户进入已配置的模拟交易环境，模拟账户与真实账户分开。', '模拟新增业务仍需对应交易功能授权与模拟环境配置；未开放时不影响已有模拟账户退出。'] },
  { key: 'deposit', name: '充值服务', icon: Wallet, summary: '充值申请与渠道使用', details: ['允许用户通过已配置的充值渠道创建充值申请。', '充值到账仍受渠道规则及审核流程约束，开放功能不会自动增加账户余额。'] },
  { key: 'withdraw', name: '提现服务', icon: Money, summary: '提现申请与审核流程', details: ['允许用户按配置的币种、额度与收款方式发起提现申请。', '可提现余额、手续费、风控与审核要求仍按现有规则校验。'] },
  { key: 'support', name: '站内客服', icon: Headset, summary: '在线咨询与会话接待', details: ['提供站内在线咨询、客服接待、回复及会话记录。', '还需将客服模式设为站内客服并分配客服账号权限；关闭后不能创建或回复会话，已有记录保留。'] },
  { key: 'external_support', name: '外部客服', icon: Link, summary: '跳转已配置的客服渠道', details: ['允许用户通过配置的外部客服链接联系服务人员。', '还需启用外部客服模式及有效链接；统一锁定为站内客服时不能改为外部客服。'] },
  { key: 'inbox', name: '站内信', icon: Message, summary: '消息发送与用户收信', details: ['支持向用户发送站内消息及相关消息提醒。', '还需开启站内信配置并具备发送权限；功能开放不会自动发送消息。'] },
  { key: 'agent', name: '代理业务', icon: Connection, summary: '代理开通与下级管理', details: ['允许开通代理身份及代理后台账号，管理其授权范围内的下级用户。', '代理可见客户与可执行操作仍由代理关系、角色授权及逐项权限共同决定。'] },
]
const enabled = (key: string) => snapshot.value?.features[key] === true
const opened = computed(() => features.filter(feature => enabled(feature.key)).length)
const visibleFeatures = computed(() => features.filter(feature => filter.value === 'all' || enabled(feature.key) === (filter.value === 'open')))
const detail = computed(() => features.find(feature => feature.key === selected.value))
const states: Record<string, string> = { DRAFT: '待启用', ACTIVE: '运行中', STOP_NEW: '停止新增业务', MAINTENANCE: '维护中', DISABLED: '已停用' }
const channelNames: Record<string, string> = { internal: '站内客服', external: '外部客服', off: '关闭客服' }
let generation = 0
async function load() {
  const run = ++generation
  selected.value = null; loading.value = true
  try { await reloadPolicies() } catch { /* The policy composable exposes the error without inventing authorization states. */ }
  finally { if (run === generation) loading.value = false }
}
watch(() => [auth.token, auth.user?.tenantId], () => { filter.value = 'all'; void load() }, { immediate: true })
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <el-card shadow="never" class="feature-overview" aria-label="功能权限概览">
    <template #header>
      <div class="feature-heading">
        <div><div class="feature-title"><el-icon><Lock /></el-icon><h2>功能权限</h2><span v-if="snapshot" class="system-status">{{ states[snapshot.status] || '状态未知' }}</span></div><p>查看已开放与未开放的功能，点击卡片了解详情。</p></div>
        <div class="feature-actions"><span v-if="snapshot" class="policy-version">授权版本 {{ snapshot.policyVersion }}</span><el-button v-permission="'dashboard:view'" :icon="Refresh" :loading="loading" @click="load">刷新</el-button></div>
      </div>
    </template>
    <el-skeleton v-if="loading" :rows="4" animated aria-label="正在加载功能权限" />
    <el-alert v-else-if="policyError || !snapshot" title="暂时无法读取功能权限" :description="policyError || '尚未确认当前系统的授权信息，请刷新重试。'" type="warning" :closable="false" />
    <template v-else>
      <el-alert v-if="snapshot.status !== 'ACTIVE'" :title="`当前状态：${states[snapshot.status] || '未知'}。已开放标记仅代表功能授权，新增业务仍受运行状态限制。`" type="warning" :closable="false" class="business-status" />
      <div class="feature-filters" aria-label="筛选功能开放状态">
        <button v-permission="'dashboard:view'" type="button" :aria-pressed="filter === 'all'" @click="filter = 'all'">全部功能 <span>{{ features.length }}</span></button>
        <button v-permission="'dashboard:view'" type="button" :aria-pressed="filter === 'open'" @click="filter = 'open'">已开放 <span>{{ opened }}</span></button>
        <button v-permission="'dashboard:view'" type="button" :aria-pressed="filter === 'closed'" @click="filter = 'closed'">未开放 <span>{{ features.length - opened }}</span></button>
      </div>
      <div class="feature-grid">
        <button v-for="feature in visibleFeatures" :key="feature.key" v-permission="'dashboard:view'" type="button" class="feature-card" :class="{ 'is-open': enabled(feature.key) }" :data-feature="feature.key" :aria-label="`${feature.name}，${enabled(feature.key) ? '已开放' : '未开放'}，查看功能详情`" @click="selected = feature.key">
          <div class="feature-card-top"><span class="feature-icon"><el-icon :size="21"><component :is="feature.icon" /></el-icon></span><span class="feature-state"><i />{{ enabled(feature.key) ? '已开放' : '未开放' }}</span></div>
          <h3>{{ feature.name }}</h3><p>{{ feature.summary }}</p><span class="feature-more">查看详情 <el-icon><ArrowRight /></el-icon></span>
        </button>
      </div>
      <p v-if="!visibleFeatures.length" class="feature-empty">{{ filter === 'open' ? '当前没有已开放的功能。' : '所有功能均已开放。' }}</p>
      <div class="feature-footer"><p>功能开放与账号权限分别管理；实际使用仍需满足业务配置，并由服务端校验。</p><p v-if="snapshot.supportChannel">客服渠道统一设置为：{{ channelNames[snapshot.supportChannel] || '未知' }}</p></div>
      <details class="config-scope">
        <summary>配置编辑范围 <span>{{ snapshot.configs.length ? `${snapshot.configs.length} 项配置规则` : '无单独锁定项' }}</span></summary>
        <ul v-if="snapshot.configs.length" class="config-list"><li v-for="config in snapshot.configs" :key="config.key"><code>{{ config.key }}</code><el-tag :type="config.denied ? 'danger' : config.locked ? 'warning' : 'success'" size="small">{{ config.denied ? '禁止修改' : config.locked ? '统一锁定' : '可配置' }}</el-tag><span>版本 {{ config.version }}</span></li></ul>
        <p v-else>当前没有单独锁定的配置项。</p><p>未单列的配置仍受功能开放及账号操作权限约束；此处不显示配置值或密钥。</p>
      </details>
    </template>
    <el-dialog :model-value="!!detail && !!snapshot" :title="`${detail?.name || ''} · 功能详情`" width="min(560px,92vw)" append-to-body destroy-on-close @update:model-value="selected = null">
      <div v-if="detail && snapshot" class="feature-detail">
        <div class="detail-status"><el-tag :type="enabled(detail.key) ? 'success' : 'info'">{{ enabled(detail.key) ? '已开放' : '未开放' }}</el-tag><span>{{ enabled(detail.key) ? '已取得此功能的系统授权' : '尚未取得此功能的系统授权' }}</span></div>
        <h3>功能说明</h3><ul><li v-for="line in detail.details" :key="line">{{ line }}</li></ul>
        <div class="detail-note"><strong>{{ enabled(detail.key) ? '使用条件' : '开放方式' }}</strong><p>{{ enabled(detail.key) ? '功能已开放，实际入口与操作仍取决于账号权限、业务配置及系统运行状态。' : '如需使用，请联系系统运营方开通。本页面只展示授权，不会通过点击卡片改变权限。' }}</p></div>
      </div>
      <template #footer><el-button v-permission="'dashboard:view'" type="primary" @click="selected = null">知道了</el-button></template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.feature-overview{border-color:#e6ebef;border-radius:16px;color:#283b42}
.feature-overview :deep(.el-card__header){padding:22px 24px;background:linear-gradient(110deg,#f6f9ef,#fff 70%);border-color:#edf0e7}
.feature-overview :deep(.el-card__body){padding:22px 24px}
.feature-heading,.feature-title,.feature-actions{display:flex;align-items:center;gap:12px}
.feature-heading{justify-content:space-between;flex-wrap:wrap}
.feature-title> .el-icon{color:#658c13;font-size:22px}
.feature-title h2{margin:0;font-size:20px;font-weight:650}
.feature-heading p{font-size:13px;color:#697c82;margin:8px 0 0}
.system-status{font-size:11px;color:#5e7252;border:1px solid #dfe8d8;border-radius:20px;padding:4px 8px;white-space:nowrap}
.policy-version{font-size:12px;color:#697c82;white-space:nowrap}
.business-status{margin-bottom:16px}
.feature-filters{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:18px}
.feature-filters button{border:1px solid #e6eaed;border-radius:8px;background:#fff;color:#6c7c82;padding:8px 12px;font:inherit;font-size:13px;cursor:pointer}
.feature-filters span{margin-left:5px;font-variant-numeric:tabular-nums}
.feature-filters button[aria-pressed=true]{background:#eef5df;border-color:#ccdfa7;color:#526e14;font-weight:600}
.feature-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}
.feature-card{display:flex;flex-direction:column;align-items:stretch;text-align:left;border:1px solid #e5eaee;border-radius:12px;background:#fafbfc;color:inherit;padding:16px;cursor:pointer;font:inherit;transition:border-color .15s,box-shadow .15s}
.feature-card.is-open{border-color:#d9e6c5;background:#fbfdf7}
.feature-card:hover{border-color:#9dbb60;box-shadow:0 4px 14px #3345200c}
.feature-card:focus-visible,.feature-filters button:focus-visible{outline:2px solid #85bd00;outline-offset:3px}
.feature-card-top{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:12px}
.feature-icon{display:inline-flex;align-items:center;justify-content:center;width:40px;height:40px;border-radius:10px;background:#edf0f3;color:#8b98a6}
.is-open .feature-icon{background:#eaf2d9;color:#6d951f}
.feature-state{display:flex;align-items:center;gap:5px;font-size:11px;color:#5f707b;background:#edf0f3;border-radius:20px;padding:4px 7px;white-space:nowrap}
.feature-state i{width:5px;height:5px;background:currentColor;border-radius:50%}
.is-open .feature-state{color:#52791b;background:#eef5df}
.feature-card h3{margin:0 0 6px;font-size:14px;font-weight:600}
.feature-card p{font-size:12px;color:#687b85;line-height:1.6;margin:0 0 16px}
.feature-more{display:flex;align-items:center;gap:5px;margin-top:auto;font-size:11px;color:#60777b}
.is-open .feature-more{color:#648b27}
.feature-footer{margin-top:18px;font-size:12px;line-height:1.8;color:#697b82}
.feature-empty{padding:30px;color:#697b82;text-align:center}
.config-scope{border-top:1px solid #edf0f2;padding-top:14px;margin-top:16px;font-size:12px;color:#697b82;line-height:1.8}
.config-scope summary{cursor:pointer;color:#586d73}.config-scope summary span{margin-left:8px;color:#8c9a9f}
.config-scope p{margin-top:10px}
.config-list{list-style:none;padding:0;margin:12px 0}
.config-list li{display:flex;align-items:center;gap:12px;flex-wrap:wrap;padding:9px 0;border-bottom:1px solid #f1f3f4}
.config-list code{flex:1;overflow-wrap:anywhere;min-width:150px;color:#667981}
.feature-detail{color:#586c74;line-height:1.8}
.detail-status{display:flex;align-items:center;gap:10px;flex-wrap:wrap;font-size:13px;margin-bottom:20px}
.feature-detail h3{font-size:14px;color:#283b42;margin:0 0 10px}
.feature-detail ul{padding-left:20px;font-size:13px}.feature-detail li+li{margin-top:10px}
.detail-note{padding:14px 16px;background:#f6f8f4;border:1px solid #e9eee3;border-radius:10px;margin-top:20px;font-size:12px}.detail-note strong{color:#586b4e}.detail-note p{margin-top:6px}
@media(max-width:1200px){.feature-grid{grid-template-columns:repeat(3,minmax(0,1fr))}}
@media(max-width:850px){.feature-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:480px){.feature-overview :deep(.el-card__header),.feature-overview :deep(.el-card__body){padding:18px 16px}.feature-heading{gap:16px}.feature-actions{width:100%;justify-content:space-between}.feature-grid{grid-template-columns:1fr}.feature-card{padding:16px}.feature-title{gap:8px}.feature-title h2{font-size:18px}}
@media(prefers-reduced-motion:reduce){.feature-card{transition:none}}
</style>
