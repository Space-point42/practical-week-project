<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { initialOf, avatarColor } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const collapsed = ref(false)
const globalKeyword = ref('')

/** 侧边栏菜单由路由表的 meta 生成，新增页面只需改路由配置 */
const menuGroups = computed(() => {
  const all = router.getRoutes().filter((r) => r.meta?.title && !r.meta.hidden)
  const pick = (group) => all.filter((r) => r.meta.group === group)

  const groups = [
    { key: 'main', label: '知识中心', items: pick('main') },
    { key: 'manage', label: '内容管理', items: pick('manage') },
    { key: 'admin', label: '系统管理', items: pick('admin') }
  ]

  return groups
    .map((g) => ({
      ...g,
      items: g.items.filter((item) => {
        if (item.meta.requiresAdmin) return userStore.isAdmin
        if (item.meta.requiresEdit) return userStore.canEdit
        return true
      })
    }))
    .filter((g) => g.items.length > 0)
})

const activeMenu = computed(() => {
  // 详情页/编辑页高亮其所属的列表菜单
  if (route.path.startsWith('/knowledge/')) return '/knowledge'
  if (route.path.startsWith('/editor/')) return '/knowledge'
  return route.path
})

const breadcrumbs = computed(() => {
  const crumbs = [{ title: '首页', path: '/dashboard' }]
  if (route.path !== '/dashboard') {
    crumbs.push({ title: route.meta.title || '', path: '' })
  }
  return crumbs
})

function handleSearch() {
  const kw = globalKeyword.value.trim()
  if (!kw) return
  router.push({ path: '/search', query: { keyword: kw } })
}

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
    userStore.logout()
    router.push('/login')
  } catch {
    /* 用户取消 */
  }
}

function goProfile(command) {
  if (command === 'logout') handleLogout()
  else if (command === 'profile') router.push('/profile')
}
</script>

<template>
  <div class="app-layout">
    <!-- ============ 侧边栏 ============ -->
    <aside class="sidebar" :class="{ collapsed }">
      <div class="brand" @click="router.push('/dashboard')">
        <div class="brand-mark">知</div>
        <transition name="fade">
          <div v-show="!collapsed" class="brand-text">
            <div class="brand-name">知识管理系统</div>
            <div class="brand-sub">专业课程设计 II</div>
          </div>
        </transition>
      </div>

      <nav class="menu">
        <template v-for="group in menuGroups" :key="group.key">
          <div v-show="!collapsed" class="menu-group-label">{{ group.label }}</div>
          <router-link
            v-for="item in group.items"
            :key="item.path"
            :to="item.path"
            class="menu-item"
            :class="{ active: activeMenu === item.path }"
            :title="collapsed ? item.meta.title : ''"
          >
            <el-icon :size="17"><component :is="item.meta.icon" /></el-icon>
            <span v-show="!collapsed" class="menu-text">{{ item.meta.title }}</span>
          </router-link>
        </template>
      </nav>

      <div class="sidebar-foot">
        <el-tooltip :content="collapsed ? '展开侧边栏' : '收起侧边栏'" placement="right">
          <div class="collapse-btn" @click="collapsed = !collapsed">
            <el-icon :size="16">
              <component :is="collapsed ? 'DArrowRight' : 'DArrowLeft'" />
            </el-icon>
            <span v-show="!collapsed">收起</span>
          </div>
        </el-tooltip>
      </div>
    </aside>

    <!-- ============ 主区 ============ -->
    <div class="main-area">
      <header class="topbar no-print">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item v-for="c in breadcrumbs" :key="c.title" :to="c.path || undefined">
            {{ c.title }}
          </el-breadcrumb-item>
        </el-breadcrumb>

        <div class="topbar-right">
          <el-input
            v-model="globalKeyword"
            placeholder="搜索知识库……"
            class="global-search"
            clearable
            @keyup.enter="handleSearch"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>

          <el-tooltip content="智能问答（RAG）" placement="bottom">
            <el-button circle text @click="router.push('/rag')">
              <el-icon :size="17"><ChatDotRound /></el-icon>
            </el-button>
          </el-tooltip>

          <el-dropdown @command="goProfile">
            <div class="user-chip">
              <div class="avatar" :style="{ background: avatarColor(userStore.user?.id) }">
                {{ initialOf(userStore.displayName) }}
              </div>
              <div class="user-info">
                <div class="user-name">{{ userStore.displayName }}</div>
                <div class="user-role">
                  {{ userStore.isAdmin ? '管理员' : userStore.canEdit ? '编辑' : '浏览者' }}
                </div>
              </div>
              <el-icon :size="12"><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">
                  <el-icon><User /></el-icon> 个人中心
                </el-dropdown-item>
                <el-dropdown-item command="logout" divided>
                  <el-icon><SwitchButton /></el-icon> 退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="content">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<style scoped>
.app-layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

/* ---------------- 侧边栏 ---------------- */
.sidebar {
  width: var(--sidebar-width);
  flex-shrink: 0;
  background: #fff;
  border-right: 1px solid var(--hairline);
  display: flex;
  flex-direction: column;
  transition: width 0.2s ease;
  overflow: hidden;

  &.collapsed {
    width: 64px;
  }
}

.brand {
  height: var(--header-height);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 16px;
  cursor: pointer;
  flex-shrink: 0;
  border-bottom: 1px solid var(--hairline);
}

.brand-mark {
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border-radius: 8px;
  background: linear-gradient(135deg, #2a78d6, #4a3aa7);
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

.brand-text {
  white-space: nowrap;
}

.brand-name {
  font-size: 14px;
  font-weight: 650;
  line-height: 1.2;
}

.brand-sub {
  font-size: 11px;
  color: var(--ink-muted);
  margin-top: 1px;
}

.menu {
  flex: 1;
  overflow-y: auto;
  padding: 12px 8px;
}

.menu-group-label {
  font-size: 11px;
  color: var(--ink-muted);
  padding: 12px 10px 6px;
  letter-spacing: 0.04em;
  white-space: nowrap;
}

.menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 40px;
  padding: 0 10px;
  margin-bottom: 2px;
  border-radius: 8px;
  color: var(--ink-secondary);
  font-size: 14px;
  white-space: nowrap;
  transition: background 0.14s ease, color 0.14s ease;

  &:hover {
    background: var(--page-plane);
    color: var(--ink-primary);
  }

  &.active {
    background: var(--app-primary-soft);
    color: var(--app-primary);
    font-weight: 600;
  }
}

.menu-text {
  overflow: hidden;
  text-overflow: ellipsis;
}

.sidebar-foot {
  border-top: 1px solid var(--hairline);
  padding: 8px;
}

.collapse-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 34px;
  border-radius: 8px;
  font-size: 13px;
  color: var(--ink-muted);
  cursor: pointer;

  &:hover {
    background: var(--page-plane);
    color: var(--ink-primary);
  }
}

/* ---------------- 主区 ---------------- */
.main-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.topbar {
  height: var(--header-height);
  flex-shrink: 0;
  background: #fff;
  border-bottom: 1px solid var(--hairline);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 24px;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.global-search {
  width: 260px;

  :deep(.el-input__wrapper) {
    border-radius: 18px;
    background: var(--page-plane);
    box-shadow: none;
    border: 1px solid transparent;

    &:hover,
    &.is-focus {
      border-color: var(--series-1);
      background: #fff;
    }
  }
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px 4px 4px;
  border-radius: 20px;
  cursor: pointer;
  outline: none;
  transition: background 0.14s ease;

  &:hover {
    background: var(--page-plane);
  }
}

.avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.user-info {
  line-height: 1.25;
}

.user-name {
  font-size: 13px;
  font-weight: 550;
}

.user-role {
  font-size: 11px;
  color: var(--ink-muted);
}

.content {
  flex: 1;
  overflow-y: auto;
  background: var(--page-plane);
}

@media (max-width: 900px) {
  .global-search {
    width: 150px;
  }

  .user-info {
    display: none;
  }
}
</style>
