<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import { can } from '@/utils/access'
import { supportUrl } from '@/utils/support'
const settings = ref<any>(),
  busy = ref(false),
  error = ref('')
const replyLocale = ref('')
const languages = [
  ['zh-CN', '简体中文'], ['zh-TW', '繁體中文'], ['en', 'English'], ['ja', '日本語'],
  ['ko', '한국어'], ['fr', 'Français'], ['de', 'Deutsch'], ['ru', 'Русский'],
  ['es', 'Español'], ['pt', 'Português'], ['it', 'Italiano'], ['ar', 'العربية'],
  ['tr', 'Türkçe'], ['id', 'Bahasa Indonesia'], ['my', 'မြန်မာ'], ['hi', 'हिंदी'],
  ['cs', 'Čeština'], ['pl', 'Polski'], ['th', 'ไทย'], ['vi', 'Tiếng Việt'],
]
function replyField(field: 'welcome' | 'offline') {
  return computed({
    get: () => replyLocale.value
      ? settings.value?.replies?.[replyLocale.value]?.[field] || ''
      : settings.value?.[field] || '',
    set: (value: string) => {
      if (!replyLocale.value) settings.value[field] = value
      else {
        settings.value.replies[replyLocale.value] ||= { welcome: '', offline: '' }
        settings.value.replies[replyLocale.value][field] = value
      }
    },
  })
}
const welcome = replyField('welcome'), offline = replyField('offline')
const sounds = [
  { label: '清脆三音 · 新客户', value: '/api/user/support/tones/arrival.wav' },
  { label: '柔和双音 · 新回复', value: '/api/user/support/tones/reply.wav' },
  { label: '关闭提示音', value: '' },
]
async function load() {
  try {
    settings.value = await request.get('/admin/support/settings')
    settings.value.replies ||= {}
    settings.value.fallbackLocale ||= ''
  } catch (e: any) {
    error.value = e.message
  }
}
async function save() {
  if (busy.value) return
  busy.value = true
  try {
    await request.post('/admin/support/settings', settings.value)
    ElMessage.success('配置已保存，客户端将在下次刷新时生效')
  } catch (e: any) {
    ElMessage.error(e.message)
  } finally {
    busy.value = false
  }
}
async function preview(value: string) {
  if (!value) return
  try {
    await new Audio(supportUrl(value)).play()
  } catch {
    ElMessage.warning('浏览器未允许播放，或音频不可用')
  }
}
onMounted(load)
</script>
<template>
  <section class="support-settings">
    <header>
      <small>COMMUNICATION SETTINGS</small>
      <h1>客服与消息设置</h1>
      <p>保留原客服入口，按业务需要切换服务方式。</p>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form
      v-if="settings"
      label-position="top"
      :disabled="!can('support_settings:save')"
      @submit.prevent="save"
    >
      <div class="setting-card">
        <h2>01 / 服务渠道</h2>
        <el-form-item label="客服模式"
          ><el-radio-group v-model="settings.mode"
            ><el-radio-button value="off" label="off">关闭客服</el-radio-button
            ><el-radio-button value="external" label="external">外部客服</el-radio-button
            ><el-radio-button value="internal" label="internal">站内客服</el-radio-button></el-radio-group
          ></el-form-item
        >
        <p class="hint">
          外部地址沿用「系统配置 → 客服配置 →
          客服链接」。切换渠道不会删除历史会话；关闭站内客服后禁止创建和回复。
        </p>
        <el-form-item label="站内信"
          ><el-switch
            v-permission="'support_settings:save'"
            v-model="settings.inboxEnabled"
            active-text="开放站内信入口及发送"
            inactive-text="关闭" /></el-form-item
        ><el-form-item label="每位客服同时接待上限"
          ><el-input-number v-model="settings.capacity" :min="1" :max="50"
        /></el-form-item>
      </div>
      <div class="setting-card">
        <h2>02 / 欢迎与离线回复</h2>
        <el-form-item label="编辑回复语言">
          <el-select v-model="replyLocale" :empty-values="[null, undefined]" aria-label="编辑回复语言">
            <el-option label="默认回复（兼容原配置）" value="" />
            <el-option v-for="[value, label] in languages" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item label="未选择或未配置语言时的回退语言">
          <el-select v-model="settings.fallbackLocale" :empty-values="[null, undefined]" aria-label="回退语言">
            <el-option label="默认回复" value="" />
            <el-option v-for="[value, label] in languages" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <p class="hint">按用户当前页面语言发送欢迎语和显示离线提示。对应内容留空时，依次使用回退语言、默认回复。切换编辑语言不会丢失草稿，全部语言统一保存；已发送的历史消息不变。</p>
        <el-form-item label="欢迎回复"
          ><el-input
            v-model="welcome"
            type="textarea"
            :rows="3"
            maxlength="2000"
            show-word-limit /></el-form-item
        ><el-form-item label="离线提示"
          ><el-input v-model="offline" type="textarea" :rows="2" maxlength="2000" show-word-limit
        /></el-form-item>
        <p class="hint">
          IP 规则优先于语言匹配，按顺序首条命中生效（仅覆盖欢迎语）；未命中按页面语言回复。支持 IPv4、IPv6 及 CIDR
          网段，不依赖不可靠的国家猜测。
        </p>
        <div v-for="(rule, index) in settings.rules" :key="index" class="ip-rule">
          <el-input
            v-model="rule.cidr"
            placeholder="IP / CIDR，如 192.0.2.0/24"
            :aria-label="`规则 ${Number(index) + 1} 的 IP 网段`"
          /><el-input
            v-model="rule.reply"
            type="textarea"
            maxlength="2000"
            placeholder="该 IP / 网段的初始回复"
            :aria-label="`规则 ${Number(index) + 1} 的回复`"
          /><el-button v-permission="'support_settings:save'" @click="settings.rules.splice(index, 1)"
            >移除</el-button
          >
        </div>
        <el-button
          v-permission="'support_settings:save'"
          :disabled="settings.rules.length >= 50"
          @click="settings.rules.push({ cidr: '', reply: '' })"
          >添加 IP 规则</el-button
        >
        <p class="hint">
          反向代理复用 SECURITY_TRUSTED_PROXIES，可用 SUPPORT_TRUSTED_PROXIES 单独覆盖；代理须覆盖 X-Real-IP。未配置可信代理时只使用直连 IP。
        </p>
      </div>
      <div class="setting-card">
        <h2>03 / 提示音</h2>
        <p class="hint">
          内置原创 WAV，已作为默认音源，无第三方版权及外链失效风险。也可粘贴现有「提示音配置」上传后获得的
          /api/uploads/audio/ 地址。
        </p>
        <el-form-item
          v-for="item in [
            { key: 'adminSound', title: '后台 · 新排队客户 / 新消息' },
            { key: 'userSound', title: '用户 · 客服回复 / 新站内信' },
          ]"
          :key="item.key"
          :label="item.title"
          ><div class="sound-row">
            <el-select v-model="settings[item.key]" filterable allow-create default-first-option
              ><el-option
                v-for="sound in sounds"
                :key="sound.value"
                :label="sound.label"
                :value="sound.value" /></el-select
            ><el-button
              v-permission="'support_settings:save'"
              :disabled="!settings[item.key]"
              @click="preview(settings[item.key])"
              >试听</el-button
            >
          </div></el-form-item
        ><el-alert
          type="info"
          :closable="false"
          title="用户需点击「开启提示音」授权。网页关闭、手机锁屏或系统静音时不保证播放；本功能不是系统级推送。"
        />
      </div>
      <div class="setting-card">
        <h2>04 / 账号与监督</h2>
        <p class="hint">
          在「角色与权限」创建客服角色，选择「客服工作台」及查看、接待、回复、图片、结束、转接等操作；在「管理员账号」创建多个客服账号并绑定该角色。无须授予系统设置或客户资金权限。
        </p>
        <p class="hint">
          普通管理员只能查看自己当前负责的会话。监督全部与证据导出始终只允许超级管理员，普通角色即使被勾选也不能越权。消息及图片不提供修改、撤回、删除接口。
        </p>
      </div>
      <el-button
        v-permission="'support_settings:save'"
        v-if="can('support_settings:save')"
        type="primary"
        native-type="submit"
        :loading="busy"
        >保存客服与消息设置</el-button
      >
    </el-form>
  </section>
</template>
<style scoped>
.support-settings {
  max-width: 920px;
  color: #29374b;
}
.support-settings header small {
  color: #91a0b4;
  font-size: 10px;
  letter-spacing: 2px;
}
.support-settings h1 {
  font-size: 25px;
  font-weight: 600;
  margin: 10px 0;
}
.support-settings header p {
  font-size: 12px;
  color: #95a0b0;
  margin-bottom: 25px;
}
.setting-card {
  background: white;
  border: 1px solid #e8edf3;
  border-radius: 14px;
  padding: 25px;
  margin: 0 0 18px;
}
.setting-card h2 {
  font-size: 14px;
  font-weight: 600;
  margin: 0 0 23px;
  color: #657c98;
}
.hint {
  font-size: 12px;
  color: #8e9aac;
  line-height: 1.9;
  margin: 0 0 20px;
}
.sound-row {
  display: flex;
  gap: 12px;
  width: 100%;
}
.sound-row .el-select {
  flex: 1;
}
.ip-rule {
  display: grid;
  grid-template-columns: 220px 1fr auto;
  gap: 12px;
  margin-bottom: 12px;
  align-items: start;
}
.setting-card :deep(.el-form-item__label) {
  font-size: 12px;
}
@media (max-width: 700px) {
  .ip-rule {
    grid-template-columns: 1fr;
  }
  .setting-card {
    padding: 18px;
  }
}
</style>
