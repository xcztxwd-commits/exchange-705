import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../src/styles/global.scss'
import App from '../src/control/App.vue'
import { controlSession, api } from '../src/control/api'
import AdminTable from '../src/components/AdminTable'
import { TABLE_PREFERENCES, type TablePreferenceClient } from '../src/utils/tablePreferences'
const app = createApp(App)
app.component('AdminTable', AdminTable)
const tablePreferences: TablePreferenceClient = {
  identityKey: () => controlSession.value ? `CONTROL:${controlSession.value.user.id}:${controlSession.value.token}` : '',
  load: (table, signal) => api(`/control/table-preferences/${encodeURIComponent(table)}`, 'GET', undefined, signal),
  save: (table, columns, signal) => api(`/control/table-preferences/${encodeURIComponent(table)}`, 'PUT', columns, signal),
}
app.provide(TABLE_PREFERENCES, tablePreferences)
// This entry has only the independent, authenticated total-control identity.
app.directive('permission', { mounted(el, binding) { if (binding.value !== 'session:self' || !controlSession.value) el.remove() } })
app.use(ElementPlus).mount('#app')
