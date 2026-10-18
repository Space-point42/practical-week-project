import { createRouter, createWebHashHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

/**
 * 路由表。
 *
 * 采用 hash 模式：部署到任意静态服务器（含直接双击打开）都能工作，
 * 无需配置服务端 rewrite —— 对课程设计的答辩演示环境更友好。
 *
 * meta.requiresAuth / meta.requiresRole 由全局前置守卫检查。
 */
const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/',
    component: () => import('@/layout/AppLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '首页看板', icon: 'DataBoard', group: 'main' }
      },
      {
        path: 'knowledge',
        name: 'knowledge',
        component: () => import('@/views/KnowledgeListView.vue'),
        meta: { title: '知识库', icon: 'Collection', group: 'main' }
      },
      {
        path: 'knowledge/:id',
        name: 'knowledge-detail',
        component: () => import('@/views/KnowledgeDetailView.vue'),
        meta: { title: '知识详情', hidden: true }
      },
      {
        path: 'knowledge/:id/versions',
        name: 'knowledge-versions',
        component: () => import('@/views/VersionHistoryView.vue'),
        meta: { title: '版本历史', hidden: true }
      },
      {
        path: 'editor',
        name: 'knowledge-create',
        component: () => import('@/views/KnowledgeEditView.vue'),
        meta: { title: '新建知识', requiresEdit: true, hidden: true }
      },
      {
        path: 'editor/:id',
        name: 'knowledge-edit',
        component: () => import('@/views/KnowledgeEditView.vue'),
        meta: { title: '编辑知识', requiresEdit: true, hidden: true }
      },
      {
        path: 'ontology',
        name: 'ontology',
        component: () => import('@/views/OntologyGraphView.vue'),
        meta: { title: '知识图谱', icon: 'Share', group: 'main' }
      },
      {
        path: 'ontology/concepts',
        name: 'ontology-concepts',
        component: () => import('@/views/OntologyConceptView.vue'),
        meta: { title: '知识点管理', icon: 'Grid', group: 'main' }
      },
      {
        path: 'search',
        name: 'search',
        component: () => import('@/views/SearchView.vue'),
        meta: { title: '高级检索', icon: 'Search', group: 'main' }
      },
      {
        path: 'rag',
        name: 'rag',
        component: () => import('@/views/RagChatView.vue'),
        meta: { title: '智能问答', icon: 'ChatDotRound', group: 'main' }
      },
      {
        path: 'archive',
        name: 'archive',
        component: () => import('@/views/ArchiveView.vue'),
        meta: { title: '归档管理', icon: 'Box', requiresEdit: true, group: 'manage' }
      },
      {
        path: 'my',
        name: 'my-content',
        component: () => import('@/views/MyContentView.vue'),
        meta: { title: '我的内容', icon: 'Files', requiresEdit: true, group: 'manage' }
      },
      {
        path: 'admin/users',
        name: 'admin-users',
        component: () => import('@/views/admin/UserManageView.vue'),
        meta: { title: '用户管理', icon: 'UserFilled', requiresAdmin: true, group: 'admin' }
      },
      {
        path: 'admin/policies',
        name: 'admin-policies',
        component: () => import('@/views/admin/VersionPolicyView.vue'),
        meta: { title: '版本策略', icon: 'Timer', requiresAdmin: true, group: 'admin' }
      },
      {
        path: 'admin/index',
        name: 'admin-index',
        component: () => import('@/views/admin/IndexStatusView.vue'),
        meta: { title: '检索索引', icon: 'Connection', requiresAdmin: true, group: 'admin' }
      },
      {
        path: 'admin/audit',
        name: 'admin-audit',
        component: () => import('@/views/admin/AuditLogView.vue'),
        meta: { title: '审计日志', icon: 'Document', requiresAdmin: true, group: 'admin' }
      },
      {
        path: 'admin/search-stats',
        name: 'admin-search-stats',
        component: () => import('@/views/admin/SearchStatsView.vue'),
        meta: { title: '检索分析', icon: 'TrendCharts', requiresAdmin: true, group: 'admin' }
      },
      {
        path: 'profile',
        name: 'profile',
        component: () => import('@/views/ProfileView.vue'),
        meta: { title: '个人中心', hidden: true }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在', public: true }
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach((to) => {
  const userStore = useUserStore()
  document.title = to.meta.title ? `${to.meta.title} · 知识管理系统` : '知识管理系统'

  if (to.meta.public) {
    return true
  }

  if (!userStore.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (to.meta.requiresAdmin && !userStore.isAdmin) {
    return { name: 'dashboard' }
  }

  if (to.meta.requiresEdit && !userStore.canEdit) {
    return { name: 'dashboard' }
  }

  return true
})

export default router
