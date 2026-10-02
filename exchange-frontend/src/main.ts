import { createApp } from 'vue'
import { initVisitorRegion } from './utils/visitorRegion'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './styles/global.scss'

const app = createApp(App)
app.use(createPinia())
app.use(router)
void initVisitorRegion().then(() => {
  app.mount('#app')
  window.dispatchEvent(new Event('forex-app-ready'))
})
