import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
const proxy = {
    '/api': {
        target: process.env.API_PROXY_TARGET || 'http://127.0.0.1:8080',
        rewrite: (path) => path.replace(/^\/api(?=\/|$)/, ''),
    },
}
export default defineConfig({
    plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
    server: { host: '127.0.0.1', port: 7001, proxy },
    preview: { host: '127.0.0.1', port: 4173, strictPort: true, proxy },
})
