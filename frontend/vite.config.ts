import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  base: '/fate/',
  build: {
    outDir: '../ftc/src/main/assets/web/fate',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/fate/logs': {
        target: 'http://192.168.43.1:8080', // Default FTC Control Hub IP and Port
        changeOrigin: true,
      }
    }
  }
})
