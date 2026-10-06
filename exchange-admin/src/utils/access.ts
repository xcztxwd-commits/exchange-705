import { reactive, watchEffect, type ObjectDirective } from 'vue'
import { useAuthStore } from '@/store/auth'
import request from './request'

export const access = reactive({ loaded: false, error: '', superAdmin: false, menus: [] as any[], groups: [] as any[], actions: {} as Record<string, string[]> })
let subject: string | null = null
let generation = 0
let pending: Promise<void> | null = null
const normalize = (code: string) => code.replace(/-/g, '_')

export function clearAccess() {
  generation++
  subject = null
  pending = null
  Object.assign(access, { loaded: false, error: '', superAdmin: false, menus: [], groups: [], actions: {} })
}

export async function loadAccess(force = false): Promise<void> {
  const token = useAuthStore().token
  if (subject !== token) { clearAccess(); subject = token }
  if (!token) return
  if (pending) return pending
  if (access.loaded && !access.error && !force) return
  const currentGeneration = generation
  const task = (async () => {
    try {
      const result: any = await request.get('/admin/menus/current')
      if (useAuthStore().token !== token || generation !== currentGeneration) return
      if (result?.success !== true || !Array.isArray(result.menus) || !result.actions || typeof result.actions !== 'object' || Array.isArray(result.actions) ||
          !Object.values(result.actions).every(grants => Array.isArray(grants) && grants.every(action => typeof action === 'string'))) throw new Error('权限响应无效')
      Object.assign(access, { loaded: true, error: '', superAdmin: result.superAdmin === true, menus: result.menus || [], groups: result.groups || [], actions: result.actions || {} })
    } catch (error: any) {
      if (useAuthStore().token === token && generation === currentGeneration) {
        const status = error.response?.status
        if (status === 403 && error.response?.data?.success === false) {
          Object.assign(access, { loaded: true, error: '', superAdmin: false, menus: [], groups: [], actions: {} })
          return
        }
        // Keep the last confirmed snapshot for the mounted page, never as write authority.
        access.error = error.response?.data?.code === 'RELEASE_MAINTENANCE' ? '系统正在发布维护（HTTP 503）'
          : status ? `权限服务请求失败（HTTP ${status}）`
          : error.message === '权限响应无效' ? '权限服务返回了无效响应' : '网络连接异常或权限请求超时'
      }
      throw error
    }
  })()
  pending = task
  try { await task } finally { if (pending === task) pending = null }
}

export function can(code: string): boolean {
  if (code === 'session:close' || code === 'session:self') return !!useAuthStore().token
  if (!access.loaded || subject !== useAuthStore().token) return false
  const [menu, action = 'view'] = code.split(':')
  if (access.error && action !== 'view') return false
  const grants = access.actions[normalize(menu || '') ]
  return !!grants && (action === 'view' || grants.includes('*') || grants.includes(action))
}

export function routeMenu(path: string): string | undefined {
  if (/^\/agents\/\d+\/performance$/.test(path)) return 'agents'
  return access.menus.find(menu => menu.path === path)?.menuCode
}
export function canRoute(path: string): boolean {
  if (/^\/agents\/\d+\/performance$/.test(path)) return can('agents:performance')
  const menu = routeMenu(path)
  return !!menu && can(`${menu}:view`)
}

type PermissionElement = HTMLElement & { permissionStop?: () => void; permissionValue?: string; permissionBlock?: (event: Event) => void }
export const permissionDirective: ObjectDirective<PermissionElement, string> = {
  mounted(el, binding) {
    el.permissionValue = binding.value
    const apply = () => {
      const allowed = can(el.permissionValue || '')
      el.dataset.permission = el.permissionValue || ''
      el.style.setProperty('display', allowed ? '' : 'none', allowed ? '' : 'important')
      el.setAttribute('aria-hidden', String(!allowed))
    }
    el.permissionStop = watchEffect(apply)
    el.permissionBlock = event => { if (!can(el.permissionValue || '')) { event.preventDefault(); event.stopImmediatePropagation() } }
    el.addEventListener('click', el.permissionBlock, true)
    el.addEventListener('change', el.permissionBlock, true)
  },
  updated(el, binding) {
    el.permissionValue = binding.value
    const allowed = can(binding.value)
    el.dataset.permission = binding.value
    el.style.setProperty('display', allowed ? '' : 'none', allowed ? '' : 'important')
    el.setAttribute('aria-hidden', String(!allowed))
  },
  beforeUnmount(el) {
    el.permissionStop?.()
    if (el.permissionBlock) { el.removeEventListener('click', el.permissionBlock, true); el.removeEventListener('change', el.permissionBlock, true) }
  },
}
