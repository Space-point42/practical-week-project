<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ontologyApi } from '@/api'
import { useUserStore } from '@/stores/user'
import StatTile from '@/components/StatTile.vue'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const concepts = ref([])
const tree = ref([])
const relationTypes = ref([])
const stats = ref({})

const filter = reactive({ keyword: '', domain: '', coverage: 'all' })

const dialogVisible = ref(false)
const dialogMode = ref('create')
const saving = ref(false)
const formRef = ref()

const relDialogVisible = ref(false)
const relSaving = ref(false)
const relFormRef = ref()

const form = reactive({
  id: null,
  code: '',
  name: '',
  enName: '',
  aliases: [],
  description: '',
  parentId: null,
  domain: '',
  difficulty: 3,
  importance: 3,
  color: '',
  sortOrder: 0
})

const relForm = reactive({
  sourceId: null,
  targetId: null,
  relationType: 'SUB_CLASS_OF',
  weight: 1,
  description: ''
})

const formRules = {
  code: [
    { required: true, message: '请输入概念编码', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9._-]+$/, message: '编码只能包含字母、数字、点、下划线与连字符', trigger: 'blur' }
  ],
  name: [{ required: true, message: '请输入概念名称', trigger: 'blur' }]
}

const relRules = {
  sourceId: [{ required: true, message: '请选择源知识点', trigger: 'change' }],
  targetId: [{ required: true, message: '请选择目标知识点', trigger: 'change' }],
  relationType: [{ required: true, message: '请选择关系类型', trigger: 'change' }]
}

const DOMAIN_COLORS = {
  软件工程: 'var(--series-1)',
  数据库: 'var(--series-3)',
  知识管理: 'var(--series-2)'
}

onMounted(load)

async function load() {
  loading.value = true
  try {
    const [list, treeData, types, stat] = await Promise.all([
      ontologyApi.concepts(),
      ontologyApi.tree(),
      ontologyApi.relationTypes(),
      ontologyApi.stats()
    ])
    concepts.value = list || []
    tree.value = treeData || []
    relationTypes.value = types || []
    stats.value = stat || {}
  } finally {
    loading.value = false
  }
}

/** 客户端筛选：数据量在百级，无需为每次输入发起请求 */
const filtered = computed(() =>
  concepts.value.filter((c) => {
    if (filter.domain && c.domain !== filter.domain) return false
    if (filter.coverage === 'covered' && c.itemCount === 0) return false
    if (filter.coverage === 'gap' && c.itemCount > 0) return false
    if (filter.keyword) {
      const kw = filter.keyword.toLowerCase()
      return (
        c.name.toLowerCase().includes(kw) ||
        (c.enName || '').toLowerCase().includes(kw) ||
        c.code.toLowerCase().includes(kw) ||
        (c.aliases || []).some((a) => a.toLowerCase().includes(kw))
      )
    }
    return true
  })
)

const domainOptions = computed(() => [...new Set(concepts.value.map((c) => c.domain).filter(Boolean))])
const uncoveredCount = computed(() => concepts.value.filter((c) => !c.itemCount).length)

function openCreate(parent) {
  dialogMode.value = 'create'
  Object.assign(form, {
    id: null,
    code: '',
    name: '',
    enName: '',
    aliases: [],
    description: '',
    parentId: parent?.id || null,
    domain: parent?.domain || '',
    difficulty: 3,
    importance: 3,
    color: '',
    sortOrder: 0
  })
  dialogVisible.value = true
}

function openEdit(row) {
  dialogMode.value = 'edit'
  Object.assign(form, {
    id: row.id,
    code: row.code,
    name: row.name,
    enName: row.enName || '',
    aliases: row.aliases || [],
    description: row.description || '',
    parentId: row.parentId || null,
    domain: row.domain || '',
    difficulty: row.difficulty || 3,
    importance: row.importance || 3,
    color: row.color || '',
    sortOrder: row.sortOrder || 0
  })
  dialogVisible.value = true
}

async function saveConcept() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    const payload = { ...form }
    delete payload.id
    if (dialogMode.value === 'create') {
      await ontologyApi.createConcept(payload)
      ElMessage.success('知识点已创建')
    } else {
      await ontologyApi.updateConcept(form.id, payload)
      ElMessage.success('知识点已更新')
    }
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function removeConcept(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除知识点「${row.name}」吗？删除会同时移除它的全部关系。` +
        (row.itemCount ? `该知识点已被 ${row.itemCount} 条知识条目引用，需要先解除标注。` : ''),
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' }
    )
    await ontologyApi.removeConcept(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    // 后端会因为「仍有子节点」或「仍被引用」而拒绝，提示已由拦截器给出
  }
}

function openRelation(sourceId) {
  Object.assign(relForm, {
    sourceId: sourceId || null,
    targetId: null,
    relationType: 'SUB_CLASS_OF',
    weight: 1,
    description: ''
  })
  relDialogVisible.value = true
}

async function saveRelation() {
  const valid = await relFormRef.value?.validate().catch(() => false)
  if (!valid) return
  if (relForm.sourceId === relForm.targetId) {
    ElMessage.warning('源与目标不能是同一知识点')
    return
  }

  relSaving.value = true
  try {
    await ontologyApi.createRelation({ ...relForm })
    ElMessage.success('关系已创建')
    relDialogVisible.value = false
    load()
  } finally {
    relSaving.value = false
  }
}

function viewInGraph(row) {
  router.push({ path: '/ontology', query: { conceptId: row.id } })
}

function domainColor(domain) {
  return DOMAIN_COLORS[domain] || 'var(--ink-muted)'
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h1 class="page-title">知识点管理</h1>
        <p class="page-subtitle">
          本体的概念层维护。层次结构由「父概念」定义，语义关系由三元组定义，
          两者互补：树回答「属于哪一类」，关系回答「与谁相关、如何相关」
        </p>
      </div>
      <div class="header-actions">
        <el-button v-if="userStore.isAdmin" :icon="'Share'" @click="openRelation(null)">
          新建关系
        </el-button>
        <el-button v-if="userStore.isAdmin" type="primary" :icon="'Plus'" @click="openCreate(null)">
          新建知识点
        </el-button>
      </div>
    </div>

    <el-row :gutter="14" class="stat-row">
      <el-col :xs="12" :sm="6">
        <StatTile label="知识点总数" :value="stats.conceptCount || 0" icon="Grid" accent="var(--series-1)" />
      </el-col>
      <el-col :xs="12" :sm="6">
        <StatTile label="语义关系" :value="stats.relationCount || 0" icon="Share" accent="var(--series-2)" />
      </el-col>
      <el-col :xs="12" :sm="6">
        <StatTile
          label="内容缺口"
          :value="uncoveredCount"
          icon="WarningFilled"
          accent="var(--series-4)"
          hint="尚无知识条目支撑的知识点"
        />
      </el-col>
      <el-col :xs="12" :sm="6">
        <StatTile
          label="本体覆盖率"
          :value="stats.coverage || 0"
          unit="%"
          icon="CircleCheck"
          accent="var(--series-3)"
        />
      </el-col>
    </el-row>

    <!-- ============ 筛选 ============ -->
    <div class="card filter-bar">
      <el-input
        v-model="filter.keyword"
        placeholder="搜索名称、英文名、别名或编码"
        clearable
        class="kw-input"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-select v-model="filter.domain" clearable placeholder="全部知识域" class="domain-select">
        <el-option v-for="d in domainOptions" :key="d" :label="d" :value="d" />
      </el-select>

      <el-radio-group v-model="filter.coverage" size="default">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="covered">已有内容</el-radio-button>
        <el-radio-button value="gap">内容缺口</el-radio-button>
      </el-radio-group>

      <span class="filter-count">
        显示 <b class="tabular">{{ filtered.length }}</b> / {{ concepts.length }} 个
      </span>
    </div>

    <!-- ============ 列表 ============ -->
    <div v-loading="loading" class="card table-card">
      <el-table :data="filtered" stripe row-key="id" :default-sort="{ prop: 'id', order: 'ascending' }">
        <el-table-column label="知识点" min-width="240">
          <template #default="{ row }">
            <div class="concept-cell">
              <span class="domain-bar" :style="{ background: domainColor(row.domain) }" />
              <div>
                <div class="c-name">
                  {{ row.name }}
                  <el-tag v-if="row.builtin === 1" size="small" effect="plain" type="info">内置</el-tag>
                </div>
                <div class="c-code">{{ row.code }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="英文名 / 别名" min-width="180">
          <template #default="{ row }">
            <div class="c-en">{{ row.enName || '—' }}</div>
            <div v-if="row.aliases && row.aliases.length" class="c-aliases">
              {{ row.aliases.join('、') }}
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="domain" label="知识域" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" disable-transitions>{{ row.domain || '—' }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="层级" width="76" align="center">
          <template #default="{ row }">
            <span class="tabular">L{{ row.level }}</span>
          </template>
        </el-table-column>

        <el-table-column label="子节点" width="76" align="center">
          <template #default="{ row }">
            <span class="tabular">{{ row.childCount }}</span>
          </template>
        </el-table-column>

        <el-table-column label="关联知识" width="96" align="center" sortable :sort-method="(a, b) => a.itemCount - b.itemCount">
          <template #default="{ row }">
            <el-tag v-if="row.itemCount" size="small" type="success" effect="plain" disable-transitions>
              {{ row.itemCount }} 条
            </el-tag>
            <el-tag v-else size="small" type="warning" effect="plain" disable-transitions>
              缺口
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="难度" width="130">
          <template #default="{ row }">
            <el-rate :model-value="row.difficulty" disabled size="small" :max="5" />
          </template>
        </el-table-column>

        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button text size="small" :icon="'Share'" @click="viewInGraph(row)">图谱</el-button>
            <el-button
              v-if="userStore.isAdmin"
              text
              size="small"
              :icon="'Edit'"
              @click="openEdit(row)"
            >
              编辑
            </el-button>
            <el-button
              v-if="userStore.isAdmin && row.builtin !== 1"
              text
              size="small"
              type="danger"
              :icon="'Delete'"
              @click="removeConcept(row)"
            />
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- ============ 概念编辑对话框 ============ -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新建知识点' : '编辑知识点'"
      width="620px"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="90px">
        <el-form-item label="编码" prop="code">
          <el-input v-model="form.code" placeholder="建议使用 域.子域.概念 形式，如 SE.REQ.ANALYSIS" />
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="中文名称" />
        </el-form-item>
        <el-form-item label="英文名">
          <el-input v-model="form.enName" placeholder="选填" />
        </el-form-item>
        <el-form-item label="别名">
          <el-select
            v-model="form.aliases"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="回车添加，用于实体链接时的同义归一"
            class="full-width"
          />
        </el-form-item>
        <el-form-item label="父概念">
          <el-select v-model="form.parentId" filterable clearable placeholder="不选则为根节点" class="full-width">
            <el-option
              v-for="c in concepts.filter((x) => x.id !== form.id)"
              :key="c.id"
              :label="`${'　'.repeat(c.level - 1)}${c.name}`"
              :value="c.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="知识域">
          <el-select v-model="form.domain" filterable allow-create clearable placeholder="如：软件工程" class="full-width">
            <el-option v-for="d in domainOptions" :key="d" :label="d" :value="d" />
          </el-select>
        </el-form-item>
        <el-form-item label="难度">
          <el-rate v-model="form.difficulty" :max="5" show-text :texts="['入门', '较易', '中等', '较难', '深入']" />
        </el-form-item>
        <el-form-item label="重要度">
          <el-rate v-model="form.importance" :max="5" />
        </el-form-item>
        <el-form-item label="释义">
          <el-input v-model="form.description" type="textarea" :rows="3" resize="none" maxlength="1024" show-word-limit />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveConcept">保存</el-button>
      </template>
    </el-dialog>

    <!-- ============ 关系创建对话框 ============ -->
    <el-dialog v-model="relDialogVisible" title="新建本体关系" width="560px" destroy-on-close>
      <el-form ref="relFormRef" :model="relForm" :rules="relRules" label-width="90px">
        <el-form-item label="源知识点" prop="sourceId">
          <el-select v-model="relForm.sourceId" filterable placeholder="选择源知识点" class="full-width">
            <el-option v-for="c in concepts" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="关系类型" prop="relationType">
          <el-select v-model="relForm.relationType" class="full-width">
            <el-option v-for="t in relationTypes" :key="t.code" :label="t.name" :value="t.code">
              <span class="edge-swatch" :style="{ background: t.color }" />
              {{ t.name }}
              <span class="rt-desc">{{ t.description ? t.description.slice(0, 22) + '…' : '' }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="目标知识点" prop="targetId">
          <el-select v-model="relForm.targetId" filterable placeholder="选择目标知识点" class="full-width">
            <el-option v-for="c in concepts" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="强度">
          <el-slider v-model="relForm.weight" :min="0" :max="1" :step="0.05" show-input />
        </el-form-item>
        <el-form-item label="关系说明">
          <el-input
            v-model="relForm.description"
            type="textarea"
            :rows="2"
            resize="none"
            placeholder="选填。留空时由系统按「源 + 关系 + 目标」生成自然语言断言"
          />
        </el-form-item>
      </el-form>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="对称关系会自动补一条反向边"
        description="例如「相关」与「近义」是无向关系，系统会同时写入反向三元组，保证图谱的双向遍历正确。"
      />

      <template #footer>
        <el-button @click="relDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="relSaving" @click="saveRelation">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.header-actions {
  display: flex;
  gap: 8px;
}

.stat-row {
  margin-bottom: 14px;

  :deep(.el-col) {
    margin-bottom: 14px;
  }
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.kw-input {
  width: 280px;
}

.domain-select {
  width: 160px;
}

.filter-count {
  margin-left: auto;
  font-size: 13px;
  color: var(--ink-secondary);
}

.table-card {
  padding: 6px 8px;
}

.concept-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.domain-bar {
  width: 3px;
  height: 30px;
  border-radius: 2px;
  flex-shrink: 0;
}

.c-name {
  font-size: 13.5px;
  font-weight: 550;
  display: flex;
  align-items: center;
  gap: 6px;
}

.c-code {
  font-size: 11px;
  color: var(--ink-muted);
  font-family: 'SFMono-Regular', Consolas, monospace;
  margin-top: 2px;
}

.c-en {
  font-size: 12.5px;
  color: var(--ink-secondary);
}

.c-aliases {
  font-size: 11.5px;
  color: var(--ink-muted);
  margin-top: 2px;
}

.full-width {
  width: 100%;
}

.edge-swatch {
  display: inline-block;
  width: 12px;
  height: 3px;
  border-radius: 2px;
  margin-right: 6px;
  vertical-align: 2px;
}

.rt-desc {
  float: right;
  font-size: 11px;
  color: var(--ink-muted);
}
</style>
