import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    // Element Plus 全量引入约 1MB，和业务代码打在同一个 chunk 里会导致
    // 每次改一行样式都让用户重新下载整个组件库。拆成独立 vendor chunk。
    // 用函数式而不是对象式：对象式会把「已装但还没被 import」的包（如 T0 的 echarts）
    // 也生成一个 0.00 kB 的空 chunk，属无意义噪音；函数式只切真实进入依赖图的模块。
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          if (id.includes('element-plus')) return 'element-plus'
          if (id.includes('echarts') || id.includes('zrender')) return 'echarts'
          return 'vendor'
        },
      },
    },
    chunkSizeWarningLimit: 900,
  },
  server: {
    port: 5173,
    // 前端只认相对路径 /api，真正的后端地址只在这里出现一次。
    // v3 起代理目标是 Java 主业务服务（8080），不再是 Python（8000）——
    // 前端直连分析服务在架构层就被禁止（AGENTS.md 第四节）。
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
})
