import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'node:path'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'LOCAL_')
  return {
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
      '@img': path.resolve(__dirname, './public/img'),
    },
  },
  css: {
    preprocessorOptions: {
      scss: {
        // 抑制 @import 弃用警告
        // 注意：在 Vue SFC 中使用 @import 仍然是最兼容的方式
        // silenceDeprecations 选项用于抑制弃用警告
        silenceDeprecations: ['legacy-js-api', 'import'],
      },
    },
  },
  server: {
    proxy: {
      '/demo-api': { target: 'http://localhost:8081', changeOrigin: false, ws: true, rewrite: (path) => path.replace(/^\/demo-api/, '/api') },
      '/demo-uploads': { target: 'http://localhost:8081', changeOrigin: false, rewrite: (path) => path.replace(/^\/demo-uploads/, '/uploads') },
      '/api': {
        ws: true,
        target: process.env.LOCAL_API_PROXY || env.LOCAL_API_PROXY || 'http://localhost:8080',
        changeOrigin: false,
      },
      '/uploads': {
        target: process.env.LOCAL_API_PROXY || env.LOCAL_API_PROXY || 'http://localhost:8080',
        changeOrigin: false,
      },
    },
  },
  build: {
    // 增加 chunk 大小限制，避免警告
    chunkSizeWarningLimit: 1000,
    rollupOptions: {
      output: {
        // 手动分割代码块，优化打包大小
        manualChunks: {
          'vue-vendor': ['vue', 'vue-router', 'pinia'],
          'chart-vendor': ['klinecharts'],
        },
      },
    },
  },
}
})
