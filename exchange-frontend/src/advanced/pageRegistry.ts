import { defineAsyncComponent, type Component } from 'vue'
import { businessPageLoaders } from './businessPages'
import AdvancedLoading from './components/AdvancedLoading.vue'
import AdvancedLoadError from './components/AdvancedLoadError.vue'
const loaders = {
  ...businessPageLoaders,
  '/home': () => import('./views/Home.vue'),
  '/profile': () => import('./views/Profile.vue'),
  '/orders': () => import('./views/Orders.vue'),
  '/trade': () => import('./views/Trade.vue'),
  '/explore': () => import('./views/Explore.vue'),
}
const pages: Record<string, Component> = Object.fromEntries(Object.entries(loaders).map(([path, loader]) => [path, defineAsyncComponent({ loader, loadingComponent: AdvancedLoading, errorComponent: AdvancedLoadError, delay: 120 })]))
export const advancedPageForPath = (path: string) => pages[path]
