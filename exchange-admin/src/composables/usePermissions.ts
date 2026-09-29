import { computed } from 'vue'
import { useAuthStore } from '@/store/auth'
import { access, can, loadAccess, clearAccess } from '@/utils/access'
export function usePermissions() {
  const isAgent = computed(() => useAuthStore().user?.userType === 'agent')
  const loadPermissions = async () => { await loadAccess(); return new Map(Object.entries(access.actions)) }
  const hasPermission = async (menu: string, action: string) => { await loadAccess(); return can(`${menu}:${action}`) }
  return { isAgent, loadPermissions, hasPermission, clearCache: clearAccess }
}
