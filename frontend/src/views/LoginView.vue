<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { authApi, statsApi, ontologyApi } from '@/api'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const mode = ref('login')
const loading = ref(false)
const formRef = ref()

const form = reactive({
  username: '',
  password: '',
  realName: '',
  email: '',
  department: ''
})

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '用户名长度 3~32 位', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' }
  ]
}

const demoAccounts = [
  { username: 'admin', password: 'admin123', role: '管理员', desc: '全部权限，含用户与本体管理' },
  { username: 'editor', password: 'editor123', role: '编辑', desc: '创建、编辑与发布知识内容' },
  { username: 'student', password: 'student123', role: '浏览者', desc: '检索、浏览与智能问答' }
]

const stats = reactive({ knowledgeCount: 0, conceptCount: 0, relationCount: 0 })

onMounted(async () => {
  // 登录页展示真实规模，让人一眼看出这是个有内容的系统而非空壳
  try {
    const [overview, ontology] = await Promise.all([statsApi.overview(), ontologyApi.stats()])
    stats.knowledgeCount = overview.knowledgeCount
    stats.conceptCount = ontology.conceptCount
    stats.relationCount = ontology.relationCount
  } catch {
    /* 后端未就绪时静默降级，不阻塞登录界面 */
  }
})

function fillAccount(account) {
  form.username = account.username
  form.password = account.password
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    if (mode.value === 'login') {
      const data = await userStore.login({ username: form.username, password: form.password })
      ElMessage.success(`欢迎回来，${data.user.realName || data.user.username}`)
      router.replace(route.query.redirect || '/dashboard')
    } else {
      await authApi.register({
        username: form.username,
        password: form.password,
        realName: form.realName,
        email: form.email,
        department: form.department
      })
      ElMessage.success('注册成功，请登录')
      mode.value = 'login'
    }
  } catch {
    /* 错误提示已由请求拦截器统一处理 */
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <!-- 左侧品牌区 -->
    <section class="brand-pane">
      <div class="brand-inner">
        <div class="brand-logo">
          <span>知</span>
        </div>
        <h1>知识管理系统</h1>
        <p class="brand-desc">
          面向《专业课程设计II》的课程知识平台 ——
          以知识本体组织内容结构，以全文与向量混合检索定位知识，
          以检索增强生成回答问题。
        </p>

        <div class="brand-stats">
          <div class="bs-item">
            <div class="bs-value tabular">{{ stats.knowledgeCount }}</div>
            <div class="bs-label">知识条目</div>
          </div>
          <div class="bs-divider" />
          <div class="bs-item">
            <div class="bs-value tabular">{{ stats.conceptCount }}</div>
            <div class="bs-label">知识点</div>
          </div>
          <div class="bs-divider" />
          <div class="bs-item">
            <div class="bs-value tabular">{{ stats.relationCount }}</div>
            <div class="bs-label">本体关系</div>
          </div>
        </div>

        <ul class="feature-list">
          <li><el-icon><Share /></el-icon> 本体驱动的知识图谱与学习路径推理</li>
          <li><el-icon><Search /></el-icon> ngram 全文检索 + 向量语义检索融合</li>
          <li><el-icon><ChatDotRound /></el-icon> RAG 问答，回答标注可核对的引用来源</li>
          <li><el-icon><Timer /></el-icon> 按天数/修改次数策略的版本管理</li>
        </ul>
      </div>
    </section>

    <!-- 右侧表单区 -->
    <section class="form-pane">
      <div class="form-box">
        <h2 class="form-title">{{ mode === 'login' ? '登录' : '注册新账号' }}</h2>
        <p class="form-sub">
          {{ mode === 'login' ? '请使用课程账号登录' : '注册后默认为浏览者角色' }}
        </p>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          @submit.prevent="handleSubmit"
        >
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" placeholder="请输入用户名" clearable>
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              show-password
              @keyup.enter="handleSubmit"
            >
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>

          <template v-if="mode === 'register'">
            <el-form-item label="姓名">
              <el-input v-model="form.realName" placeholder="请输入真实姓名" />
            </el-form-item>
            <el-form-item label="邮箱">
              <el-input v-model="form.email" placeholder="选填" />
            </el-form-item>
            <el-form-item label="单位 / 班级">
              <el-input v-model="form.department" placeholder="选填，如：计算机科学与技术系 计科2201" />
            </el-form-item>
          </template>

          <el-button
            type="primary"
            size="large"
            class="submit-btn"
            :loading="loading"
            @click="handleSubmit"
          >
            {{ mode === 'login' ? '登 录' : '注 册' }}
          </el-button>
        </el-form>

        <div class="mode-switch">
          <span v-if="mode === 'login'">
            还没有账号？<a @click="mode = 'register'">立即注册</a>
          </span>
          <span v-else>
            已有账号？<a @click="mode = 'login'">返回登录</a>
          </span>
        </div>

        <el-divider v-if="mode === 'login'" class="demo-divider">
          <span class="demo-divider-text">演示账号（点击填入）</span>
        </el-divider>

        <div v-if="mode === 'login'" class="demo-accounts">
          <div
            v-for="acc in demoAccounts"
            :key="acc.username"
            class="demo-account"
            @click="fillAccount(acc)"
          >
            <div class="da-head">
              <span class="da-role">{{ acc.role }}</span>
              <span class="da-cred tabular">{{ acc.username }} / {{ acc.password }}</span>
            </div>
            <div class="da-desc">{{ acc.desc }}</div>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

/* ---------------- 左侧品牌区 ---------------- */
.brand-pane {
  flex: 1.1;
  background: linear-gradient(150deg, #1c5cab 0%, #2a78d6 45%, #4a3aa7 100%);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px;
  position: relative;
  overflow: hidden;

  /* 用极淡的同心圆营造纵深，不干扰文字阅读 */
  &::after {
    content: '';
    position: absolute;
    right: -180px;
    bottom: -180px;
    width: 520px;
    height: 520px;
    border-radius: 50%;
    background: rgba(255, 255, 255, 0.06);
    box-shadow: 0 0 0 80px rgba(255, 255, 255, 0.04);
  }
}

.brand-inner {
  max-width: 480px;
  position: relative;
  z-index: 1;
}

.brand-logo {
  width: 56px;
  height: 56px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.16);
  border: 1px solid rgba(255, 255, 255, 0.28);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
  font-weight: 700;
  margin-bottom: 24px;
}

.brand-pane h1 {
  font-size: 34px;
  font-weight: 700;
  margin: 0 0 14px;
  letter-spacing: -0.02em;
}

.brand-desc {
  font-size: 14px;
  line-height: 1.9;
  color: rgba(255, 255, 255, 0.86);
  margin: 0 0 32px;
}

.brand-stats {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 18px 22px;
  background: rgba(255, 255, 255, 0.10);
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: 12px;
  margin-bottom: 32px;
}

.bs-item {
  text-align: center;
  flex: 1;
}

.bs-value {
  font-size: 24px;
  font-weight: 700;
  line-height: 1.2;
}

.bs-label {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.75);
  margin-top: 2px;
}

.bs-divider {
  width: 1px;
  height: 32px;
  background: rgba(255, 255, 255, 0.2);
}

.feature-list {
  list-style: none;
  padding: 0;
  margin: 0;

  li {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 13.5px;
    color: rgba(255, 255, 255, 0.9);
    padding: 7px 0;
  }
}

/* ---------------- 右侧表单区 ---------------- */
.form-pane {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
  background: #fff;
  overflow-y: auto;
}

.form-box {
  width: 100%;
  max-width: 380px;
}

.form-title {
  font-size: 24px;
  font-weight: 650;
  margin: 0 0 6px;
  letter-spacing: -0.01em;
}

.form-sub {
  font-size: 13px;
  color: var(--ink-muted);
  margin: 0 0 28px;
}

.submit-btn {
  width: 100%;
  margin-top: 8px;
  letter-spacing: 0.2em;
}

.mode-switch {
  text-align: center;
  font-size: 13px;
  color: var(--ink-muted);
  margin-top: 18px;

  a {
    cursor: pointer;
    font-weight: 500;

    &:hover {
      text-decoration: underline;
    }
  }
}

.demo-divider {
  margin: 26px 0 16px;

  :deep(.el-divider__text) {
    background: #fff;
  }
}

.demo-divider-text {
  font-size: 12px;
  color: var(--ink-muted);
  font-weight: 400;
}

.demo-accounts {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.demo-account {
  border: 1px solid var(--hairline);
  border-radius: 8px;
  padding: 9px 12px;
  cursor: pointer;
  transition: border-color 0.14s ease, background 0.14s ease;

  &:hover {
    border-color: var(--series-1);
    background: var(--app-primary-soft);
  }
}

.da-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.da-role {
  font-size: 13px;
  font-weight: 600;
}

.da-cred {
  font-size: 12px;
  color: var(--ink-muted);
}

.da-desc {
  font-size: 12px;
  color: var(--ink-muted);
  margin-top: 2px;
}

@media (max-width: 900px) {
  .brand-pane {
    display: none;
  }
}
</style>
