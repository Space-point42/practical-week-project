<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { authApi, ragApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { avatarColor, initialOf, formatDateTime } from '@/utils/format'

const userStore = useUserStore()

const loading = ref(false)
const savingProfile = ref(false)
const changingPassword = ref(false)
const sessions = ref([])

const profileForm = reactive({ realName: '', email: '', department: '' })
const passwordForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const profileRef = ref()
const passwordRef = ref()

const ROLE_TEXT = { ADMIN: '管理员', EDITOR: '编辑', VIEWER: '浏览者' }
const ROLE_DESC = {
  ADMIN: '拥有全部权限：用户管理、本体维护、版本策略配置、归档与索引重建',
  EDITOR: '可创建、编辑、提交与发布知识内容，管理版本历史',
  VIEWER: '可检索、浏览、下载知识内容，参与智能问答'
}

const profileRules = {
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }]
}

const passwordRules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 64, message: '密码长度 6~64 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== passwordForm.newPassword) callback(new Error('两次输入的密码不一致'))
        else callback()
      },
      trigger: 'blur'
    }
  ]
}

const roleList = computed(() => userStore.roles || [])

onMounted(async () => {
  loading.value = true
  try {
    const me = await userStore.fetchMe()
    profileForm.realName = me.realName || ''
    profileForm.email = me.email || ''
    profileForm.department = me.department || ''
    sessions.value = (await ragApi.sessions().catch(() => [])) || []
  } finally {
    loading.value = false
  }
})

async function saveProfile() {
  const valid = await profileRef.value?.validate().catch(() => false)
  if (!valid) return
  savingProfile.value = true
  try {
    await authApi.updateProfile({ ...profileForm })
    await userStore.fetchMe()
    ElMessage.success('资料已更新')
  } finally {
    savingProfile.value = false
  }
}

async function changePassword() {
  const valid = await passwordRef.value?.validate().catch(() => false)
  if (!valid) return
  changingPassword.value = true
  try {
    await authApi.changePassword({
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword
    })
    ElMessage.success('口令已修改，下次登录请使用新口令')
    passwordForm.oldPassword = ''
    passwordForm.newPassword = ''
    passwordForm.confirmPassword = ''
  } finally {
    changingPassword.value = false
  }
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h1 class="page-title">个人中心</h1>
        <p class="page-subtitle">查看账号信息、维护个人资料与修改登录口令</p>
      </div>
    </div>

    <div v-loading="loading" class="profile-layout">
      <!-- ============ 左侧概览 ============ -->
      <aside class="profile-side">
        <div class="card identity-card">
          <div class="avatar-big" :style="{ background: avatarColor(userStore.user?.id) }">
            {{ initialOf(userStore.displayName) }}
          </div>
          <div class="id-name">{{ userStore.displayName }}</div>
          <div class="id-username">@{{ userStore.user?.username }}</div>

          <div class="id-roles">
            <el-tag v-for="r in roleList" :key="r" type="primary" effect="light" disable-transitions>
              {{ ROLE_TEXT[r] || r }}
            </el-tag>
            <el-tag v-if="!roleList.length" type="info" effect="plain">未分配角色</el-tag>
          </div>

          <div class="id-desc">
            <template v-for="r in roleList" :key="r">{{ ROLE_DESC[r] }}</template>
          </div>

          <el-divider />

          <div class="id-row">
            <span class="ir-label">邮箱</span>
            <span class="ir-value">{{ userStore.user?.email || '—' }}</span>
          </div>
          <div class="id-row">
            <span class="ir-label">单位 / 班级</span>
            <span class="ir-value">{{ userStore.user?.department || '—' }}</span>
          </div>
          <div class="id-row">
            <span class="ir-label">最近登录</span>
            <span class="ir-value">{{ formatDateTime(userStore.user?.lastLoginAt) }}</span>
          </div>
          <div class="id-row">
            <span class="ir-label">注册时间</span>
            <span class="ir-value">{{ formatDateTime(userStore.user?.createdAt) }}</span>
          </div>
          <div class="id-row">
            <span class="ir-label">问答会话</span>
            <span class="ir-value">{{ sessions.length }} 个</span>
          </div>
        </div>
      </aside>

      <!-- ============ 右侧表单 ============ -->
      <div class="profile-main">
        <div class="card form-card">
          <h3 class="section-title">个人资料</h3>
          <el-form ref="profileRef" :model="profileForm" :rules="profileRules" label-width="100px">
            <el-form-item label="用户名">
              <el-input :model-value="userStore.user?.username" disabled />
              <div class="form-hint">用户名用于登录，创建后不可修改</div>
            </el-form-item>
            <el-form-item label="姓名" prop="realName">
              <el-input v-model="profileForm.realName" placeholder="请输入真实姓名" />
            </el-form-item>
            <el-form-item label="邮箱">
              <el-input v-model="profileForm.email" placeholder="选填" />
            </el-form-item>
            <el-form-item label="单位 / 班级">
              <el-input v-model="profileForm.department" placeholder="选填，如：计算机科学与技术系 计科2201" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="savingProfile" @click="saveProfile">保存资料</el-button>
            </el-form-item>
          </el-form>
        </div>

        <div class="card form-card">
          <h3 class="section-title">修改口令</h3>
          <el-form ref="passwordRef" :model="passwordForm" :rules="passwordRules" label-width="100px">
            <el-form-item label="当前口令" prop="oldPassword">
              <el-input v-model="passwordForm.oldPassword" type="password" show-password autocomplete="off" />
            </el-form-item>
            <el-form-item label="新口令" prop="newPassword">
              <el-input v-model="passwordForm.newPassword" type="password" show-password autocomplete="off" />
            </el-form-item>
            <el-form-item label="确认新口令" prop="confirmPassword">
              <el-input v-model="passwordForm.confirmPassword" type="password" show-password autocomplete="off" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="changingPassword" @click="changePassword">
                修改口令
              </el-button>
              <span class="form-hint inline">修改后当前登录仍然有效，下次登录需使用新口令</span>
            </el-form-item>
          </el-form>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.profile-layout {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}

.profile-side {
  width: 300px;
  flex-shrink: 0;
}

.profile-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.identity-card {
  padding: 24px 20px;
  text-align: center;
}

.avatar-big {
  width: 68px;
  height: 68px;
  border-radius: 18px;
  color: #fff;
  font-size: 26px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto 14px;
}

.id-name {
  font-size: 17px;
  font-weight: 650;
}

.id-username {
  font-size: 12.5px;
  color: var(--ink-muted);
  margin-top: 3px;
}

.id-roles {
  display: flex;
  gap: 6px;
  justify-content: center;
  flex-wrap: wrap;
  margin: 14px 0 10px;
}

.id-desc {
  font-size: 12px;
  line-height: 1.75;
  color: var(--ink-muted);
  text-align: left;
  padding: 9px 12px;
  background: var(--page-plane);
  border-radius: 7px;
}

.id-row {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  font-size: 12.5px;
  padding: 5px 0;
  text-align: left;
}

.ir-label {
  color: var(--ink-muted);
  flex-shrink: 0;
}

.ir-value {
  text-align: right;
  word-break: break-all;
}

.form-card {
  padding: 18px 22px 6px;
}

.form-hint {
  font-size: 11.5px;
  color: var(--ink-muted);
  line-height: 1.6;
  margin-top: 4px;

  &.inline {
    display: inline;
    margin-left: 12px;
  }
}

@media (max-width: 1000px) {
  .profile-layout {
    flex-direction: column;
  }

  .profile-side {
    width: 100%;
  }
}
</style>
