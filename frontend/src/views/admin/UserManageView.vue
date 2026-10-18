<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { userApi } from '@/api'
import { avatarColor, formatDateTime, initialOf } from '@/utils/format'

/**
 * 用户管理。
 *
 * 角色 code → 中文名的映射同时用于表格与下拉；后端角色表是权威来源，
 * 这里保留一份兜底文案，避免接口尚未返回时界面露出英文 code。
 */
const ROLE_TEXT = { ADMIN: '管理员', EDITOR: '编辑', VIEWER: '浏览者' }
const ROLE_TAG = { ADMIN: 'danger', EDITOR: 'primary', VIEWER: 'info' }

const loading = ref(false)
const users = ref([])
const total = ref(0)
const roleOptions = ref([])

const query = reactive({ keyword: '', status: '', page: 1, size: 10 })

const statusOptions = [
  { value: '1', label: '启用' },
  { value: '0', label: '停用' }
]

function roleName(code) {
  return ROLE_TEXT[code] || roleOptions.value.find((r) => r.code === code)?.name || code
}

onMounted(() => {
  loadRoles()
  load()
})

async function loadRoles() {
  try {
    roleOptions.value = (await userApi.roles()) || []
  } catch {
    /* 角色列表加载失败不阻塞主表格，表格内仍有兜底文案 */
  }
}

async function load() {
  loading.value = true
  try {
    const res = await userApi.page({
      keyword: query.keyword || undefined,
      status: query.status === '' ? undefined : query.status,
      page: query.page,
      size: query.size
    })
    users.value = res.list || []
    total.value = res.total || 0
  } finally {
    loading.value = false
  }
}

function applyFilter() {
  query.page = 1
  load()
}

function resetFilter() {
  query.keyword = ''
  query.status = ''
  query.page = 1
  load()
}

/** 更新接口要求提交完整字段，用行数据拼出请求体后覆盖指定字段 */
function buildPayload(user, overrides = {}) {
  return {
    username: user.username,
    realName: user.realName,
    email: user.email || '',
    department: user.department || '',
    status: user.status,
    roles: [...(user.roles || [])],
    ...overrides
  }
}

// ---------------------------------------------------------------------
// 新建 / 编辑
// ---------------------------------------------------------------------
const dialogVisible = ref(false)
const editingId = ref(null)
const submitting = ref(false)
const formRef = ref(null)

const form = reactive({
  username: '',
  password: '',
  realName: '',
  email: '',
  department: '',
  status: 1,
  roles: []
})

const isEdit = computed(() => editingId.value !== null)
const dialogTitle = computed(() => (isEdit.value ? '编辑用户' : '新建用户'))

// 口令仅在新建时必填；编辑时留空表示不改动
const rules = computed(() => ({
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '用户名长度需为 3 - 32 个字符', trigger: 'blur' }
  ],
  password: isEdit.value
    ? []
    : [
        { required: true, message: '请输入初始口令', trigger: 'blur' },
        { min: 6, message: '口令至少 6 位', trigger: 'blur' }
      ],
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  roles: [{ type: 'array', required: true, message: '请至少选择一个角色', trigger: 'change' }]
}))

function resetForm() {
  Object.assign(form, {
    username: '',
    password: '',
    realName: '',
    email: '',
    department: '',
    status: 1,
    roles: ['VIEWER']
  })
  nextTick(() => formRef.value?.clearValidate())
}

function openCreate() {
  editingId.value = null
  resetForm()
  dialogVisible.value = true
}

async function openEdit(row) {
  try {
    // 列表数据可能裁剪过字段，编辑前取回完整明细
    const detail = await userApi.get(row.id)
    editingId.value = detail.id
    Object.assign(form, {
      username: detail.username,
      password: '',
      realName: detail.realName || '',
      email: detail.email || '',
      department: detail.department || '',
      status: detail.status ?? 1,
      roles: [...(detail.roles || [])]
    })
    dialogVisible.value = true
    nextTick(() => formRef.value?.clearValidate())
  } catch {
    /* 错误提示已由拦截器给出 */
  }
}

async function submit() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    const payload = {
      username: form.username,
      realName: form.realName,
      email: form.email || '',
      department: form.department || '',
      status: form.status,
      roles: form.roles
    }
    // 编辑时留空即不改口令，不把空串发往后端
    if (form.password) payload.password = form.password

    if (isEdit.value) {
      await userApi.update(editingId.value, payload)
      ElMessage.success('已保存')
    } else {
      await userApi.create(payload)
      ElMessage.success('用户已创建')
    }
    dialogVisible.value = false
    load()
  } catch {
    /* 校验失败或后端拒绝，提示均已处理 */
  } finally {
    submitting.value = false
  }
}

// ---------------------------------------------------------------------
// 行内操作
// ---------------------------------------------------------------------

/** el-switch 的 before-change：先确认再落库，任一步失败都不改变开关状态 */
async function beforeStatusChange(row) {
  const next = row.status === 1 ? 0 : 1
  try {
    await ElMessageBox.confirm(
      `确定${next === 1 ? '启用' : '停用'}用户「${row.realName || row.username}」吗？`,
      '状态变更确认',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    )
  } catch {
    return false
  }
  try {
    await userApi.update(row.id, buildPayload(row, { status: next }))
    ElMessage.success(next === 1 ? '已启用' : '已停用')
    load()
    return true
  } catch {
    return false
  }
}

async function resetPassword(row) {
  let newPassword = ''
  try {
    const res = await ElMessageBox.prompt(
      `为用户「${row.realName || row.username}」设置新口令：`,
      '重置口令',
      {
        confirmButtonText: '确认重置',
        cancelButtonText: '取消',
        inputType: 'password',
        inputPlaceholder: '请输入新口令（至少 6 位）',
        inputValidator: (v) => (v && v.length >= 6 ? true : '口令至少 6 位')
      }
    )
    newPassword = res.value
  } catch {
    return
  }
  try {
    // 更新接口是整体覆盖，先取回完整用户数据再附带新口令
    const detail = await userApi.get(row.id)
    await userApi.update(row.id, buildPayload(detail, { password: newPassword }))
    ElMessage.success('口令已重置')
  } catch {
    /* 拦截器已提示 */
  }
}

async function handleRemove(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除用户「${row.realName || row.username}」吗？删除后该账号将无法登录，操作不可恢复。`,
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch {
    return
  }
  try {
    await userApi.remove(row.id)
    ElMessage.success('已删除')
    load()
  } catch {
    /* 拦截器已提示 */
  }
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h1 class="page-title">用户管理</h1>
        <p class="page-subtitle">共 {{ total }} 个账号，可按用户名 / 姓名 / 邮箱检索并调整状态与角色</p>
      </div>
      <el-button type="primary" :icon="'Plus'" @click="openCreate">新建用户</el-button>
    </div>

    <!-- ============ 筛选 ============ -->
    <div class="card filter-card">
      <el-input
        v-model="query.keyword"
        placeholder="用户名 / 姓名 / 邮箱"
        clearable
        class="filter-keyword"
        @keyup.enter="applyFilter"
        @clear="applyFilter"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-select v-model="query.status" placeholder="全部状态" clearable class="filter-status">
        <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>

      <el-button type="primary" :icon="'Search'" @click="applyFilter">查询</el-button>
      <el-button :icon="'RefreshLeft'" @click="resetFilter">重置</el-button>
    </div>

    <!-- ============ 列表 ============ -->
    <div class="card table-card">
      <el-table v-loading="loading" :data="users" row-key="id" stripe>
        <el-table-column label="用户" min-width="200">
          <template #default="{ row }">
            <div class="user-cell">
              <span class="avatar" :style="{ background: avatarColor(row.id) }">
                {{ initialOf(row.realName || row.username) }}
              </span>
              <div class="user-main">
                <div class="user-name">{{ row.realName || '未填写姓名' }}</div>
                <div class="user-sub">@{{ row.username }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="角色" min-width="150">
          <template #default="{ row }">
            <el-tag
              v-for="code in row.roles || []"
              :key="code"
              :type="ROLE_TAG[code] || 'info'"
              effect="plain"
              size="small"
              class="role-tag"
              disable-transitions
            >
              {{ roleName(code) }}
            </el-tag>
            <span v-if="!(row.roles || []).length" class="muted">未分配</span>
          </template>
        </el-table-column>

        <el-table-column prop="email" label="邮箱" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.email || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="department" label="单位 / 班级" min-width="150">
          <template #default="{ row }">
            <span>{{ row.department || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.status === 1"
              :before-change="() => beforeStatusChange(row)"
              inline-prompt
              active-text="启"
              inactive-text="停"
            />
          </template>
        </el-table-column>

        <el-table-column label="最近登录" width="160">
          <template #default="{ row }">
            <span class="tabular muted">{{ formatDateTime(row.lastLoginAt) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="'Edit'" @click="openEdit(row)">编辑</el-button>
            <el-button link type="warning" :icon="'Key'" @click="resetPassword(row)">重置口令</el-button>
            <el-button link type="danger" :icon="'Delete'" @click="handleRemove(row)">删除</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div class="empty-hint">没有符合条件的用户</div>
        </template>
      </el-table>

      <div v-if="total > query.size" class="pagination-wrap">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="load"
          @size-change="applyFilter"
        />
      </div>
    </div>

    <!-- ============ 新建 / 编辑 ============ -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="520px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="92px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="isEdit" placeholder="登录账号，3 - 32 位" />
        </el-form-item>

        <el-form-item label="口令" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="isEdit ? '留空表示不修改口令' : '至少 6 位'"
          />
        </el-form-item>

        <el-form-item label="姓名" prop="realName">
          <el-input v-model="form.realName" placeholder="真实姓名" />
        </el-form-item>

        <el-form-item label="电子邮箱" prop="email">
          <el-input v-model="form.email" placeholder="选填" />
        </el-form-item>

        <el-form-item label="单位 / 班级" prop="department">
          <el-input v-model="form.department" placeholder="选填" />
        </el-form-item>

        <el-form-item label="角色" prop="roles">
          <el-select v-model="form.roles" multiple class="full-width" placeholder="至少选择一个角色">
            <el-option
              v-for="r in roleOptions"
              :key="r.code"
              :label="r.name"
              :value="r.code"
            >
              <span>{{ r.name }}</span>
              <span class="opt-desc">{{ r.description }}</span>
            </el-option>
          </el-select>
        </el-form-item>

        <el-form-item label="状态" prop="status">
          <el-switch
            v-model="form.status"
            :active-value="1"
            :inactive-value="0"
            inline-prompt
            active-text="启用"
            inactive-text="停用"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">
          {{ isEdit ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filter-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.filter-keyword {
  width: 300px;
}

.filter-status {
  width: 150px;
}

.table-card {
  padding: 8px 14px 14px;
}

.user-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 14px;
  font-weight: 600;
}

.user-main {
  min-width: 0;
}

.user-name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink-primary);
  line-height: 1.4;
}

.user-sub {
  font-size: 12px;
  color: var(--ink-muted);
}

.role-tag {
  margin: 2px 6px 2px 0;
}

.muted {
  color: var(--ink-muted);
  font-size: 12.5px;
}

.opt-desc {
  float: right;
  color: var(--ink-muted);
  font-size: 12px;
}

.full-width {
  width: 100%;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>
