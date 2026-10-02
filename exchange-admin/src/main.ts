import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import AdminTable from './components/AdminTable'
import { TABLE_PREFERENCES, type TablePreferenceClient } from './utils/tablePreferences'
import { useAuthStore } from './store/auth'
import request from './utils/request'
import { permissionDirective } from './utils/access'
import router from './router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import './styles/global.scss'

const app = createApp(App)
app.component('AdminTable', AdminTable)

// 注册所有图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.directive('permission', permissionDirective)
app.use(createPinia())
const auth = useAuthStore()
const tablePreferences: TablePreferenceClient = {
  identityKey: () => auth.token ? `${auth.user?.tenantId}:${auth.isControl ? 'CONTROL_ACCESS' : auth.user?.userType || auth.user?.role}:${auth.user?.id}:${auth.token}` : '',
  load: (table, signal) => request.get(`/admin/table-preferences/${encodeURIComponent(table)}`, { signal }),
  save: (table, columns, signal) => request.put(`/admin/table-preferences/${encodeURIComponent(table)}`, columns, { signal }),
}
app.provide(TABLE_PREFERENCES, tablePreferences)
app.use(router)
app.use(ElementPlus)
app.mount('#app')

