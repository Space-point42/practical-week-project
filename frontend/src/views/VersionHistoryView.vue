<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { knowledgeApi, versionApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { CHANGE_SOURCE_META, formatDateTime, formatNumber } from '@/utils/format'
import MarkdownView from '@/components/MarkdownView.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const item = ref(null)
const decision = ref(null)
const versions = ref([])
const total = ref(0)

const query = reactive({ page: 1, size: 10 })

// ---- 版本正文抽屉 ----
const viewVisible = ref(false)
const viewLoading = ref(false)
const viewData = ref(null)

// ---- 版本对比 ----
const compareFrom = ref(null)
const compareTo = ref(null)
const diffVisible = ref(false)
const diffLoading = ref(false)
const diff = ref(null)

const itemId = computed(() => route.params.id)

const versionOptions = computed(() =>
  versions.value.map((v) => ({
    value: v.versionNo,
    label: `v${v.versionNo}${v.current ? '（当前）' : ''}`,
    title: v.title
  }))
)

// ---- 策略判定可视化 ----
const daysReached = computed(
  () => (decision.value?.daysSinceLastSnapshot || 0) >= (decision.value?.intervalDays || 0)
)
const changesReached = computed(
  () => (decision.value?.pendingChangeCount || 0) >= (decision.value?.minChangeCount || 0)
)
const daysPct = computed(() => ratio(decision.value?.daysSinceLastSnapshot, decision.value?.intervalDays))
const changesPct = computed(() =>
  ratio(decision.value?.pendingChangeCount, decision.value?.minChangeCount)
)
const daysColor = computed(() => (daysReached.value ? 'var(--status-warning)' : 'var(--app-primary)'))
const changesColor = computed(() =>
  changesReached.value ? 'var(--status-warning)' : 'var(--app-primary)'
)

function ratio(current, threshold) {
  if (!threshold) return 0
  return Math.min(100, Math.round((Number(current || 0) / Number(threshold)) * 100))
}

async function load() {
  loading.value = true
  try {
    const [listRes, decisionRes, detail] = await Promise.all([
      versionApi.list(itemId.value, { page: query.page, size: query.size }),
      versionApi.decision(itemId.value),
      knowledgeApi.detail(itemId.value)
    ])
    versions.value = listRes.list || []
    total.value = listRes.total || 0
    decision.value = decisionRes
    item.value = detail
  } finally {
    loading.value = false
  }
}

onMounted(load)
// 组件复用（切换条目）时重新加载
watch(itemId, () => {
  query.page = 1
  load()
})

function goDetail() {
  router.push(`/knowledge/${itemId.value}`)
}

function changePage(page) {
  query.page = page
  load()
}

// ---------------------------------------------------------------------
// 手工建版
// ---------------------------------------------------------------------
async function handleCreate() {
  try {
    const { value } = await ElMessageBox.prompt(
      '手工创建会立即把当前内容固化为一个新版本快照。',
      '创建版本',
      {
        confirmButtonText: '创建',
        cancelButtonText: '取消',
        inputPlaceholder: '请填写版本说明（必填）',
        inputValidator: (v) => (v && v.trim() ? true : '版本说明不能为空')
      }
    )
    await versionApi.create(itemId.value, { changeLog: value.trim() })
    ElMessage.success('版本已创建')
    query.page = 1
    load()
  } catch {
    /* 用户取消；接口错误由拦截器提示 */
  }
}

// ---------------------------------------------------------------------
// 查看 / 回滚 / 对比
// ---------------------------------------------------------------------
async function handleView(row) {
  viewVisible.value = true
  viewLoading.value = true
  viewData.value = null
  try {
    viewData.value = await versionApi.detail(itemId.value, row.versionNo)
  } catch {
    viewVisible.value = false
  } finally {
    viewLoading.value = false
  }
}

async function handleRollback(row) {
  try {
    const { value } = await ElMessageBox.prompt(
      `回滚会用 v${row.versionNo} 的内容覆盖当前条目，并生成一个全新的版本；历史版本不会被删除，随时可再追溯。`,
      '回滚确认',
      {
        type: 'warning',
        confirmButtonText: '回滚',
        cancelButtonText: '取消',
        inputPlaceholder: '请填写本次回滚的变更说明',
        inputValidator: (v) => (v && v.trim() ? true : '变更说明不能为空')
      }
    )
    await versionApi.rollback(itemId.value, row.versionNo, value.trim())
    ElMessage.success('已回滚并生成新版本')
    query.page = 1
    load()
  } catch {
    /* 用户取消 */
  }
}

/** 表格行「对比」：先把基准填入选择器，用户可在上方微调后开始对比 */
function quickCompare(row) {
  compareFrom.value = row.versionNo
  if (!compareTo.value || compareTo.value === row.versionNo) {
    compareTo.value = item.value?.versionNo || row.versionNo
  }
}

const canCompare = computed(
  () => compareFrom.value != null && compareTo.value != null && compareFrom.value !== compareTo.value
)

async function handleDiff() {
  if (!canCompare.value) {
    ElMessage.warning('请选择两个不同的版本进行对比')
    return
  }
  diffVisible.value = true
  diffLoading.value = true
  diff.value = null
  try {
    diff.value = await versionApi.diff(itemId.value, compareFrom.value, compareTo.value)
  } catch {
    diffVisible.value = false
  } finally {
    diffLoading.value = false
  }
}

function sourceMeta(value) {
  return CHANGE_SOURCE_META[value] || { label: value || '未知', type: 'info' }
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h1 class="page-title">
          <el-button text :icon="'Back'" class="back-btn" @click="goDetail">返回详情</el-button>
          {{ item?.title || '版本历史' }}
        </h1>
        <p class="page-subtitle">
          当前版本 v{{ item?.versionNo ?? '—' }}，共 {{ total }} 个历史版本。版本快照按「天数 / 修改次数」双阈值策略自动判定。
        </p>
      </div>
      <el-button type="primary" :icon="'Plus'" @click="handleCreate">手工创建版本</el-button>
    </div>

    <!-- ============ 版本策略判定 ============ -->
    <section v-if="decision" class="card policy-panel">
      <div class="policy-head">
        <h2 class="section-title policy-title">
          版本策略判定
          <span class="section-hint">{{ decision.policyName || '默认策略' }}</span>
        </h2>
        <el-tag :type="decision.due ? 'warning' : 'success'" effect="dark" class="due-chip">
          <el-icon><component :is="decision.due ? 'WarningFilled' : 'CircleCheckFilled'" /></el-icon>
          {{ decision.due ? '建议创建新版本' : '暂不需要建版' }}
        </el-tag>
      </div>

      <p class="policy-reason">{{ decision.reason }}</p>

      <div class="policy-metrics">
        <div class="policy-row">
          <div class="policy-row-head">
            <span class="pr-label">距上次快照天数</span>
            <span class="pr-value tabular">
              {{ decision.daysSinceLastSnapshot }} <em>/ {{ decision.intervalDays }} 天</em>
            </span>
          </div>
          <el-progress :percentage="daysPct" :stroke-width="10" :show-text="false" :color="daysColor" />
          <span class="pr-flag" :class="{ reached: daysReached }">
            {{ daysReached ? '已达阈值' : '未达阈值' }}
          </span>
        </div>

        <div class="policy-row">
          <div class="policy-row-head">
            <span class="pr-label">累计修改次数</span>
            <span class="pr-value tabular">
              {{ decision.pendingChangeCount }} <em>/ {{ decision.minChangeCount }} 次</em>
            </span>
          </div>
          <el-progress
            :percentage="changesPct"
            :stroke-width="10"
            :show-text="false"
            :color="changesColor"
          />
          <span class="pr-flag" :class="{ reached: changesReached }">
            {{ changesReached ? '已达阈值' : '未达阈值' }}
          </span>
        </div>
      </div>

      <p class="policy-rule">
        <el-icon><InfoFilled /></el-icon>
        判定规则：距上次快照天数与累计修改次数<b>任一</b>达到阈值即建议创建新版本；两条进度条可直观看出是「时间」还是「改动量」先触发。
      </p>
    </section>

    <!-- ============ 版本对比入口 ============ -->
    <section class="card compare-bar">
      <div class="compare-field">
        <span class="compare-label">对比基准</span>
        <el-select v-model="compareFrom" placeholder="选择旧版本" clearable class="version-select">
          <el-option
            v-for="v in versionOptions"
            :key="v.value"
            :label="v.label"
            :value="v.value"
          />
        </el-select>
      </div>
      <el-icon class="compare-arrow"><Right /></el-icon>
      <div class="compare-field">
        <span class="compare-label">对比目标</span>
        <el-select v-model="compareTo" placeholder="选择新版本" clearable class="version-select">
          <el-option
            v-for="v in versionOptions"
            :key="v.value"
            :label="v.label"
            :value="v.value"
          />
        </el-select>
      </div>
      <el-button type="primary" :icon="'DocumentCopy'" :disabled="!canCompare" @click="handleDiff">
        开始对比
      </el-button>
    </section>

    <!-- ============ 版本列表 ============ -->
    <div v-loading="loading" class="card table-card">
      <el-table :data="versions" row-key="id" style="width: 100%">
        <el-table-column label="版本" width="120">
          <template #default="{ row }">
            <span class="version-no tabular">v{{ row.versionNo }}</span>
            <el-tag v-if="row.current" size="small" type="success" effect="plain" class="cur-tag">
              当前
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="变更说明" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.changeLog || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="来源" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="sourceMeta(row.changeSource).type" effect="plain">
              {{ sourceMeta(row.changeSource).label }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="变更量" width="150">
          <template #default="{ row }">
            <span class="delta-add tabular">+{{ formatNumber(row.charAdded) }}</span>
            <span class="delta-sep">/</span>
            <span class="delta-del tabular">-{{ formatNumber(row.charRemoved) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="编辑人" width="120">
          <template #default="{ row }">{{ row.editorName || '—' }}</template>
        </el-table-column>

        <el-table-column label="时间" width="160">
          <template #default="{ row }">
            <span class="tabular">{{ formatDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="handleView(row)">查看</el-button>
            <el-button
              v-if="!row.current"
              text
              type="warning"
              size="small"
              @click="handleRollback(row)"
            >
              回滚
            </el-button>
            <el-button text size="small" @click="quickCompare(row)">对比</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div class="empty-hint">暂无版本记录</div>
        </template>
      </el-table>

      <div v-if="total > query.size" class="pagination-wrap">
        <el-pagination
          :current-page="query.page"
          :page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          background
          @current-change="changePage"
        />
      </div>
    </div>

    <!-- ============ 版本正文抽屉 ============ -->
    <el-drawer v-model="viewVisible" :title="viewData ? `v${viewData.versionNo} · ${viewData.title}` : '版本内容'" size="640px">
      <div v-loading="viewLoading" class="drawer-body">
        <template v-if="viewData">
          <div class="drawer-meta">
            <span>{{ sourceMeta(viewData.changeSource).label }}</span>
            <span>·</span>
            <span>{{ viewData.editorName || '未知' }}</span>
            <span>·</span>
            <span class="tabular">{{ formatDateTime(viewData.createdAt) }}</span>
          </div>
          <p v-if="viewData.changeLog" class="drawer-changelog">{{ viewData.changeLog }}</p>
          <MarkdownView :content="viewData.content" />
        </template>
      </div>
    </el-drawer>

    <!-- ============ 版本对比对话框 ============ -->
    <el-dialog v-model="diffVisible" title="版本对比" width="900px" top="5vh" destroy-on-close>
      <div v-loading="diffLoading" class="diff-wrap">
        <template v-if="diff">
          <!-- 概要 -->
          <div class="diff-summary">
            <el-tag :type="diff.titleChanged ? 'warning' : 'info'" effect="plain">
              标题{{ diff.titleChanged ? '已变更' : '未变更' }}
            </el-tag>
            <el-tag :type="diff.summaryChanged ? 'warning' : 'info'" effect="plain">
              摘要{{ diff.summaryChanged ? '已变更' : '未变更' }}
            </el-tag>
            <el-tag :type="diff.categoryChanged ? 'warning' : 'info'" effect="plain">
              分类{{ diff.categoryChanged ? '已变更' : '未变更' }}
            </el-tag>
            <span class="summary-gap" />
            <span class="summary-stat">
              <b class="delta-add tabular">+{{ formatNumber(diff.addedLines) }}</b> /
              <b class="delta-del tabular">-{{ formatNumber(diff.removedLines) }}</b> 行
            </span>
            <span class="summary-stat">
              <b class="delta-add tabular">+{{ formatNumber(diff.charAdded) }}</b> /
              <b class="delta-del tabular">-{{ formatNumber(diff.charRemoved) }}</b> 字
            </span>
          </div>

          <div class="diff-versions">
            <span>基准 v{{ diff.fromVersion }} · {{ diff.fromTitle }}</span>
            <el-icon><Right /></el-icon>
            <span>目标 v{{ diff.toVersion }} · {{ diff.toTitle }}</span>
          </div>

          <!-- 知识点变化 -->
          <div v-if="diff.conceptsAdded?.length || diff.conceptsRemoved?.length" class="diff-block">
            <div class="diff-block-title">知识点变化</div>
            <div class="chip-row">
              <el-tag v-for="c in diff.conceptsAdded" :key="'ca-' + c" type="success" effect="plain" size="small">
                + {{ c }}
              </el-tag>
              <el-tag v-for="c in diff.conceptsRemoved" :key="'cr-' + c" type="danger" effect="plain" size="small">
                − {{ c }}
              </el-tag>
            </div>
          </div>

          <!-- 标签变化 -->
          <div v-if="diff.tagsAdded?.length || diff.tagsRemoved?.length" class="diff-block">
            <div class="diff-block-title">标签变化</div>
            <div class="chip-row">
              <el-tag v-for="t in diff.tagsAdded" :key="'ta-' + t" type="success" effect="plain" size="small">
                + {{ t }}
              </el-tag>
              <el-tag v-for="t in diff.tagsRemoved" :key="'tr-' + t" type="danger" effect="plain" size="small">
                − {{ t }}
              </el-tag>
            </div>
          </div>

          <!-- 行级差异 -->
          <div class="diff-block">
            <div class="diff-block-title">正文行级差异</div>
            <div class="diff-lines">
              <template v-for="(line, idx) in diff.lines" :key="idx">
                <div v-if="line.type === 'SKIP'" class="diff-row is-skip">
                  <span class="diff-skip">{{ line.content }}</span>
                </div>
                <div v-else :class="['diff-row', 'is-' + line.type.toLowerCase()]">
                  <span class="gutter tabular">{{ line.oldLineNo ?? '' }}</span>
                  <span class="gutter tabular">{{ line.newLineNo ?? '' }}</span>
                  <code class="diff-content">{{ line.content || ' ' }}</code>
                </div>
              </template>
            </div>
          </div>
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.back-btn {
  margin-right: 4px;
}

/* ---------------- 策略判定面板 ---------------- */
.policy-panel {
  padding: 18px 22px 16px;
  margin-bottom: 16px;
  border-left: 3px solid var(--app-primary);
}

.policy-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.policy-title {
  margin-bottom: 0;
}

.due-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: none;
}

.policy-reason {
  font-size: 14px;
  line-height: 1.75;
  color: var(--ink-primary);
  background: var(--page-plane);
  border-radius: var(--app-radius-sm);
  padding: 12px 14px;
  margin: 14px 0 16px;
}

.policy-metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px 32px;
}

.policy-row-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 6px;
}

.pr-label {
  font-size: 13px;
  color: var(--ink-secondary);
}

.pr-value {
  font-size: 15px;
  font-weight: 650;
}

.pr-value em {
  font-style: normal;
  font-weight: 400;
  font-size: 12px;
  color: var(--ink-muted);
}

.pr-flag {
  display: inline-block;
  margin-top: 4px;
  font-size: 11.5px;
  color: var(--ink-muted);
}

.pr-flag.reached {
  color: var(--status-warning);
  font-weight: 600;
}

.policy-rule {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--ink-secondary);
  margin: 16px 0 0;
  line-height: 1.6;
}

/* ---------------- 对比入口 ---------------- */
.compare-bar {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  padding: 14px 18px;
  margin-bottom: 16px;
}

.compare-field {
  display: flex;
  align-items: center;
  gap: 8px;
}

.compare-label {
  font-size: 13px;
  color: var(--ink-secondary);
  white-space: nowrap;
}

.version-select {
  width: 200px;
}

.compare-arrow {
  color: var(--ink-muted);
}

/* ---------------- 表格 ---------------- */
.table-card {
  padding: 8px 4px 4px;
}

.version-no {
  font-weight: 600;
}

.cur-tag {
  margin-left: 6px;
}

.delta-add {
  color: var(--delta-up);
}

.delta-del {
  color: var(--status-critical);
}

.delta-sep {
  margin: 0 6px;
  color: var(--ink-muted);
}

.pagination-wrap {
  display: flex;
  justify-content: center;
  padding: 16px 0 10px;
}

/* ---------------- 抽屉 ---------------- */
.drawer-body {
  min-height: 120px;
}

.drawer-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--ink-muted);
  margin-bottom: 8px;
}

.drawer-changelog {
  font-size: 13px;
  color: var(--ink-secondary);
  background: var(--page-plane);
  border-radius: var(--app-radius-sm);
  padding: 10px 12px;
  margin: 0 0 16px;
  line-height: 1.7;
}

/* ---------------- 对比对话框 ---------------- */
.diff-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--hairline);
}

.summary-gap {
  flex: 1;
}

.summary-stat {
  font-size: 12.5px;
  color: var(--ink-secondary);
}

.diff-versions {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--ink-secondary);
  margin: 12px 0;
}

.diff-block {
  margin-top: 16px;
}

.diff-block-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
}

.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

/* 行级差异：等宽字体 + 双行号编槽 + 背景着色，文字保持常规墨色以保证对比度 */
.diff-lines {
  border: 1px solid var(--hairline);
  border-radius: var(--app-radius-sm);
  overflow: hidden;
  max-height: 460px;
  overflow-y: auto;
}

.diff-row {
  display: flex;
  align-items: stretch;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12.5px;
  line-height: 1.7;
}

.gutter {
  flex: none;
  width: 46px;
  padding: 0 8px 0 0;
  text-align: right;
  color: var(--ink-muted);
  user-select: none;
  background: rgba(11, 11, 11, 0.03);
}

.diff-content {
  flex: 1;
  min-width: 0;
  padding: 0 12px;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--ink-primary);
}

.is-add {
  background: color-mix(in srgb, var(--status-good) 12%, #fff);
}

.is-add .gutter {
  background: color-mix(in srgb, var(--status-good) 18%, #fff);
  color: var(--delta-up);
}

.is-remove {
  background: color-mix(in srgb, var(--status-critical) 10%, #fff);
}

.is-remove .gutter {
  background: color-mix(in srgb, var(--status-critical) 16%, #fff);
  color: var(--status-critical);
}

.is-skip {
  justify-content: center;
}

.diff-skip {
  display: block;
  width: 100%;
  text-align: center;
  font-size: 11.5px;
  color: var(--ink-muted);
  background: var(--page-plane);
  padding: 4px 0;
}

@media (max-width: 760px) {
  .policy-metrics {
    grid-template-columns: 1fr;
  }

  .version-select {
    width: 100%;
  }

  .compare-field {
    flex: 1 1 100%;
  }
}
</style>
