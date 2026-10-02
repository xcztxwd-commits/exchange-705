import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import * as path from 'node:path'
export default defineConfig({
  root: path.resolve(__dirname, 'control'), envDir: __dirname,
  plugins: [vue()], resolve: { dedupe: ['vue', 'vue-router', 'pinia'], alias: { '@': path.resolve(__dirname, 'src') } },
  build: { outDir: path.resolve(__dirname, 'dist-control'), emptyOutDir: true },
  server: { port: 5177, fs: { allow: [__dirname] }, proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: false } } },
})
