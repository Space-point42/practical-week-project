<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { knowledgeApi, categoryApi, tagApi, ontologyApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { ASSET_TYPES } from '@/utils/format'
import KnowledgeCard from '@/components/KnowledgeCard.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const items = ref([])
const total = ref(0)
const categoryTree = ref([])
const hotTags = ref([])
const domains = ref([])
const conceptOptions = ref([])
const conceptSearching = ref(false)

const query = reactive({
  keyword: '',
  categoryId: null,
  assetTypes: [],
  tagIds: [],
  conceptIds: [],
  status: '',
  sortBy: 'updatedAt',
  sortDir: 'desc',
  page: 1,
  size: 12
})

const sortOptions = [
  { value: 'updatedAt:desc', label: '最近更新' },
  { value: 'createdAt:desc', label: '最新创建' },
  { value: 'viewCount:desc', label: '最多浏览' },
  { value: 'likeCount:desc', label: '最多点赞' },
  { value: 'title:asc', label: '标题排序' }
]

const sortValue = computed({
  get: () => `${query.sortBy}:${query.sortDir}`,
  set: (v) => {
    const [by, dir] = v.split(':')
    query.sortBy = by
    query.sortDir = dir
    query.page = 1
    load()
  }
})

const hasFilter = computed(
  () =>
    Boolean(query.keyword) ||
    Boolean(query.categoryId) ||
    query.assetTypes.length > 0 ||
    query.tagIds.length > 0 ||
    query.conceptIds.length > 0 ||
    Boolean(query.status)
)

onMounted(async () => {
  // 支持从其他页面带分类参数跳转过来
  if (route.query.categoryId) query.categoryId = Number(route.query.categoryId)
  if (route.query.keyword) query.keyword = String(route.query.keyword)

  await Promise.all([loadMeta(), load()])
})

async function loadMeta() {
  try {
    const [tree, tags, domainList] = await Promise.all([
      categoryApi.tree(),
      tagApi.hot(24),
      ontologyApi.domains()
    ])
    categoryTree.value = tree
    hotTags.value = tags
    domains.value = domainList
  } catch {
    /* 元数据加载失败不影响主列表 */
  }
}

async function load() {
  loading.value = true
  try {
    const params = {
      ...query,
      assetTypes: query.assetTypes.length ? query.assetTypes.join(',') : undefined,
      tagIds: query.tagIds.length ? query.tagIds.join(',') : undefined,
      conceptIds: query.conceptIds.length ? query.conceptIds.join(',') : undefined,
      categoryId: query.categoryId || undefined,
      status: query.status || undefined,
      keyword: query.keyword || undefined
    }
    // 管理员/编辑可以看到草稿与待审，普通浏览者只看已发布
    const res = userStore.canEdit
      ? await knowledgeApi.manage(params)
      : await knowledgeApi.page(params)
    items.value = res.list || []
    total.value = res.total || 0
  } finally {
    loading.value = false
  }
}

function searchConcepts(keyword) {
  if (!keyword) {
    conceptOptions.value = []
    return
  }
  conceptSearching.value = true
  ontologyApi
    .searchConcepts(keyword)
    .then((res) => {
      conceptOptions.value = res || []
    })
    .finally(() => {
      conceptSearching.value = false
    })
}

function resetFilters() {
  query.keyword = ''
  query.categoryId = null
  query.assetTypes = []
  query.tagIds = []
  query.conceptIds = []
  query.status = ''
  query.page = 1
  load()
}

function applyAndLoad() {
  query.page = 1
  load()
}

function openItem(item) {
  router.push(`/knowledge/${item.id}`)
}

async function handleDelete(item) {
  try {
    await ElMessageBox.confirm(
      `确定删除《${item.title}》吗？删除后该条目将从知识库中移除，历史版本仍保留在数据库中。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' }
    )
    await knowledgeApi.remove(item.id)
    ElMessage.success('已删除')
    load()
  } catch {
    /* 用户取消 */
  }
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h1 class="page-title">知识库</h1>
        <p class="page-subtitle">
          共 {{ total }} 条知识资产，可按分类、类型、标签与知识点多维筛选
        </p>
      </div>
      <el-button v-if="userStore.canEdit" type="primary" :icon="'Plus'" @click="router.push('/editor')">
        新建知识
      </el-button>
    </div>

    <div class="layout">
      <!-- ============ 左侧筛选栏 ============ -->
      <aside class="filter-panel">
        <div class="filter-block">
          <div class="filter-label">关键词</div>
          <el-input
            v-model="query.keyword"
            placeholder="标题 / 摘要 / 关键词"
            clearable
            @keyup.enter="applyAndLoad"
            @clear="applyAndLoad"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </div>

        <div class="filter-block">
          <div class="filter-label">分类</div>
          <el-tree
            :data="categoryTree"
            node-key="id"
            :props="{ label: 'name', children: 'children' }"
            highlight-current
            :expand-on-click-node="false"
            :current-node-key="query.categoryId"
            @node-click="(n) => { query.categoryId = n.id; applyAndLoad() }"
          >
            <template #default="{ data }">
              <span class="tree-node">
                <span>{{ data.name }}</span>
                <span class="tree-count tabular">{{ data.itemCount }}</span>
              </span>
            </template>
          </el-tree>
        </div>

        <div class="filter-block">
          <div class="filter-label">资产类型</div>
          <el-checkbox-group v-model="query.assetTypes" class="type-group" @change="applyAndLoad">
            <el-checkbox v-for="t in ASSET_TYPES" :key="t.value" :value="t.value" class="type-checkbox">
              <el-icon :size="13" :color="t.color"><component :is="t.icon" /></el-icon>
              {{ t.label }}
            </el-checkbox>
          </el-checkbox-group>
        </div>

        <div class="filter-block">
          <div class="filter-label">知识点</div>
          <el-select
            v-model="query.conceptIds"
            multiple
            filterable
            remote
            clearable
            collapse-tags
            collapse-tags-tooltip
            :remote-method="searchConcepts"
            :loading="conceptSearching"
            placeholder="搜索知识点"
            class="full-width"
            @change="applyAndLoad"
          >
            <el-option v-for="c in conceptOptions" :key="c.id" :label="c.name" :value="c.id">
              <span>{{ c.name }}</span>
              <span class="opt-domain">{{ c.domain }}</span>
            </el-option>
          </el-select>
        </div>

        <div class="filter-block">
          <div class="filter-label">
            标签
            <span class="filter-hint">可多选</span>
          </div>
          <div class="tag-cloud">
            <el-check-tag
              v-for="t in hotTags"
              :key="t.id"
              :checked="query.tagIds.includes(t.id)"
              class="tag-chip"
              @change="(checked) => {
                if (checked) query.tagIds.push(t.id)
                else query.tagIds = query.tagIds.filter((x) => x !== t.id)
                applyAndLoad()
              }"
            >
              {{ t.name }}<span class="tag-count"> {{ t.useCount }}</span>
            </el-check-tag>
          </div>
        </div>

        <div v-if="userStore.canEdit" class="filter-block">
          <div class="filter-label">状态</div>
          <el-select v-model="query.status" clearable placeholder="全部状态" class="full-width" @change="applyAndLoad">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="待审核" value="PENDING" />
            <el-option label="已发布" value="PUBLISHED" />
            <el-option label="已归档" value="ARCHIVED" />
          </el-select>
        </div>

        <el-button v-if="hasFilter" text class="reset-btn" :icon="'RefreshLeft'" @click="resetFilters">
          清空全部筛选
        </el-button>
      </aside>

      <!-- ============ 右侧列表 ============ -->
      <section class="list-panel">
        <div class="list-toolbar">
          <div class="toolbar-left">
            <el-tag v-if="hasFilter" closable type="primary" effect="plain" @close="resetFilters">
              已应用筛选条件
            </el-tag>
            <span class="result-count">
              共 <b class="tabular">{{ total }}</b> 条结果
            </span>
          </div>
          <el-select v-model="sortValue" class="sort-select" size="default">
            <el-option v-for="s in sortOptions" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </div>

        <div v-loading="loading" class="card-grid-wrap">
          <div v-if="items.length" class="card-grid">
            <KnowledgeCard
              v-for="item in items"
              :key="item.id"
              :item="item"
              :manage-mode="userStore.canEdit"
              @click="openItem"
            />
          </div>
          <el-empty v-else-if="!loading" description="没有找到符合条件的知识条目">
            <el-button v-if="hasFilter" type="primary" @click="resetFilters">清空筛选条件</el-button>
            <el-button v-else-if="userStore.canEdit" @click="router.push('/editor')">创建第一条知识</el-button>
          </el-empty>
        </div>

        <div v-if="total > query.size" class="pagination-wrap">
          <el-pagination
            v-model:current-page="query.page"
            v-model:page-size="query.size"
            :total="total"
            :page-sizes="[12, 24, 48]"
            layout="total, sizes, prev, pager, next, jumper"
            background
            @current-change="load"
            @size-change="applyAndLoad"
          />
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

/* ---------------- 筛选栏 ---------------- */
.filter-panel {
  width: 236px;
  flex-shrink: 0;
  background: #fff;
  border: 1px solid var(--hairline);
  border-radius: var(--app-radius);
  padding: 16px;
  position: sticky;
  top: 0;
  max-height: calc(100vh - 120px);
  overflow-y: auto;
}

.filter-block {
  margin-bottom: 20px;
}

.filter-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-secondary);
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.filter-hint {
  font-weight: 400;
  color: var(--ink-muted);
  font-size: 11px;
}

.tree-node {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding-right: 6px;
  font-size: 13px;
}

.tree-count {
  font-size: 11px;
  color: var(--ink-muted);
}

.type-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.type-checkbox {
  height: 26px;

  :deep(.el-checkbox__label) {
    font-size: 13px;
    display: inline-flex;
    align-items: center;
    gap: 5px;
  }
}

.tag-cloud {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tag-chip {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 12px;
}

.tag-count {
  color: var(--ink-muted);
  font-size: 11px;
}

.full-width {
  width: 100%;
}

.opt-domain {
  float: right;
  color: var(--ink-muted);
  font-size: 12px;
}

.reset-btn {
  width: 100%;
  color: var(--ink-muted);
}

/* ---------------- 列表 ---------------- */
.list-panel {
  flex: 1;
  min-width: 0;
}

.list-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.result-count {
  font-size: 13px;
  color: var(--ink-secondary);
}

.sort-select {
  width: 140px;
}

.card-grid-wrap {
  min-height: 320px;
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(310px, 1fr));
  gap: 14px;
}

.pagination-wrap {
  display: flex;
  justify-content: center;
  margin-top: 22px;
}

@media (max-width: 1100px) {
  .layout {
    flex-direction: column;
  }

  .filter-panel {
    width: 100%;
    position: static;
    max-height: none;
  }
}
</style>
