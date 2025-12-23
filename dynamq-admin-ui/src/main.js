import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import axios from 'axios'
import App from './App.vue'
import router from './router'

// Configure axios with API key
const API_KEY = import.meta.env.VITE_API_KEY || 'dynamq-admin-secret-key'
axios.defaults.headers.common['X-API-Key'] = API_KEY

// Add response interceptor for auth errors
axios.interceptors.response.use(
    response => response,
    error => {
        if (error.response?.status === 401 || error.response?.status === 403) {
            console.error('API Authentication failed. Check API key.')
        }
        return Promise.reject(error)
    }
)

const app = createApp(App)

// Register all Element Plus icons
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
    app.component(key, component)
}

app.use(ElementPlus)
app.use(router)
app.mount('#app')
