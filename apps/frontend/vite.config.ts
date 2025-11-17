import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
    plugins: [react()],
    server: {
        host: true,
        port: 5174,
        allowedHosts: ['frp-bus.com'],
        proxy: {
            '/ai': {target: 'http://localhost:8080', changeOrigin: true},
            '/files': {target: 'http://localhost:8080', changeOrigin: true},
            "/minio": {
                target: "http://127.0.0.1:9000",
                changeOrigin: true,
                // 把前缀 /minio 去掉再转发
                rewrite: (path) => path.replace(/^\/minio/, ""),
            },
        },
    },
})

