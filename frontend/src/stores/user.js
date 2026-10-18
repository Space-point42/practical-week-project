import { defineStore } from 'pinia'
import { authApi } from '@/api'

/**
 * 登录状态与权限。
 *
 * 角色模型：ADMIN（管理员）/ EDITOR（编辑）/ VIEWER（浏览者）。
 * 前端只做界面可见性控制，真正的权限校验在后端 ——
 * 前端隐藏按钮是为了体验，不是为了安全。
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('kms_token') || '',
    user: JSON.parse(localStorage.getItem('kms_user') || 'null')
  }),

  getters: {
    isLoggedIn: (state) => Boolean(state.token),
    roles: (state) => state.user?.roles || [],
    isAdmin() {
      return this.roles.includes('ADMIN')
    },
    canEdit() {
      return this.roles.includes('ADMIN') || this.roles.includes('EDITOR')
    },
    displayName: (state) => state.user?.realName || state.user?.username || '未登录'
  },

  actions: {
    async login(payload) {
      const data = await authApi.login(payload)
      this.token = data.token
      this.user = data.user
      localStorage.setItem('kms_token', data.token)
      localStorage.setItem('kms_user', JSON.stringify(data.user))
      return data
    },

    async fetchMe() {
      const user = await authApi.me()
      this.user = user
      localStorage.setItem('kms_user', JSON.stringify(user))
      return user
    },

    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem('kms_token')
      localStorage.removeItem('kms_user')
    }
  }
})
