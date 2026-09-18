<script setup lang="ts">
import { useLocaleStore } from '@/store/locale'
import type { MessageKeys } from '@/store/locale'

const localeStore = useLocaleStore()
localeStore.loadLocale()

interface TabItem {
  nameKey: MessageKeys
  key: string
  route: string
  icon: string
  activeIcon: string
}

const tabs: TabItem[] = [
  { nameKey: 'tabbarMarket', key: 'home', route: '/home', icon: '/img/home.png', activeIcon: '/img/home-1.png' },
  { nameKey: 'tabbarTrade', key: 'trade', route: '/trade', icon: '/img/jy.png', activeIcon: '/img/jy-1.png' },
  { nameKey: 'tabbarOrder', key: 'order', route: '/orders', icon: '/img/dd.png', activeIcon: '/img/dd-1.png' },
  { nameKey: 'tabbarPersonalCenter', key: 'user', route: '/profile', icon: '/img/gr.png', activeIcon: '/img/gr-1.png' },
]
</script>

<template>
  <div class="tabbar">
    <router-link
      v-for="t in tabs"
      :key="t.key"
      :to="t.route"
      class="tabbar-item"
      :class="{ active: $route.path === t.route }"
    >
      <img :src="$route.path === t.route ? t.activeIcon : t.icon" class="tabbar-icon" :alt="localeStore.t(t.nameKey)" />
      <div class="text">{{ localeStore.t(t.nameKey) }}</div>
    </router-link>
  </div>
</template>

<style scoped>
.tabbar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  height: 64px;
  background: #fff;
  border-top: 1px solid #e6e6e6;
  display: flex;
  justify-content: space-around;
  align-items: center;
  padding: 0 12px;
  box-shadow: 0 -6px 18px rgba(0, 0, 0, 0.04);
  z-index: 50;
}
.tabbar-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  color: #777;
  font-size: 12px;
}
.tabbar-icon {
  width: 22px;
  height: 22px;
  margin-bottom: 2px;
}
.tabbar-item.active {
  color: #73b100;
  font-weight: 700;
}
</style>




