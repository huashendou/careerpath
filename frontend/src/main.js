import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './styles/main.css'

// Pinia 必须先装，路由守卫里会用到 store
const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
