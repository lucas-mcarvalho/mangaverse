import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '')
  return {
    plugins: [react(), tailwindcss()],
    server: {
      host: true,
      port: 5180,
      proxy: {
        '/api': { target: env.API_URL || 'http://localhost:8090', changeOrigin: false },
      },
    },
  }
})
