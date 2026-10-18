<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { versionApi } from '@/api'
import { formatDateTime } from '@/utils/format'

/**
 * 版本快照策略。
 *
 * 策略按作用域分三层：条目级 → 分类级 → 全局级。匹配时自上而下取第一条
 * 命中的策略即止，因此优先级字段只在同一作用域内部用于排序。
 */
const SCOPE_TEXT = { GLOBAL: '全局级', CATEGORY: '分类级', ITEM: '条目级' }
const SCOPE_TAG = { GLOBAL: 'primary', CATEGORY: 'warning', ITEM: 'success' }

const scopeOptions = [
  { value: 'GLOBAL', label: '全局级（作用于全部条目）' },
  { value: 'CATEGORY', label: '分类级（作用于指定分类）' },
  { value: 'ITEM', label: '条目级（作用于指定条目）' }
]

const loading = ref(false)
const pruning = ref(false)
const policies = ref([])

onMounted(load)

async function load() {
  loading.value = true
  try {
    policies.value = (await versionApi.policies()) || []
  } finally {
    loading.value = false
  }
}

async function handlePrune() {
  try {
    await ElMessageBox.confirm(
      '将按各策略配置的「保留版本数」清理超出上限的历史快照，清理后不可恢复。确定继续吗？',
      '全量清理确认',
      {
        type: 'warning',
        confirmButtonText: '开始清理',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch {
    return
  }
  pruning.value = true
  try {
    // 后端返回被清理的版本数量
    const removed = await versionApi.prune()
    ElMessage.success(`清理完成，共删除 ${removed ?? 0} 个历史版本`)
    load()
  } catch {
    /* 错误提示由拦截器给出（例如「不能删除最后一条启用的全局策略」） */
  } finally {
    pruning.value = false
  }
}

async function handleRemove(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除策略「${row.policyName}」吗？删除后命中的条目将回退到上一层作用域的策略。`,
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
    await versionApi.removePolicy(row.id)
    ElMessage.success('已删除')
    load()
  } catch {
    /* 删除最后一条启用的全局策略会被后端拒绝，拦截器已提示 */
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
  policyName: '',
  scope: 'GLOBAL',
  scopeId: null,
  intervalDays: 7,
  minChangeCount: 5,
  maxVersions: 20,
  autoSnapshot: 1,
  enabled: 1,
  priority: 100
})

const isEdit = computed(() => editingId.value !== null)
const dialogTitle = computed(() => (isEdit.value ? '编辑策略' : '新建策略'))
const isGlobal = computed(() => form.scope === 'GLOBAL')

const rules = computed(() => ({
  policyName: [{ required: true, message: '请输入策略名称', trigger: 'blur' }],
  scope: [{ required: true, message: '请选择作用域', trigger: 'change' }],
  scopeId: isGlobal.value
    ? []
    : [{ required: true, message: '请填写分类 / 条目的 id', trigger: 'blur' }],
  intervalDays: [{ required: true, message: '请填写天数阈值', trigger: 'blur' }],
  minChangeCount: [{ required: true, message: '请填写修改次数阈值', trigger: 'blur' }],
  maxVersions: [{ required: true, message: '请填写保留版本数', trigger: 'blur' }]
}))

function resetForm() {
  Object.assign(form, {
    policyName: '',
    scope: 'GLOBAL',
    scopeId: null,
    intervalDays: 7,
    minChangeCount: 5,
    maxVersions: 20,
    autoSnapshot: 1,
    enabled: 1,
    priority: 100
  })
  nextTick(() => formRef.value?.clearValidate())
}

function openCreate() {
  editingId.value = null
  resetForm()
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  Object.assign(form, {
    policyName: row.policyName,
    scope: row.scope,
    scopeId: row.scopeId ?? null,
    intervalDays: row.intervalDays ?? 0,
    minChangeCount: row.minChangeCount ?? 1,
    maxVersions: row.maxVersions ?? 1,
    autoSnapshot: row.autoSnapshot ?? 1,
    enabled: row.enabled ?? 1,
    priority: row.priority ?? 100
  })
  dialogVisible.value = true
  nextTick(() => formRef.value?.clearValidate())
}

/** 切换作用域时清掉残留的 scopeId，避免全局策略误带 id */
function handleScopeChange() {
  if (isGlobal.value) form.scopeId = null
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
      policyName: form.policyName,
      scope: form.scope,
      scopeId: isGlobal.value ? null : form.scopeId,
      intervalDays: form.intervalDays,
      minChangeCount: form.minChangeCount,
      maxVersions: form.maxVersions,
      autoSnapshot: form.autoSnapshot,
      enabled: form.enabled,
      priority: form.priority
    }
    if (isEdit.value) {
      await versionApi.updatePolicy(editingId.value, payload)
      ElMessage.success('已保存')
    } else {
      await versionApi.createPolicy(payload)
      ElMessage.success('策略已创建')
    }
    dialogVisible.value = false
    load()
  } catch {
    /* 拦截器已提示 */
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h1 class="page-title">版本策略</h1>
        <p class="page-subtitle">配置自动快照的触发条件与历史版本的保留上限</p>
      </div>
      <div class="header-actions">
        <el-button :icon="'Delete'" :loading="pruning" @click="handlePrune">全量清理</el-button>
        <el-button type="primary" :icon="'Plus'" @click="openCreate">新建策略</el-button>
      </div>
    </div>

    <!-- 策略模型说明：把匹配顺序与阈值关系一次讲清，避免读者靠猜 -->
    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="model-alert"
      title="策略解析顺序：条目级 → 分类级 → 全局级，命中即停止"
      description="保存快照时，先尝试条目自己的策略，未命中再上溯到所属分类，最后落到全局策略；只要有任意一层命中就不再继续查找。天数阈值与修改次数阈值之间是「或」的关系 —— 满足其一即创建版本。"
    />

    <div class="card table-card">
      <el-table v-loading="loading" :data="policies" row-key="id" stripe>
        <el-table-column prop="policyName" label="策略名称" min-width="160" show-overflow-tooltip />

        <el-table-column label="作用域" min-width="170">
          <template #default="{ row }">
            <el-tag :type="SCOPE_TAG[row.scope] || 'info'" effect="plain" size="small" disable-transitions>
              {{ row.scopeName || SCOPE_TEXT[row.scope] || row.scope }}
            </el-tag>
            <div v-if="row.scope !== 'GLOBAL'" class="scope-target">
              {{ row.targetName || `#${row.scopeId}` }}
            </div>
          </template>
        </el-table-column>

        <el-table-column label="天数阈值" width="110" align="right">
          <template #default="{ row }">
            <span class="tabular">{{ row.intervalDays }}</span> <span class="unit">天</span>
          </template>
        </el-table-column>

        <el-table-column label="修改次数阈值" width="130" align="right">
          <template #default="{ row }">
            <span class="tabular">{{ row.minChangeCount }}</span> <span class="unit">次</span>
          </template>
        </el-table-column>

        <el-table-column label="保留版本数" width="120" align="right">
          <template #default="{ row }">
            <span class="tabular">{{ row.maxVersions }}</span>
          </template>
        </el-table-column>

        <el-table-column label="自动快照" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="row.autoSnapshot === 1 ? 'success' : 'info'" effect="plain" size="small" disable-transitions>
              {{ row.autoSnapshot === 1 ? '启用' : '关闭' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.enabled === 1 ? 'success' : 'danger'" effect="plain" size="small" disable-transitions>
              {{ row.enabled === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="priority" label="优先级" width="90" align="right">
          <template #default="{ row }">
            <span class="tabular">{{ row.priority }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="'Edit'" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" :icon="'Delete'" @click="handleRemove(row)">删除</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div class="empty-hint">暂无版本策略，建议至少保留一条启用的全局策略</div>
        </template>
      </el-table>
    </div>

    <!-- ============ 新建 / 编辑 ============ -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="560px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="策略名称" prop="policyName">
          <el-input v-model="form.policyName" placeholder="例如：高风险条目快照" />
        </el-form-item>

        <el-form-item label="作用域" prop="scope">
          <el-select v-model="form.scope" class="full-width" @change="handleScopeChange">
            <el-option v-for="s in scopeOptions" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </el-form-item>

        <el-form-item v-if="!isGlobal" label="作用对象 id" prop="scopeId">
          <el-input-number v-model="form.scopeId" :min="1" :controls="false" class="full-width" placeholder="填写分类或条目的 id" />
          <div class="field-hint">
            分类级策略填「分类」的主键 id，条目级策略填「知识条目」的主键 id；可在分类管理 / 知识库中查看对应编号。
          </div>
        </el-form-item>

        <el-form-item label="天数阈值" prop="intervalDays">
          <el-input-number v-model="form.intervalDays" :min="0" :controls="false" class="num-input" />
          <span class="field-unit">天</span>
          <span class="field-inline-hint">距上次快照超过该天数即触发</span>
        </el-form-item>

        <el-form-item label="修改次数阈值" prop="minChangeCount">
          <el-input-number v-model="form.minChangeCount" :min="1" :controls="false" class="num-input" />
          <span class="field-unit">次</span>
          <span class="field-inline-hint">累计修改达到该次数即触发</span>
        </el-form-item>

        <el-form-item label="保留版本数" prop="maxVersions">
          <el-input-number v-model="form.maxVersions" :min="1" :controls="false" class="num-input" />
          <span class="field-unit">个</span>
          <span class="field-inline-hint">超出上限的旧版本会被「全量清理」回收</span>
        </el-form-item>

        <el-form-item label="优先级" prop="priority">
          <el-input-number v-model="form.priority" :min="0" :controls="false" class="num-input" />
          <span class="field-inline-hint">仅用于同作用域内排序，数值越小越先命中</span>
        </el-form-item>

        <el-form-item label="自动快照" prop="autoSnapshot">
          <el-switch v-model="form.autoSnapshot" :active-value="1" :inactive-value="0" inline-prompt active-text="开" inactive-text="关" />
          <span class="field-inline-hint">关闭后仅允许手工创建版本</span>
        </el-form-item>

        <el-form-item label="状态" prop="enabled">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" inline-prompt active-text="启用" inactive-text="停用" />
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
.header-actions {
  display: flex;
  gap: 10px;
}

.model-alert {
  margin-bottom: 14px;
}

.model-alert :deep(.el-alert__description) {
  line-height: 1.7;
}

.table-card {
  padding: 8px 14px 14px;
}

.scope-target {
  font-size: 12px;
  color: var(--ink-muted);
  margin-top: 2px;
}

.unit {
  font-size: 12px;
  color: var(--ink-muted);
  margin-left: 2px;
}

.num-input {
  width: 120px;
}

.field-unit {
  margin-left: 8px;
  font-size: 13px;
  color: var(--ink-secondary);
}

.field-inline-hint {
  margin-left: 12px;
  font-size: 12px;
  color: var(--ink-muted);
}

.field-hint {
  font-size: 12px;
  color: var(--ink-muted);
  line-height: 1.6;
  margin-top: 4px;
}

.full-width {
  width: 100%;
}
</style>
