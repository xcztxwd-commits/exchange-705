import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import * as path from 'node:path';
export default defineConfig({
    plugins: [vue()],
    resolve: {
        // Shared SFCs from sibling apps must use this app's provider identities.
        dedupe: ['vue', 'vue-router', 'pinia'],
        alias: {
            '@': path.resolve(__dirname, './src'),
        },
    },
    server: {
        proxy: {
            '/api': {
                target: 'http://localhost:8080',
                changeOrigin: false,
            },
            '/uploads': {
                target: 'http://localhost:8080',
                changeOrigin: false,
            },
        },
    },
});
