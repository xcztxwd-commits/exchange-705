import { useAuthStore } from '@/store/auth'
import request from '@/utils/request'

// 权限缓存
let permissionsCache: Map<string, string[]> | null = null
let isLoadingPermissions = false

/**
 * 加载用户权限
 */
export async function loadUserPermissions(): Promise<Map<string, string[]>> {
  const auth = useAuthStore()
  
  // 超级管理员拥有所有权限
  if (auth.user?.isSuperAdmin || auth.user?.role === 'super_admin') {
    return new Map()
  }
  
  // 如果不是代理，返回空Map（表示拥有所有权限）
  if (auth.user?.userType !== 'agent') {
    return new Map()
  }
  
  // 如果正在加载，等待
  if (isLoadingPermissions && permissionsCache) {
    return permissionsCache
  }
  
  // 如果已有缓存，直接返回
  if (permissionsCache) {
    return permissionsCache
  }
  
  isLoadingPermissions = true
  
  try {
    const userId = auth.user?.id
    if (!userId) {
      return new Map()
    }
    
    const permissionsResponse: any = await request.get(`/admin/users/${userId}/menus`)
    if (permissionsResponse.success) {
      const allMenus: any = await request.get('/admin/menus/list')
      if (allMenus.success) {
        const menuMap = new Map<number, string>()
        allMenus.list.forEach((m: any) => {
          menuMap.set(m.id, m.menuCode)
        })
        
        const actions = permissionsResponse.actions || {}
        permissionsCache = new Map()
        for (const [menuIdStr, actionCodes] of Object.entries(actions)) {
          const menuId = Number(menuIdStr)
          const menuCode = menuMap.get(menuId)
          if (menuCode) {
            permissionsCache.set(menuCode, actionCodes as string[])
          }
        }
      }
    }
  } catch (error: any) {
    console.error('加载权限失败:', error)
    permissionsCache = new Map()
  } finally {
    isLoadingPermissions = false
  }
  
  return permissionsCache || new Map()
}

/**
 * 检查是否有操作权限
 */
export async function hasPermission(menuCode: string, actionCode: string): Promise<boolean> {
  const auth = useAuthStore()
  
  // 超级管理员拥有所有权限
  if (auth.user?.isSuperAdmin || auth.user?.role === 'super_admin') {
    return true
  }
  
  // 如果不是代理，默认有所有权限
  if (auth.user?.userType !== 'agent') {
    return true
  }
  
  // 加载权限
  const permissions = await loadUserPermissions()
  const actions = permissions.get(menuCode) || []
  return actions.includes(actionCode)
}

/**
 * 清除权限缓存（在权限更新后调用）
 */
export function clearPermissionsCache() {
  permissionsCache = null
}




