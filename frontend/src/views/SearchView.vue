<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { searchApi, categoryApi, tagApi, ontologyApi } from '@/api'
import { ASSET_TYPES, formatDuration } from '@/utils/format'
import KnowledgeCard from '@/components/KnowledgeCard.vue'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const searched = ref(false)
const result = ref({ hits: [], total: 0, page: 1, size: 10, pages: 0 })
const hotKeywords = ref([])
const suggestions = ref([])
const categoryTree = ref([])
const hotTags = ref([])
const conceptOptions = ref([])

const advanced = ref(false)

const form = reactive({
  keyword: '',
  categoryId: null,
  assetTypes: [],
  tagIds: [],
  conceptIds: [],
  titleOnly: false,
  withAttachmentOnly: false,
  sortBy: 'RELEVANCE',
  page: 1,
  size: 10
})

const sortOptions = [
  { value: 'RELEVANCE', label: '相关度优先' },
  { value: 'LATEST', label: '最新发布' },
  { value: 'HOTTEST', label: '最多浏览' },
  { value: 'LIKES', label: '最多点赞' }
]

const hasCondition = computed(
  () =>
    Boolean(form.keyword) ||
    Boolean(form.categoryId) ||
    form.assetTypes.length > 0 ||
    form.tagIds.length > 0 ||
    form.conceptIds.length > 0 ||
    form.titleOnly ||
    form.withAttachmentOnly
)

onMounted(async () => {
  if (route.query.keyword) form.keyword = String(route.query.keyword)

  try {
    const [hot, tree, tags] = await Promise.all([
      searchApi.hotKeywords(12),
      categoryApi.tree(),
      tagApi.hot(20)
    ])
    hotKeywords.value = hot || []
    categoryTree.value = tree || []
    hotTags.value = tags || []
  } catch {
    /* 元数据失败不阻塞检索 */
  }

  if (form.keyword) runSearch()
})

async function runSearch(page = 1) {
  form.page = page
  loading.value = true
  searched.value = true
  try {
    const payload = {
      ...form,
      categoryId: form.categoryId || undefined,
      assetTypes: form.assetTypes.length ? form.assetTypes : undefined,
      tagIds: form.tagIds.length ? form.tagIds : undefined,
      conceptIds: form.conceptIds.length ? form.conceptIds : undefined,
      keyword: form.keyword || undefined
    }
    result.value = advanced.value
      ? await searchApi.advanced(payload)
      : await searchApi.search(payload)
  } catch {
    result.value = { hits: [], total: 0, page: 1, size: form.size, pages: 0 }
  } finally {
    loading.value = false
  }
}

function useKeyword(kw) {
  form.keyword = kw
  runSearch(1)
}

async function fetchSuggestions(queryString) {
  if (!queryString) {
    suggestions.value = []
    return
  }
  try {
    suggestions.value = await searchApi.suggest(queryString, 8)
  } catch {
    suggestions.value = []
  }
}

function resetAll() {
  form.keyword = ''
  form.categoryId = null
  form.assetTypes = []
  form.tagIds = []
  form.conceptIds = []
  form.titleOnly = false
  form.withAttachmentOnly = false
  form.page = 1
  searched.value = false
  result.value = { hits: [], total: 0, page: 1, size: form.size, pages: 0 }
  suggestions.value = []
}

function openItem(item) {
  router.push(`/knowledge/${item.id}`)
}

function askRag() {
  router.push({ path: '/rag', query: { q: form.keyword } })
}

function searchByConcept(concept) {
  form.conceptIds = [concept.id]
  form.keyword = ''
  runSearch(1)
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h1 class="page-title">高级检索</h1>
        <p class="page-subtitle">
          支持全文检索与多条件组合筛选 —— 系统在应用层对召回结果重新排序，
          使标题命中的文档优先呈现
        </p>
      </div>
      <el-button :icon="advanced ? 'ArrowUp' : 'Operation'" @click="advanced = !advanced">
        {{ advanced ? '收起高级条件' : '展开高级条件' }}
      </el-button>
    </div>

    <!-- ============ 检索区 ============ -->
    <div class="search-panel card">
      <div class="search-row">
        <el-autocomplete
          v-model="form.keyword"
          :fetch-suggestions="(q, cb) => fetchSuggestions(q).then(() => cb(suggestions.map((s) => ({ value: s.text, type: s.type, refId: s.refId }))))"
          placeholder="输入关键词，例如：软件测试、范式、RAG、索引优化"
          class="search-input"
          clearable
          @keyup.enter="runSearch(1)"
          @select="(item) => { form.keyword = item.value; runSearch(1) }"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
          <template #default="{ item }">
            <div class="suggest-item">
              <el-icon :size="13">
                <component :is="item.type === 'CONCEPT' ? 'Grid' : item.type === 'TITLE' ? 'Document' : 'Clock'" />
              </el-icon>
              <span>{{ item.value }}</span>
              <span v-if="item.type === 'CONCEPT'" class="suggest-tag">知识点</span>
              <span v-else-if="item.type === 'TITLE'" class="suggest-tag">知识条目</span>
            </div>
          </template>
        </el-autocomplete>

        <el-button type="primary" size="large" :loading="loading" @click="runSearch(1)">检索</el-button>
        <el-button v-if="form.keyword" size="large" :icon="'ChatDotRound'" @click="askRag">
          提问
        </el-button>
      </div>

      <transition name="fade">
        <div v-if="advanced" class="advanced-panel">
          <el-row :gutter="16">
            <el-col :xs="24" :sm="12" :md="6">
              <div class="adv-field">
                <label>分类</label>
                <el-tree-select
                  v-model="form.categoryId"
                  :data="categoryTree"
                  node-key="id"
                  :props="{ label: 'name', children: 'children' }"
                  check-strictly
                  clearable
                  placeholder="全部分类（含子分类）"
                  class="full-width"
                />
              </div>
            </el-col>
            <el-col :xs="24" :sm="12" :md="6">
              <div class="adv-field">
                <label>资产类型</label>
                <el-select v-model="form.assetTypes" multiple collapse-tags placeholder="全部类型" class="full-width">
                  <el-option v-for="t in ASSET_TYPES" :key="t.value" :label="t.label" :value="t.value" />
                </el-select>
              </div>
            </el-col>
            <el-col :xs="24" :sm="12" :md="6">
              <div class="adv-field">
                <label>知识点</label>
                <el-select
                  v-model="form.conceptIds"
                  multiple
                  filterable
                  remote
                  collapse-tags
                  placeholder="搜索并选择知识点"
                  class="full-width"
                  :remote-method="(q) => ontologyApi.searchConcepts(q).then((r) => { conceptOptions = r || [] })"
                >
                  <el-option v-for="c in conceptOptions" :key="c.id" :label="c.name" :value="c.id" />
                </el-select>
              </div>
            </el-col>
            <el-col :xs="24" :sm="12" :md="6">
              <div class="adv-field">
                <label>标签</label>
                <el-select v-model="form.tagIds" multiple collapse-tags placeholder="全部标签" class="full-width">
                  <el-option v-for="t in hotTags" :key="t.id" :label="`${t.name} (${t.useCount})`" :value="t.id" />
                </el-select>
              </div>
            </el-col>
          </el-row>

          <div class="adv-foot">
            <el-checkbox v-model="form.titleOnly">仅匹配标题</el-checkbox>
            <el-checkbox v-model="form.withAttachmentOnly">仅含附件的条目</el-checkbox>
            <div class="adv-actions">
              <el-button text @click="resetAll">清空条件</el-button>
              <el-button type="primary" @click="runSearch(1)">应用条件</el-button>
            </div>
          </div>
        </div>
      </transition>

      <!-- 热搜词（未检索时展示，给用户一个起点） -->
      <div v-if="hotKeywords.length && !searched" class="hot-row">
        <span class="hot-label">热搜</span>
        <el-tag
          v-for="k in hotKeywords"
          :key="k.keyword"
          size="small"
          effect="plain"
          class="hot-tag"
          @click="useKeyword(k.keyword)"
        >
          {{ k.keyword }}
        </el-tag>
      </div>
    </div>

    <!-- ============ 结果区 ============ -->
    <div v-if="searched" class="result-panel">
      <!-- 检索过程说明 -->
      <div class="trace-bar">
        <div class="trace-left">
          <el-tag :type="result.fulltextUsed ? 'success' : 'warning'" effect="plain" size="small">
            {{ result.modeUsed || '—' }}
          </el-tag>
          <span class="trace-text">
            命中 <b class="tabular">{{ result.total }}</b> 条 · 耗时
            <b class="tabular">{{ formatDuration(result.costMs) }}</b>
          </span>
          <el-tag v-if="result.terms && result.terms.length" size="small" type="info" effect="plain">
            分词：{{ result.terms.slice(0, 6).join(' / ') }}
          </el-tag>
        </div>
        <el-select v-model="form.sortBy" class="sort-select" @change="runSearch(1)">
          <el-option v-for="s in sortOptions" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </div>

      <!-- 弱匹配提示：如实告知用户这是模糊结果，而不是伪装成精确命中 -->
      <el-alert
        v-if="result.weakMatch"
        type="warning"
        :closable="false"
        show-icon
        class="weak-alert"
        title="未找到精确匹配的内容"
        description="以下结果的标题、摘要与关键词均未命中查询词，仅正文存在偶然的字符重合，相关性较低。建议换用更具体的关键词，或参考下方的检索建议。"
      />

      <!-- 查询串关联到的知识点 -->
      <div v-if="result.linkedConcepts && result.linkedConcepts.length" class="linked-row">
        <span class="linked-label">查询涉及的知识点</span>
        <el-tag
          v-for="c in result.linkedConcepts"
          :key="c.id"
          class="linked-tag"
          effect="plain"
          @click="searchByConcept(c)"
        >
          <el-icon :size="12"><Grid /></el-icon>
          {{ c.name }}
        </el-tag>
      </div>

      <!-- 无结果 -->
      <div v-if="!result.hits.length && !loading" class="no-result card">
        <el-icon :size="34" color="var(--ink-muted)"><Search /></el-icon>
        <h3>没有找到相关内容</h3>
        <div class="no-result-tips">
          <p v-for="(tip, i) in result.suggestions || []" :key="i">· {{ tip }}</p>
        </div>
        <div class="no-result-actions">
          <el-button @click="resetAll">清空条件重试</el-button>
          <el-button type="primary" @click="router.push('/ontology')">浏览知识图谱</el-button>
          <el-button :icon="'ChatDotRound'" @click="askRag">换个方式问 AI</el-button>
        </div>
      </div>

      <!-- 结果卡片 -->
      <div v-else v-loading="loading" class="result-grid">
        <KnowledgeCard
          v-for="hit in result.hits"
          :key="hit.id"
          :item="hit"
          search-mode
          @click="openItem"
        />
      </div>

      <div v-if="result.total > form.size" class="pagination-wrap">
        <el-pagination
          :current-page="result.page"
          :page-size="result.size"
          :total="result.total"
          layout="total, prev, pager, next"
          background
          @current-change="runSearch"
        />
      </div>
    </div>

    <!-- ============ 未检索时的引导 ============ -->
    <div v-else class="guide card">
      <h3 class="section-title">检索能力说明<span class="section-hint">答辩时可展开讲解</span></h3>
      <div class="guide-grid">
        <div class="guide-item">
          <el-icon :size="18" color="var(--series-1)"><Document /></el-icon>
          <div>
            <h4>全文检索</h4>
            <p>
              基于 MySQL 8 的全文索引，中文使用 ngram 解析器按二元组切分，
              因此「软件测试」与「测试」都能命中。
            </p>
          </div>
        </div>
        <div class="guide-item">
          <el-icon :size="18" color="var(--series-2)"><Operation /></el-icon>
          <div>
            <h4>高级检索</h4>
            <p>
              分类（含子分类）、资产类型、标签、知识点、标题限定与附件限定可自由组合，
              条件之间是「与」的关系。
            </p>
          </div>
        </div>
        <div class="guide-item">
          <el-icon :size="18" color="var(--series-3)"><Sort /></el-icon>
          <div>
            <h4>应用层重排序</h4>
            <p>
              数据库只负责召回候选，最终排序由应用层按「字段命中率 65% + 全文相关度 25% + 热度 10%」
              计算，避免长文档因二元组多而被过度加权。
            </p>
          </div>
        </div>
        <div class="guide-item">
          <el-icon :size="18" color="var(--series-4)"><Grid /></el-icon>
          <div>
            <h4>本体联动</h4>
            <p>
              检索词会被链接到本体知识点，点击知识点标签即可切换到「按知识结构浏览」的视角。
            </p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.search-panel {
  padding: 18px;
  margin-bottom: 16px;
}

.search-row {
  display: flex;
  gap: 10px;
  align-items: center;
}

.search-input {
  flex: 1;

  :deep(.el-input__wrapper) {
    border-radius: 10px;
  }

  :deep(.el-input__inner) {
    height: 40px;
    font-size: 14px;
  }
}

.suggest-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.suggest-tag {
  margin-left: auto;
  font-size: 11px;
  color: var(--ink-muted);
}

/* ---------------- 高级条件 ---------------- */
.advanced-panel {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px dashed var(--gridline);
}

.adv-field {
  margin-bottom: 12px;

  label {
    display: block;
    font-size: 12px;
    font-weight: 600;
    color: var(--ink-secondary);
    margin-bottom: 6px;
  }
}

.full-width {
  width: 100%;
}

.adv-foot {
  display: flex;
  align-items: center;
  gap: 18px;
  flex-wrap: wrap;
}

.adv-actions {
  margin-left: auto;
  display: flex;
  gap: 8px;
}

/* ---------------- 热搜 ---------------- */
.hot-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--gridline);
}

.hot-label {
  font-size: 12px;
  color: var(--ink-muted);
}

.hot-tag {
  cursor: pointer;

  &:hover {
    background: var(--app-primary-soft);
    border-color: var(--app-primary);
    color: var(--app-primary);
  }
}

/* ---------------- 结果区 ---------------- */
.trace-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.trace-left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.trace-text {
  font-size: 13px;
  color: var(--ink-secondary);
}

.sort-select {
  width: 140px;
}

.weak-alert {
  margin-bottom: 12px;
}

.linked-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 14px;
  padding: 10px 14px;
  background: #fff;
  border: 1px solid var(--hairline);
  border-radius: var(--app-radius);
}

.linked-label {
  font-size: 12px;
  color: var(--ink-muted);
}

.linked-tag {
  cursor: pointer;

  &:hover {
    background: var(--app-primary-soft);
    color: var(--app-primary);
  }
}

.result-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 14px;
  min-height: 200px;
}

.pagination-wrap {
  display: flex;
  justify-content: center;
  margin-top: 22px;
}

/* ---------------- 无结果 ---------------- */
.no-result {
  padding: 44px 20px;
  text-align: center;

  h3 {
    margin: 14px 0 8px;
    font-size: 16px;
    font-weight: 600;
  }
}

.no-result-tips {
  max-width: 560px;
  margin: 0 auto 20px;
  text-align: left;

  p {
    font-size: 13px;
    color: var(--ink-secondary);
    line-height: 1.9;
    margin: 0;
  }
}

.no-result-actions {
  display: flex;
  gap: 10px;
  justify-content: center;
  flex-wrap: wrap;
}

/* ---------------- 引导 ---------------- */
.guide {
  padding: 20px 22px;
}

.guide-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 18px;
  margin-top: 6px;
}

.guide-item {
  display: flex;
  gap: 12px;
  align-items: flex-start;

  h4 {
    font-size: 13.5px;
    font-weight: 600;
    margin: 0 0 5px;
  }

  p {
    font-size: 12.5px;
    line-height: 1.8;
    color: var(--ink-secondary);
    margin: 0;
  }
}
</style>
