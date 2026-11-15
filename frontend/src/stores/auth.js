import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import http, { TOKEN_KEY } from '../api/client'

/**
 * 登录态。
 *
 * T0 只落骨架：接口 404/401 时不会崩，但也不会假装登录成功。
 * T1 接 /api/auth/register|login|me 后即可用。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const user = ref(null)

  const isLoggedIn = computed(() => Boolean(token.value))
  const displayName = computed(() => (user.value ? user.value.username : ''))

  function setToken(value) {
    token.value = value || ''
    if (value) localStorage.setItem(TOKEN_KEY, value)
    else localStorage.removeItem(TOKEN_KEY)
  }

  async function register(username, password) {
    const data = await http.post('/auth/register', { username, password })
    setToken(data.accessToken)
    user.value = data.user
    return data.user
  }

  async function login(username, password) {
    const data = await http.post('/auth/login', { username, password })
    setToken(data.accessToken)
    user.value = data.user
    return data.user
  }

  async function fetchMe() {
    if (!token.value) return null
    user.value = await http.get('/auth/me')
    return user.value
  }

  function logout() {
    setToken('')
    user.value = null
  }

  return { token, user, isLoggedIn, displayName, register, login, fetchMe, logout }
})
