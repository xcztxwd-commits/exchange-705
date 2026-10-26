<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { can } from '@/utils/access'
import { announcementFieldGroups, announcementTranslations, cloneAnnouncementLocale, validateAnnouncementDocument } from '@/utils/announcementEditor'
import { parseDesign, type ActivityDesign } from '../../../exchange-frontend/src/utils/activityDesign'
import { createCelebrationAnnouncementDesign } from '../../../exchange-frontend/src/utils/announcementDesign'
import AnnouncementDocumentView from '../../../exchange-frontend/src/components/AnnouncementDocumentView.vue'

const props = defineProps<{
  value?: string; locale: string; fallback: string; name: string; template: boolean;
  saving: boolean; canCreate: boolean; editing?: boolean;
  fallbackCopy?: Record<string, string>; copy?: Record<string, string>; translations?: Record<string, any>;
}>()
const emit = defineEmits<{
  (event: 'save', value: { layoutJson: string; translations: Record<string, any>; name: string; defaultLocale: string; asTemplate: boolean }): void
  (event: 'cancel'): void
}>()
const design = ref<ActivityDesign>(structuredClone(parseDesign(props.value) || createCelebrationAnnouncementDesign()))
const editorLocale = ref(props.locale), defaultLocale = ref(props.fallback), campaignName = ref(props.name)
const changed = ref(false), previewWidth = ref(760), previewMessage = ref('')
const languageOptions = [
  { code: 'zh-CN', label: '简体中文' }, { code: 'zh-TW', label: '繁體中文' }, { code: 'ja', label: '日本語' },
]
const languages = computed(() => [...new Set([...languageOptions.map(option => option.code), ...Object.keys(design.value.locales)])])
const currentDocument = computed(() => design.value.locales[editorLocale.value]?.announcement)
const writePermission = computed(() => props.editing ? 'announcement:edit' : 'announcement:create')
const readonly = computed(() => props.saving || !can(writePermission.value))
const previewStyle = computed(() => ({ width: `${previewWidth.value}px`, '--announcement-height': '100%' }))
function languageName(locale: string) { return languageOptions.find(option => option.code === locale)?.label || locale }
function selectLocale(locale: string) {
  if (props.saving) return
  if (!design.value.locales[locale]) {
    if (!can(writePermission.value)) return
    design.value.locales[locale] = cloneAnnouncementLocale(design.value.locales[defaultLocale.value]!)
    changed.value = true
    ElMessage.info('已复制回退语言的内容，请编辑为对应语言后保存')
  }
  editorLocale.value = locale
  previewMessage.value = ''
}
async function removeLocale() {
  if (readonly.value || editorLocale.value === defaultLocale.value) return
  try { await ElMessageBox.confirm(`移除${languageName(editorLocale.value)}版本？`, '移除语言', { confirmButtonText: '移除', cancelButtonText: '取消' }) } catch { return }
  delete design.value.locales[editorLocale.value]
  editorLocale.value = defaultLocale.value
  changed.value = true
}
function addTier() {
  if (readonly.value || !currentDocument.value || currentDocument.value.tiers.length >= 20) return
  currentDocument.value.tiers.push({ deposit: '', reward: '' })
  changed.value = true
}
function removeTier(index: number) {
  if (readonly.value || !currentDocument.value || currentDocument.value.tiers.length <= 1) return
  currentDocument.value.tiers.splice(index, 1)
  changed.value = true
}
async function previewAction(action: 'read' | 'close' | 'link') {
  previewMessage.value = action === 'link' ? '预览：正式页面将打开在线客服。' : '预览：确认按钮会记录已读并关闭公告。'
}
function save(asTemplate: boolean) {
  if (readonly.value || (asTemplate !== props.template && !props.canCreate)) return
  try {
    if (!campaignName.value.trim() || campaignName.value.trim().length > 120) throw new Error('请填写1至120字的后台名称')
    if (!design.value.locales[defaultLocale.value]) throw new Error('请选择已配置的回退语言')
    for (const [locale, value] of Object.entries(design.value.locales)) {
      try { validateAnnouncementDocument(value.announcement) } catch (error: any) { throw new Error(`${languageName(locale)}：${error.message}`) }
    }
    emit('save', { layoutJson: JSON.stringify(design.value), translations: announcementTranslations(design.value), name: campaignName.value.trim(), defaultLocale: defaultLocale.value, asTemplate })
  } catch (error: any) { ElMessage.error(error.message) }
}
async function cancel() {
  if (props.saving) return
  if (changed.value) {
    try { await ElMessageBox.confirm('未保存的公告修改将丢弃，确定退出？', '退出编辑器', { confirmButtonText: '退出', cancelButtonText: '继续编辑' }) } catch { return }
  }
  emit('cancel')
}
</script>

<template>
  <section class="announcement-studio" :aria-busy="saving">
    <header class="announcement-studio-header">
      <div><strong>公告模板编辑器</strong><small>内容跟随页面语言显示</small></div>
      <div class="editor-actions">
        <el-button v-permission="'session:close'" :disabled="saving" @click="cancel">取消</el-button>
        <el-button v-if="canCreate" v-permission="'announcement:create'" :disabled="readonly" @click="save(!template)">{{ template ? '保存为活动' : '另存为模板' }}</el-button>
        <el-button v-permission="writePermission" type="primary" :loading="saving" :disabled="readonly" @click="save(template)">{{ template ? '保存模板' : '保存活动' }}</el-button>
      </div>
    </header>
    <div class="announcement-meta">
      <label>后台名称<el-input v-model="campaignName" aria-label="后台公告名称" maxlength="120" :disabled="readonly" @input="changed = true" /></label>
      <label>编辑语言<el-select :model-value="editorLocale" aria-label="编辑公告语言" :disabled="saving" @change="selectLocale"><el-option v-for="locale in languages" :key="locale" :value="locale" :label="languageName(locale) + (design.locales[locale] ? ' · 已配置' : ' · 新增')" /></el-select></label>
      <label>回退语言<el-select v-model="defaultLocale" aria-label="公告回退语言" :disabled="readonly" @change="changed = true"><el-option v-for="locale in Object.keys(design.locales)" :key="locale" :value="locale" :label="languageName(locale)" /></el-select></label>
      <el-button v-permission="writePermission" :disabled="readonly || editorLocale === defaultLocale" @click="removeLocale">移除此语言</el-button>
    </div>
    <main class="announcement-editor-body">
      <div class="announcement-fields">
        <p class="editor-hint">编辑文字和金额即可更新布局。展示日期与发送时间窗分别设置；保存不会发送公告。</p>
        <el-form v-if="currentDocument" label-position="top" :disabled="readonly" @input="changed = true" @change="changed = true">
          <fieldset v-for="group in announcementFieldGroups" :key="group.title">
            <legend>{{ group.title }}</legend>
            <el-form-item v-for="field in group.fields" :key="field.key" :label="field.label" required>
              <el-input v-model="currentDocument[field.key]" :aria-label="field.label" :type="field.long ? 'textarea' : 'text'" :autosize="field.long ? { minRows: 3, maxRows: 10 } : undefined" :maxlength="field.long ? 10000 : 200" show-word-limit />
            </el-form-item>
            <template v-if="group.title === '奖励表格'">
              <div v-for="(tier, index) in currentDocument.tiers" :key="index" class="announcement-tier">
                <span>第 {{ index + 1 }} 档</span>
                <el-input v-model="tier.deposit" :aria-label="'第' + (index + 1) + '档入金金额'" maxlength="80" placeholder="入金金额" />
                <el-input v-model="tier.reward" :aria-label="'第' + (index + 1) + '档奖励金额'" maxlength="80" placeholder="奖励金额" />
                <el-button v-permission="writePermission" :disabled="readonly || currentDocument.tiers.length === 1" :aria-label="'删除第' + (index + 1) + '档'" @click="removeTier(index)">删除</el-button>
              </div>
              <el-button v-permission="writePermission" :disabled="readonly || currentDocument.tiers.length >= 20" @click="addTier">添加奖励档位</el-button>
            </template>
          </fieldset>
        </el-form>
        <p v-else role="alert">当前语言的公告内容无效，请切换到已配置的语言。</p>
      </div>
      <section class="announcement-preview-area" aria-label="公告实时预览">
        <div class="announcement-preview-toolbar"><strong>实时预览</strong><div role="group" aria-label="预览设备"><button type="button" :aria-pressed="previewWidth === 375" @click="previewWidth = 375">手机</button><button type="button" :aria-pressed="previewWidth === 760" @click="previewWidth = 760">电脑</button></div></div>
        <p class="editor-hint">{{ languageName(editorLocale) }} · 与用户端使用相同渲染组件；按钮仅演示交互。</p>
        <div v-if="currentDocument" class="announcement-preview-frame" :style="previewStyle"><AnnouncementDocumentView :key="editorLocale" :document="currentDocument" :run-action="previewAction" /></div>
        <p v-if="previewMessage" class="preview-message" role="status">{{ previewMessage }}</p>
      </section>
    </main>
    <footer class="announcement-editor-footer">保存保留全部语言和可编辑内容。发布状态、登录触发及时间窗在“公告发送设置”中配置。</footer>
  </section>
</template>

<style scoped>
.announcement-studio{height:calc(100dvh - 60px);min-height:0;display:flex;flex-direction:column;background:#f5f7f2;color:#33412c;font:13px/1.6 system-ui,sans-serif}.announcement-studio-header{display:flex;justify-content:space-between;align-items:center;gap:16px;padding:14px 22px;background:#fff;border-bottom:1px solid #e3e9dc}.announcement-studio-header strong{font-size:17px}.announcement-studio-header small{display:block;font-size:11px;color:#6f7d62}.editor-actions{display:flex;flex-wrap:wrap;gap:8px}.editor-actions .el-button{margin:0}.announcement-meta{display:flex;flex-wrap:wrap;gap:12px;align-items:center;padding:12px 22px;background:#fff;border-bottom:1px solid #e3e9dc}.announcement-meta label{display:flex;align-items:center;gap:8px}.announcement-meta .el-input{width:230px}.announcement-meta .el-select{width:170px}.announcement-editor-body{min-height:0;flex:1;display:grid;grid-template-columns:minmax(320px,420px) minmax(0,1fr)}.announcement-fields{min-height:0;overflow:auto;padding:20px 24px;background:#fff;border-right:1px solid #e3e9dc}.editor-hint{margin:0 0 18px;color:#6b795f;font-size:12px;line-height:1.8}.announcement-fields fieldset{padding:0;margin:0 0 26px;border:0}.announcement-fields legend{width:100%;padding:0 0 10px;margin-bottom:18px;border-bottom:1px solid #e3e9dc;font-size:15px;font-weight:700}.announcement-fields :deep(.el-form-item){margin-bottom:19px}.announcement-fields :deep(.el-input__count){font-size:10px}.announcement-tier{display:grid;grid-template-columns:44px minmax(0,1fr) minmax(0,1fr) 54px;gap:8px;align-items:center;margin:12px 0;font-size:11px}.announcement-tier .el-button{padding-left:8px;padding-right:8px}.announcement-preview-area{display:flex;flex-direction:column;min-width:0;min-height:0;overflow:hidden;padding:22px}.announcement-preview-area>.editor-hint{flex-shrink:0}.announcement-preview-toolbar{display:flex;justify-content:space-between;gap:16px;align-items:center;flex-shrink:0;margin-bottom:8px}.announcement-preview-toolbar strong{font-size:14px}.announcement-preview-toolbar button{min-height:34px;padding:6px 14px;border:1px solid #d9e2ce;background:#fff;font:12px system-ui,sans-serif;color:#657359;cursor:pointer}.announcement-preview-toolbar button:first-child{border-radius:6px 0 0 6px}.announcement-preview-toolbar button:last-child{border-radius:0 6px 6px 0}.announcement-preview-toolbar button[aria-pressed=true]{background:#e8f1df;color:#3e6322;border-color:#94b574}.announcement-preview-toolbar button:focus-visible{outline:2px solid #6c9b3a;outline-offset:2px}.announcement-preview-frame{flex:1 1 0;min-height:0;height:0;max-width:100%;margin:22px auto 0;overflow:hidden;border-radius:22px;box-shadow:0 16px 46px #263b1521;background:white}.preview-message{flex-shrink:0;max-height:62px;overflow:auto;margin:12px 0 0;padding:10px 14px;background:#eaf2e1;color:#526c3d;border-radius:7px;font-size:12px}.announcement-editor-footer{padding:10px 22px;border-top:1px solid #e3e9dc;background:white;font-size:11px;color:#68765f}
@media(max-width:1000px){.announcement-editor-body{grid-template-columns:minmax(290px,350px) minmax(0,1fr)}.announcement-fields{padding:18px}.announcement-preview-area{padding:18px 12px}.announcement-tier{grid-template-columns:35px minmax(0,1fr) minmax(0,1fr) 48px;gap:5px}}
@media(max-width:760px){.announcement-studio-header{align-items:flex-start;flex-direction:column;padding:12px 16px}.announcement-meta{padding:12px 16px}.announcement-meta label{width:100%;justify-content:space-between}.announcement-meta .el-input,.announcement-meta .el-select{width:min(260px,70%)}.announcement-editor-body{display:flex;flex-direction:column;overflow:auto}.announcement-fields{flex:none;overflow:visible;border-right:0}.announcement-preview-area{flex:none;min-height:650px;overflow:visible}.announcement-preview-frame{flex:none;height:580px;--announcement-height:580px!important}.announcement-editor-footer{padding:10px 16px}}
</style>
