import { createApp } from 'vue'
import { initVisitorRegion } from './utils/visitorRegion'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles/global.scss'
import './styles/dark-theme.scss'

const savedTheme = localStorage.getItem('theme')
document.documentElement.classList.toggle('dark', savedTheme === 'dark' || (!savedTheme && matchMedia('(prefers-color-scheme: dark)').matches))

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus)
void initVisitorRegion().then(() => {
  app.mount('#app')
  window.dispatchEvent(new Event('forex-app-ready'))
})
