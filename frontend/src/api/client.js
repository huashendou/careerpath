import axios from 'axios'

export const TOKEN_KEY = 'sr_token'

/**
 * 后端契约（看板 c2.1）：
 *  - 成功：直接返回资源对象，无 code/data 封套
 *  - 失败：HTTP 4xx/5xx + { detail: { code, message } }
 *  - 校验失败：FastAPI 原生 422 + { detail: [{ loc, msg, type }] }
 */
export class ApiError extends Error {
  constructor(code, message, status) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

const http = axios.create({ baseURL: '/api', timeout: 20000 })

http.interceptors.request.use((cfg) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) cfg.headers.Authorization = `Bearer ${token}`
  return cfg
})

http.interceptors.response.use(
  (res) => res.data,
  (err) => {
    const res = err.response
    if (!res) {
      throw new ApiError('NETWORK', '连接不上服务，请确认后端已启动（127.0.0.1:8000）', 0)
    }
    const detail = res.data && res.data.detail
    let code = 'UNKNOWN'
    let message = `请求失败（${res.status}）`

    if (typeof detail === 'string') {
      message = detail
    } else if (Array.isArray(detail)) {
      code = 'VALIDATION'
      message = detail
        .map((d) => {
          const field = Array.isArray(d.loc) ? d.loc.slice(1).join('.') : ''
          return field ? `${field}：${d.msg}` : d.msg
        })
        .join('；')
    } else if (detail && typeof detail === 'object') {
      code = detail.code || code
      message = detail.message || message
    }

    if (res.status === 401) localStorage.removeItem(TOKEN_KEY)
    throw new ApiError(code, message, res.status)
  },
)

export default http
