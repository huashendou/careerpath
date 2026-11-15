import axios from 'axios'

export const TOKEN_KEY = 'cp_token'

/**
 * 后端契约（c3.0）：
 *  - 成功：直接返回资源对象，无 code/data 封套
 *  - 失败：HTTP 4xx/5xx + { detail: { code, message } }
 *  - 分页：{ items, total, page, size }
 *
 * 这里只做三件事：带令牌、透传 traceId、把错误体归一成 ApiError。
 * 业务判断不写进拦截器，否则同一份逻辑会散落到各处。
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
  // 与后端 traceId 过滤器对齐，出问题时一句话定位两进程日志
  cfg.headers['X-Trace-Id'] = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
  return cfg
})

http.interceptors.response.use(
  (res) => res.data,
  (err) => {
    const res = err.response
    if (!res) {
      throw new ApiError('NETWORK', '连接不上服务，请确认 Java 服务已启动（127.0.0.1:8080）', 0)
    }
    const detail = res.data && res.data.detail
    let code = 'UNKNOWN'
    let message = `请求失败（${res.status}）`

    if (detail && typeof detail === 'object' && !Array.isArray(detail)) {
      code = detail.code || code
      message = detail.message || message
    } else if (typeof detail === 'string') {
      message = detail
    }

    // 令牌失效就地清掉，避免后续请求持续失败
    if (res.status === 401) localStorage.removeItem(TOKEN_KEY)
    throw new ApiError(code, message, res.status)
  },
)

export default http
