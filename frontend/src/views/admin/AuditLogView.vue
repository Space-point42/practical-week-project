<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import { auditApi } from '@/api'
import { formatDateTime } from '@/utils/format'

/**
 * 审计日志。
 *
 * 操作与对象类型在库中存的是枚举 code，展示时要翻译成中文；翻译表与首页
 * 看板的动态流保持一致，避免同一动作在不同页面出现两种叫法。
 */
const ACTION_TEXT = {
  CREATE: '创建',
  UPDATE: '更新',
  DELETE: '删除',
  PUBLISH: '发布',
  UNPUBLISH: '撤回发布',
  SUBMIT: '提交审核',
  ARCHIVE: '归档',
  UNARCHIVE: '恢复归档',
  ROLLBACK: '版本回滚',
  CREATE_VERSION: '创建版本',
  LOGIN: '登录',
  REGISTER: '注册',
  CHANGE_PASSWORD: '修改口令'
}

const TARGET_TEXT = {
  ITEM: '知识条目',
  CONCEPT: '知识点',
  RELATION: '本体关系',
  CATEGORY: '分类',
  USER: '用户',
  POLICY: '版本策略'
}

const actionOptions = Object.entries(ACTION_TEXT).map(([value, label]) => ({ value, label }))
const targetOptions = Object.entries(TARGET_TEXT).map(([value, label]) => ({ value, label }))

// 破坏性动作与发布动作在视觉上区分开，方便快速扫读日志性质
const SUCCESS_ACTIONS = new Set(['PUBLISH'])
const DANGER_ACTIONS = new Set(['DELETE', 'UNPUBLISH', 'ARCHIVE', 'UNARCHIVE', 'ROLLBACK'])

function actionTagType(action) {
  if (SUCCESS_ACTIONS.has(action)) return 'success'
  if (DANGER_ACTIONS.has(action)) return 'danger'
  return 'info'
}

const loading = ref(false)
const logs = ref([])
const total = ref(0)

const query = reactive({
  action: '',
  targetType: '',
  username: '',
  range: null,
  page: 1,
  size: 20
})

const hasFilter = computed(
  () => Boolean(query.action) || Boolean(query.targetType) || Boolean(query.username) || Boolean(query.range)
)

onMounted(load)

async function load() {
  loading.value = true
  try {
    const params = {
      action: query.action || undefined,
      targetType: query.targetType || undefined,
      username: query.username || undefined,
      page: query.page,
      size: query.size
    }
    // 后端要求 yyyy-MM-dd HH:mm:ss 字符串
    if (query.range && query.range.length === 2) {
      params.from = dayjs(query.range[0]).format('YYYY-MM-DD HH:mm:ss')
      params.to = dayjs(query.range[1]).format('YYYY-MM-DD HH:mm:ss')
    }
    const res = await auditApi.page(params)
    logs.value = res.list || []
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
  query.action = ''
  query.targetType = ''
  query.username = ''
  query.range = null
  query.page = 1
  load()
}

function targetText(row) {
  const name = TARGET_TEXT[row.targetType] || row.targetType || ''
  return row.targetId ? `${name} #${row.targetId}` : name || '—'
}

// ---------------------------------------------------------------------
// 导出当前页为 CSV
// ---------------------------------------------------------------------
function csvCell(value) {
  const s = String(value ?? '')
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}

/** UTF-8 BOM：Excel 打开 CSV 时若缺少 BOM 会把中文显示为乱码 */
const BOM = '\uFEFF'

function exportCsv() {
  if (!logs.value.length) {
    return
  }
  const header = ['时间', '操作人', '操作', '对象类型', '对象ID', '详情', 'IP']
  const rows = logs.value.map((r) => [
    formatDateTime(r.createdAt),
    r.username || '',
    ACTION_TEXT[r.action] || r.action || '',
    TARGET_TEXT[r.targetType] || r.targetType || '',
    r.targetId ?? '',
    r.detail || '',
    r.ip || ''
  ])
  const csv = [header, ...rows].map((cols) => cols.map(csvCell).join(',')).join('\r\n')

  const blob = new Blob([BOM + csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `审计日志_${dayjs().format('YYYYMMDD_HHmmss')}.csv`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h1 class="page-title">审计日志</h1>
        <p class="page-subtitle">共 {{ total }} 条操作记录，可按操作类型、对象、操作人与时间范围检索</p>
      </div>
      <el-button :icon="'Download'" :disabled="!logs.length" @click="exportCsv">导出 CSV</el-button>
    </div>

    <!-- ============ 筛选 ============ -->
    <div class="card filter-card">
      <el-select v-model="query.action" placeholder="全部操作" clearable class="filter-item">
        <el-option v-for="a in actionOptions" :key="a.value" :label="a.label" :value="a.value" />
      </el-select>

      <el-select v-model="query.targetType" placeholder="全部对象" clearable class="filter-item">
        <el-option v-for="t in targetOptions" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>

      <el-input
        v-model="query.username"
        placeholder="操作人用户名"
        clearable
        class="filter-item"
        @keyup.enter="applyFilter"
        @clear="applyFilter"
      >
        <template #prefix><el-icon><User /></el-icon></template>
      </el-input>

      <el-date-picker
        v-model="query.range"
        type="datetimerange"
        range-separator="至"
        start-placeholder="开始时间"
        end-placeholder="结束时间"
        class="filter-range"
      />

      <el-button type="primary" :icon="'Search'" @click="applyFilter">查询</el-button>
      <el-button :icon="'RefreshLeft'" @click="resetFilter">重置</el-button>
    </div>

    <!-- ============ 列表 ============ -->
    <div class="card table-card">
      <el-table v-loading="loading" :data="logs" row-key="id" stripe>
        <el-table-column label="时间" width="160">
          <template #default="{ row }">
            <span class="tabular">{{ formatDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="username" label="操作人" width="130">
          <template #default="{ row }">
            <span>{{ row.username || '系统' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-tag :type="actionTagType(row.action)" effect="plain" size="small" disable-transitions>
              {{ ACTION_TEXT[row.action] || row.action }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="对象" min-width="150">
          <template #default="{ row }">
            <span>{{ targetText(row) }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="detail" label="详情" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.detail || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="ip" label="IP" width="150">
          <template #default="{ row }">
            <span class="tabular muted">{{ row.ip || '—' }}</span>
          </template>
        </el-table-column>

        <template #empty>
          <div class="empty-hint">
            {{ hasFilter ? '没有符合条件的时间记录' : '暂无审计记录' }}
          </div>
        </template>
      </el-table>

      <div v-if="total > query.size" class="pagination-wrap">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="load"
          @size-change="applyFilter"
        />
      </div>
    </div>
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

.filter-item {
  width: 170px;
}

.filter-range {
  width: 380px;
}

.table-card {
  padding: 8px 14px 14px;
}

.muted {
  color: var(--ink-muted);
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

@media (max-width: 900px) {
  .filter-item,
  .filter-range {
    width: 100%;
  }
}
</style>
