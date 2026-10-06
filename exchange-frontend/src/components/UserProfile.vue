<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useAuthStore } from '@/store/auth'
import { useLocaleStore } from '@/store/locale'
import request from '@/utils/request'
import UserAvatar from './UserAvatar.vue'

defineProps<{ advanced?: boolean }>()
const auth = useAuthStore(), locale = useLocaleStore()
const text = (zh: string, en: string, ja: string) => locale.locale === 'ja' ? ja : locale.text(zh, en)
const name = computed(() => auth.user?.nickname?.trim() || auth.user?.email || locale.text('用戶', 'User'))
const dialog = ref<HTMLDialogElement>(), fileInput = ref<HTMLInputElement>()
const nickname = ref(''), draftAvatar = ref(''), selectedFile = ref<File>(), removeAvatar = ref(false)
const loading = ref(false), ready = ref(false), saving = ref(false), error = ref(''), saved = ref(false)
let controller: AbortController | undefined, objectUrl = '', generation = 0

function releasePreview() { if (objectUrl) URL.revokeObjectURL(objectUrl); objectUrl = '' }
function resetDialog() {
  dialog.value?.close(); releasePreview(); selectedFile.value = undefined
  if (fileInput.value) fileInput.value.value = ''
}
function apply(profile: any, token: string, id: number | string) {
  if (auth.token !== token || String(auth.user?.id) !== String(id)) return false
  if (!profile || String(profile.id) !== String(id) || typeof profile.email !== 'string' || profile.success === false) throw new Error('Invalid profile response')
  auth.setAuth(token, { ...auth.user, email: profile.email, nickname: profile.nickname || null, avatarUrl: profile.avatarUrl || null })
  return true
}
async function load() {
  const token = auth.token, id = auth.user?.id, sequence = ++generation
  controller?.abort(); controller = new AbortController()
  resetDialog(); saved.value = false; ready.value = false; saving.value = false; error.value = ''
  if (!token || !id) { loading.value = false; return }
  loading.value = true
  try {
    const profile = await request.get('/user/profile', { signal: controller.signal })
    if (sequence === generation && apply(profile, token, id)) ready.value = true
  } catch { if (sequence === generation) error.value = text('資料暫不可用，請重試', 'Profile unavailable. Please retry.', 'プロフィールを取得できません。再試行してください。') }
  finally { if (sequence === generation) loading.value = false }
}
function open() {
  if (!ready.value || saving.value) return
  releasePreview(); nickname.value = auth.user?.nickname || ''; draftAvatar.value = auth.user?.avatarUrl || ''
  selectedFile.value = undefined; removeAvatar.value = false; error.value = ''; saved.value = false
  if (fileInput.value) fileInput.value.value = ''
  dialog.value?.showModal()
}
function close() { if (!saving.value) { const refresh = Boolean(error.value); resetDialog(); error.value = ''; if (refresh) void load() } }
function chooseFile(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]
  if (!file) return
  if (!['image/png', 'image/jpeg', 'image/gif'].includes(file.type) || !file.size || file.size > 5 * 1024 * 1024) {
    error.value = text('請選擇5MB以內的PNG、JPG或GIF圖片', 'Choose a PNG, JPG or GIF image up to 5MB.', '5MB以下のPNG・JPG・GIF画像を選択してください。'); input.value = ''; return
  }
  releasePreview(); selectedFile.value = file; objectUrl = URL.createObjectURL(file)
  draftAvatar.value = objectUrl; removeAvatar.value = false; error.value = ''
}
function clearAvatar() {
  releasePreview(); selectedFile.value = undefined; draftAvatar.value = ''; removeAvatar.value = true
  if (fileInput.value) fileInput.value.value = ''
}
async function save() {
  if (saving.value || !ready.value || !auth.token) return
  const value = nickname.value.trim()
  if (Array.from(value).length > 50 || /[\u0000-\u001f\u007f-\u009f]/.test(value)) {
    error.value = text('暱稱最多50個字元，不能包含控制字元', 'Nickname: up to 50 characters, no control characters.', 'ニックネームは50文字以内で、制御文字は使用できません。'); return
  }
  const token = auth.token, id = auth.user.id, sequence = generation
  const body = new FormData(); body.append('nickname', value); body.append('removeAvatar', String(removeAvatar.value))
  if (selectedFile.value) body.append('avatar', selectedFile.value)
  saving.value = true; error.value = ''
  try {
    const profile = await request.put('/user/profile', body, { signal: controller?.signal })
    if (sequence === generation && apply(profile, token, id)) { resetDialog(); saved.value = true }
  } catch { if (sequence === generation) error.value = text('未能確認保存結果，請關閉後重新載入資料再重試', 'Save not confirmed. Close and reload your profile before retrying.', '保存結果を確認できません。閉じてプロフィールを再読み込みしてください。') }
  finally { if (sequence === generation) saving.value = false }
}
watch([() => auth.token, () => auth.user?.id], () => { void load() }, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { generation++; controller?.abort(); resetDialog() })
</script>

<template>
  <div class="profile-identity" :class="{ 'is-advanced': advanced }">
    <button class="profile-open" type="button" :disabled="!ready || loading" @click="open" :aria-label="text('編輯資料', 'Edit profile', 'プロフィールを編集')">
      <UserAvatar :src="auth.user?.avatarUrl" :name="name" />
      <span class="profile-caption"><strong class="profile-name">{{ name }}</strong><small>UID {{ auth.user?.id || '—' }}</small><span class="profile-edit">{{ text('編輯資料', 'Edit profile', 'プロフィールを編集') }}</span></span>
    </button>
    <p v-if="error && !dialog?.open" class="profile-error" role="alert">{{ error }} <button type="button" @click="load">{{ locale.text('重試', 'Retry') }}</button></p>
    <p v-if="saved" class="profile-saved" role="status">{{ text('資料已保存', 'Profile saved', 'プロフィールを保存しました') }}</p>
    <dialog ref="dialog" class="profile-editor" :aria-label="text('編輯資料', 'Edit profile', 'プロフィールを編集')" @cancel.prevent="close">
      <form @submit.prevent="save">
        <header><h2>{{ text('編輯資料', 'Edit profile', 'プロフィールを編集') }}</h2><button type="button" :disabled="saving" @click="close" :aria-label="locale.text('關閉', 'Close')">×</button></header>
        <div class="avatar-edit"><UserAvatar :src="draftAvatar" :name="nickname.trim() || auth.user?.email || name" :size="88" /><div><label class="avatar-upload">{{ text('更換頭像', 'Change avatar', 'アバターを変更') }}<input ref="fileInput" type="file" accept="image/png,image/jpeg,image/gif" :disabled="saving" @change="chooseFile" /></label><button type="button" :disabled="saving || !draftAvatar" @click="clearAvatar">{{ text('移除頭像', 'Remove avatar', 'アバターを削除') }}</button></div></div>
        <p class="profile-hint">PNG / JPG / GIF · {{ text('最多5MB', 'Up to 5MB', '最大5MB') }}</p>
        <label class="nickname-label">{{ text('暱稱', 'Nickname', 'ニックネーム') }}<input v-model="nickname" type="text" :disabled="saving" :placeholder="auth.user?.email" autocomplete="nickname" :aria-invalid="Array.from(nickname.trim()).length > 50" /></label>
        <p class="profile-hint">{{ text('不填顯示郵箱，最多50個字元。頭像和暱稱不影響登入及實名資料。', 'Leave blank to show your email. Up to 50 characters. Login and verified identity stay unchanged.', '空欄の場合はメールアドレスを表示します（最大50文字）。ログインと本人確認情報は変わりません。') }}</p>
        <p v-if="error" class="profile-error" role="alert">{{ error }}</p>
        <footer><button type="button" :disabled="saving" @click="close">{{ locale.t('cancelText') }}</button><button class="profile-save" type="submit" :disabled="saving">{{ saving ? text('保存中…', 'Saving…', '保存中…') : locale.t('saveText') }}</button></footer>
      </form>
    </dialog>
  </div>
</template>

<style scoped>
.profile-identity{--profile-accent:#75a731;--profile-surface:#fff;--profile-ink:#252a30;--profile-border:#e4e8eb;min-width:0;color:var(--profile-ink)}.profile-identity.is-advanced{--profile-accent:#736582}.profile-open{display:flex;align-items:center;gap:12px;width:100%;border:0;background:none;text-align:start;color:inherit;padding:0;font:inherit;cursor:pointer}.profile-open:disabled{cursor:default}.profile-caption{display:flex;flex-direction:column;gap:4px;min-width:0;flex:1}.profile-name{font-size:16px;font-weight:600;overflow-wrap:anywhere}.profile-caption small,.profile-hint{color:#7a828b;font-size:12px;line-height:1.6}.profile-edit,.profile-saved{font-size:12px;color:var(--profile-accent)}.profile-identity button:focus-visible,.profile-editor input:focus-visible,.avatar-upload:focus-within{outline:2px solid var(--profile-accent);outline-offset:3px}.profile-error{color:#c34d58;font-size:12px;overflow-wrap:anywhere}.profile-error button{font:inherit;color:inherit;text-decoration:underline;border:0;background:none}.profile-editor{width:min(420px,calc(100vw - 32px));max-height:calc(100dvh - 32px);overflow:auto;padding:24px;border:1px solid var(--profile-border);border-radius:18px;margin:auto;color:var(--profile-ink);background:var(--profile-surface);box-shadow:0 20px 80px #0004;box-sizing:border-box}.profile-editor::backdrop{background:#141b2980}.profile-editor header{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:24px}.profile-editor h2{font-size:20px;margin:0}.profile-editor button{font:inherit;cursor:pointer}.profile-editor header button{background:none;border:0;color:inherit;font-size:26px;width:36px;height:36px}.avatar-edit{display:flex;align-items:center;gap:22px}.avatar-edit>div{display:flex;flex-direction:column;align-items:flex-start;gap:10px}.avatar-upload{position:relative;display:inline-flex;align-items:center;overflow:hidden;border:1px solid var(--profile-border);padding:10px 14px;border-radius:8px;cursor:pointer;font-size:14px}.avatar-upload input{position:absolute;inset:0;opacity:0;width:100%;cursor:pointer}.avatar-edit button{background:none;border:0;color:#7a828b;font-size:12px;padding:4px 0}.nickname-label{display:flex;flex-direction:column;gap:8px;margin-top:24px;font-size:14px}.nickname-label input{width:100%;min-width:0;box-sizing:border-box;height:44px;border:1px solid var(--profile-border);border-radius:8px;padding:0 12px;background:var(--profile-surface);color:inherit;font:inherit}.profile-editor footer{display:flex;gap:12px;margin-top:24px}.profile-editor footer button{flex:1;min-height:44px;border:1px solid var(--profile-border);border-radius:8px;background:var(--profile-surface);color:inherit}.profile-editor footer .profile-save{background:var(--profile-accent);border-color:var(--profile-accent);color:#fff}.profile-editor button:disabled{opacity:.5;cursor:default}.dark .profile-identity{--profile-surface:#181c27;--profile-ink:#f1f4f8;--profile-border:#343b47}
</style>
