import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一的 axios 实例。
 *
 * 约定：后端返回 { code, message, data }，code === 0 表示成功。
 * 拦截器负责三件事：
 *   1. 自动附加 JWT；
 *   2. 把 code !== 0 的响应转成 reject，业务代码只需处理成功分支；
 *   3. 401 统一跳转登录，避免每个页面各写一遍。
 */
const request = axios.create({
  baseURL: '/api',
  timeout: 180000
})

/** 避免 token 失效时连续弹出多个提示 */
let lastAuthToastAt = 0

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('kms_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

request.interceptors.response.use(
  (response) => {
    const body = response.data
    // 非统一结构（如文件下载）直接返回原始响应
    if (body === null || typeof body !== 'object' || !('code' in body)) {
      return response
    }
    if (body.code === 0) {
      return body
    }
    // 业务错误：抛出，让调用方的 catch 处理
    const error = new Error(body.message || '请求失败')
    error.code = body.code
    error.silent = body.code === 401
    return Promise.reject(error)
  },
  (error) => {
    const status = error.response?.status
    const body = error.response?.data
    const message = body?.message || error.message || '网络异常'

    if (status === 401) {
      const now = Date.now()
      if (now - lastAuthToastAt > 3000) {
        lastAuthToastAt = now
        ElMessage.warning('登录已过期，请重新登录')
      }
      localStorage.removeItem('kms_token')
      localStorage.removeItem('kms_user')
      // 用 replace 避免用户回退到需要登录的页面
      const redirect = encodeURIComponent(window.location.hash.replace(/^#/, '') || '/')
      if (!window.location.hash.startsWith('#/login')) {
        window.location.replace(`#/login?redirect=${redirect}`)
      }
    } else if (status === 403) {
      ElMessage.error('无权限执行该操作')
    } else {
      ElMessage.error(message)
    }
    return Promise.reject(error)
  }
)

export default request
