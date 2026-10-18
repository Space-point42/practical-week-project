<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ontologyApi, knowledgeApi } from '@/api'
import { SERIES, INK, FONT_FAMILY, tooltip } from '@/utils/echartsTheme'
import { assetTypeMeta, fromNow } from '@/utils/format'
import EChart from '@/components/EChart.vue'
import StatTile from '@/components/StatTile.vue'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const graphLoading = ref(false)
const graph = ref({ nodes: [], edges: [], relationTypes: [] })
const concepts = ref([])
const stats = ref({})
const domains = ref([])

/** 视图模式：full 全图 / neighborhood 邻域 / domain 单域 */
const mode = ref('full')
const selectedConceptId = ref(null)
const neighborhoodDepth = ref(2)

/** 关系类型筛选：默认全开 */
const enabledRelations = ref(new Set())
const enabledDomains = ref(new Set())

const detail = ref(null)
const relatedItems = ref([])
const inference = reactive({ relationType: 'PREREQUISITE_OF', depth: 3, result: null, loading: false })

const searchKeyword = ref('')
const searchOptions = ref([])

const DOMAIN_COLORS = {
  软件工程: SERIES[0],
  数据库: SERIES[2],
  知识管理: SERIES[1]
}

function domainColor(domain) {
  return DOMAIN_COLORS[domain] || INK.muted
}

onMounted(async () => {
  await loadMeta()
  if (route.query.conceptId) {
    await focusConcept(Number(route.query.conceptId))
  } else {
    await loadGraph()
  }
  loading.value = false
})

async function loadMeta() {
  try {
    const [stat, domainList, conceptList] = await Promise.all([
      ontologyApi.stats(),
      ontologyApi.domains(),
      ontologyApi.concepts()
    ])
    stats.value = stat || {}
    domains.value = domainList || []
    concepts.value = conceptList || []

    // 首次加载时默认全选，用户可再裁剪
    enabledDomains.value = new Set(domains.value)
    const types = await ontologyApi.relationTypes()
    graph.value.relationTypes = types || []
    enabledRelations.value = new Set((types || []).map((t) => t.code))
  } catch {
    /* 静默 */
  }
}

async function loadGraph() {
  graphLoading.value = true
  try {
    if (mode.value === 'neighborhood' && selectedConceptId.value) {
      graph.value = await ontologyApi.neighborhood(selectedConceptId.value, neighborhoodDepth.value)
    } else if (mode.value === 'domain' && enabledDomains.value.size === 1) {
      const only = [...enabledDomains.value][0]
      graph.value = await ontologyApi.graph({ domain: only, withIsolated: true })
    } else {
      graph.value = await ontologyApi.graph({ withIsolated: true })
    }
  } catch {
    /* 静默 */
  } finally {
    graphLoading.value = false
  }
}

/** 后端返回的是完整对象，筛选在前端做，避免每次勾选都请求一次 */
function applyFilters(data) {
  const nodes = (data.nodes || []).filter((n) => enabledDomains.value.has(n.domain))
  const nodeIds = new Set(nodes.map((n) => n.id))
  const edges = (data.edges || []).filter(
    (e) => enabledRelations.value.has(e.relationType) && nodeIds.has(e.source) && nodeIds.has(e.target)
  )
  return { nodes, edges }
}

const filtered = computed(() => applyFilters(graph.value))

/**
 * 依据节点数给出初始缩放比例。
 *
 * 力导向布局的跨度随节点数增长，而画布尺寸固定，因此需要按规模缩放，
 * 否则节点会落到可视区之外。取值是在本数据集上实测得出的：
 * 目标是让全图刚好占满画布高度，既不溢出也不留大片空白。
 */
function initialZoom(nodeCount) {
  if (nodeCount <= 30) return 1
  if (nodeCount <= 60) return 0.95
  if (nodeCount <= 120) return 0.85
  return 0.6
}

/**
 * 图谱配置。
 *
 * 视觉编码：节点颜色=知识域，节点大小=关联的知识条目数（对数压缩），
 * 边颜色=关系类型。边始终带文字标签，因此身份识别不依赖颜色单一通道 ——
 * 这对色觉障碍用户与黑白打印都是必要的。
 */
const chartOption = computed(() => {
  const { nodes, edges } = filtered.value
  const edgeLabelVisible = edges.length <= 60

  return {
    tooltip: {
      ...tooltip,
      formatter: (params) => {
        if (params.dataType === 'edge') {
          const e = params.data
          return `<b>${e.sourceName || ''} → ${e.targetName || ''}</b><br/>关系：${e.typeName || ''}`
        }
        const n = params.data
        return `<b>${n.name}</b><br/>
                知识域：${n.domain || '—'}<br/>
                关联知识条目：${n.itemCount} 条<br/>
                难度：${n.difficulty || '—'} / 5`
      }
    },
    legend: [
      {
        data: domains.value.filter((d) => enabledDomains.value.has(d)),
        top: 0,
        left: 0,
        ...{ textStyle: { color: INK.secondary, fontSize: 12, fontFamily: FONT_FAMILY } },
        itemWidth: 10,
        itemHeight: 10,
        icon: 'circle'
      }
    ],
    series: [
      {
        type: 'graph',
        layout: 'force',
        roam: true,
        draggable: true,
        // 100+ 节点时动画会明显拖慢交互，直接关闭；节点少时保留以获得更自然的布局
        animation: nodes.length > 80,
        categories: domains.value.map((d) => ({ name: d, itemStyle: { color: domainColor(d) } })),
        data: nodes.map((n) => ({
          id: String(n.id),
          name: n.name,
          domain: n.domain,
          itemCount: n.itemCount,
          difficulty: n.difficulty,
          symbolSize: Math.max(10, Math.min(46, n.value || 12)),
          category: n.domain,
          itemStyle: {
            color: domainColor(n.domain),
            borderColor: n.highlight ? INK.primary : 'rgba(252,252,251,0.9)',
            borderWidth: n.highlight ? 3 : 2
          },
          // 节点过多时默认不显示标签：一百多个标签挤在一起反而什么都读不出来，
          // 用户可通过缩放或悬停查看。图例与配色仍能传达知识域归属。
          label: {
            show: nodes.length <= 55,
            position: 'right',
            fontSize: 11,
            color: INK.secondary,
            fontFamily: FONT_FAMILY,
            formatter: '{b}'
          }
        })),
        links: edges.map((e) => ({
          id: String(e.id),
          source: String(e.source),
          target: String(e.target),
          typeName: e.relationTypeName,
          sourceName: nodes.find((n) => n.id === e.source)?.name,
          targetName: nodes.find((n) => n.id === e.target)?.name,
          lineStyle: {
            color: e.color || INK.baseline,
            width: 1.2,
            opacity: 0.75,
            curveness: 0.08
          },
          label: {
            show: edgeLabelVisible,
            formatter: e.label,
            fontSize: 10,
            color: INK.muted,
            fontFamily: FONT_FAMILY
          }
        })),
        // 初始缩放：力导向布局的总跨度随节点数增长，而画布是固定的，
        // 不缩放的话节点会大量落到可视区之外（表现为「图是空的」）。
        zoom: initialZoom(nodes.length),
        force: {
          // 斥力与边长随节点数反向调整：节点越多，单个斥力要越小、
          // 边长要越短，否则布局跨度会迅速超出画布。
          repulsion: nodes.length > 120 ? 90 : nodes.length > 60 ? 140 : 260,
          edgeLength: nodes.length > 120 ? [26, 60] : nodes.length > 60 ? [40, 90] : [70, 150],
          // 引力把节点拉向中心，抑制大图「炸开」
          gravity: nodes.length > 60 ? 0.32 : 0.12,
          friction: 0.2,
          layoutAnimation: nodes.length <= 80
        },
        emphasis: {
          focus: 'adjacency',
          label: { show: true, fontWeight: 600, color: INK.primary },
          lineStyle: { width: 3, opacity: 1 }
        },
        blur: { itemStyle: { opacity: 0.25 }, lineStyle: { opacity: 0.1 } }
      }
    ]
  }
})

function handleChartClick(params) {
  if (params.dataType === 'node') {
    const id = Number(params.data.id)
    selectConcept(id)
  }
}

async function selectConcept(id) {
  selectedConceptId.value = id
  try {
    const [concept, rels, items] = await Promise.all([
      ontologyApi.concept(id),
      ontologyApi.relations({ conceptId: id }),
      knowledgeApi.byConcepts([id], 6)
    ])
    detail.value = { ...concept, relations: rels || [] }
    relatedItems.value = items || []
  } catch {
    detail.value = null
  }
}

async function focusConcept(id) {
  mode.value = 'neighborhood'
  selectedConceptId.value = id
  await loadGraph()
  await selectConcept(id)
}

function switchToFullGraph() {
  mode.value = 'full'
  selectedConceptId.value = null
  detail.value = null
  relatedItems.value = []
  loadGraph()
}

function switchToNeighborhood() {
  if (!selectedConceptId.value) {
    ElMessage.info('请先在图谱中点击一个知识点')
    return
  }
  mode.value = 'neighborhood'
  loadGraph()
}

watch(neighborhoodDepth, () => {
  if (mode.value === 'neighborhood') loadGraph()
})

function searchConcept(query) {
  if (!query) {
    searchOptions.value = []
    return
  }
  ontologyApi.searchConcepts(query).then((res) => {
    searchOptions.value = res || []
  })
}

async function runInference() {
  if (!selectedConceptId.value) {
    ElMessage.info('请先在图谱中点击一个知识点')
    return
  }
  inference.loading = true
  try {
    inference.result = await ontologyApi.infer(
      selectedConceptId.value,
      inference.relationType,
      inference.depth
    )
  } catch {
    inference.result = null
  } finally {
    inference.loading = false
  }
}

/** 把推理结果拼成一条可读的路径说明 */
const inferencePaths = computed(() => {
  const levels = inference.result?.levels || []
  return levels.flatMap((lv) => (lv.paths || []).map((p) => ({ depth: lv.depth, path: p })))
})

function toggleRelation(code) {
  const next = new Set(enabledRelations.value)
  if (next.has(code)) next.delete(code)
  else next.add(code)
  enabledRelations.value = next
}

function toggleDomain(domain) {
  const next = new Set(enabledDomains.value)
  if (next.has(domain)) next.delete(domain)
  else next.add(domain)
  enabledDomains.value = next
}

function resetFilters() {
  enabledRelations.value = new Set(graph.value.relationTypes.map((t) => t.code))
  enabledDomains.value = new Set(domains.value)
}

function openItem(item) {
  router.push(`/knowledge/${item.id}`)
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h1 class="page-title">知识图谱</h1>
        <p class="page-subtitle">
          以本体描述知识的逻辑结构 —— {{ stats.conceptCount || 0 }} 个知识点、
          {{ stats.relationCount || 0 }} 条语义关系、{{ stats.relationTypeCount || 0 }} 种关系类型。
          点击节点可查看详情、关联知识与推理路径
        </p>
      </div>
      <div class="header-actions">
        <el-button :type="mode === 'full' ? 'primary' : ''" :icon="'Share'" @click="switchToFullGraph">
          全图
        </el-button>
        <el-button
          :type="mode === 'neighborhood' ? 'primary' : ''"
          :icon="'Aim'"
          @click="switchToNeighborhood"
        >
          邻域
        </el-button>
      </div>
    </div>

    <!-- ============ 统计条 ============ -->
    <el-row :gutter="14" class="stat-row">
      <el-col :xs="12" :sm="6" :md="4">
        <StatTile label="知识点" :value="stats.conceptCount || 0" icon="Grid" accent="var(--series-1)" />
      </el-col>
      <el-col :xs="12" :sm="6" :md="4">
        <StatTile label="语义关系" :value="stats.relationCount || 0" icon="Share" accent="var(--series-2)" />
      </el-col>
      <el-col :xs="12" :sm="6" :md="4">
        <StatTile label="关系类型" :value="stats.relationTypeCount || 0" icon="Connection" accent="var(--series-3)" />
      </el-col>
      <el-col :xs="12" :sm="6" :md="4">
        <StatTile label="层次深度" :value="stats.maxDepth || 0" icon="Sort" accent="var(--series-4)" />
      </el-col>
      <el-col :xs="12" :sm="6" :md="4">
        <StatTile
          label="本体覆盖率"
          :value="stats.coverage || 0"
          unit="%"
          icon="CircleCheck"
          accent="var(--series-7)"
          :hint="`${stats.coveredConceptCount || 0} 个知识点已被引用`"
        />
      </el-col>
      <el-col :xs="12" :sm="6" :md="4">
        <StatTile
          label="当前显示"
          :value="`${filtered.nodes.length} / ${filtered.edges.length}`"
          icon="View"
          accent="var(--series-5)"
          hint="节点 / 关系"
        />
      </el-col>
    </el-row>

    <div class="graph-layout">
      <!-- ============ 图谱区 ============ -->
      <div class="graph-main card">
        <!-- 控制条 -->
        <div class="graph-controls">
          <el-autocomplete
            v-model="searchKeyword"
            :fetch-suggestions="(q, cb) => searchConcept(q).then(() => cb(searchOptions.map((c) => ({ value: c.name, id: c.id }))))"
            placeholder="搜索知识点并定位"
            size="small"
            class="concept-search"
            clearable
            @select="(item) => focusConcept(item.id)"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-autocomplete>

          <el-select
            v-if="mode === 'neighborhood'"
            v-model="neighborhoodDepth"
            size="small"
            class="depth-select"
          >
            <el-option :value="1" label="1 跳邻域" />
            <el-option :value="2" label="2 跳邻域" />
            <el-option :value="3" label="3 跳邻域" />
          </el-select>

          <div class="domain-filter">
            <el-check-tag
              v-for="d in domains"
              :key="d"
              :checked="enabledDomains.has(d)"
              size="small"
              @change="toggleDomain(d)"
            >
              <span class="legend-dot" :style="{ background: domainColor(d) }" />{{ d }}
            </el-check-tag>
          </div>

          <el-button size="small" text :icon="'RefreshLeft'" @click="resetFilters">重置</el-button>
        </div>

        <!-- 关系类型筛选（同时也是图例） -->
        <div class="relation-filter">
          <span class="rf-label">关系类型</span>
          <el-check-tag
            v-for="t in graph.relationTypes"
            :key="t.code"
            :checked="enabledRelations.has(t.code)"
            size="small"
            class="rf-tag"
            @change="toggleRelation(t.code)"
          >
            <span class="edge-swatch" :style="{ background: t.color }" />
            {{ t.name }}
            <span class="rf-count">{{ t.count }}</span>
          </el-check-tag>
        </div>

        <div v-loading="graphLoading || loading" class="graph-body">
          <EChart
            v-if="filtered.nodes.length"
            :option="chartOption"
            height="560"
            @click="handleChartClick"
          />
          <el-empty v-else description="当前筛选条件下没有可显示的知识点" />
        </div>

        <div class="graph-hint">
          节点颜色表示知识域，节点大小表示关联的知识条目数量；连线颜色表示关系类型，
          连线上的文字即关系名称。滚轮缩放、拖拽平移，点击节点查看详情。
        </div>
      </div>

      <!-- ============ 侧栏 ============ -->
      <aside class="graph-side">
        <!-- 知识点详情 -->
        <div class="side-card card">
          <h3 class="section-title">知识点详情</h3>

          <div v-if="detail" class="detail-body">
            <div class="d-name">{{ detail.name }}</div>
            <div v-if="detail.enName" class="d-en">{{ detail.enName }}</div>

            <div class="d-tags">
              <el-tag size="small" :color="domainColor(detail.domain)" effect="dark" disable-transitions class="d-domain">
                {{ detail.domain || '未分类' }}
              </el-tag>
              <el-tag size="small" effect="plain" type="info">层级 L{{ detail.level }}</el-tag>
              <el-tag v-if="detail.builtin === 1" size="small" effect="plain">内置</el-tag>
            </div>

            <p v-if="detail.description" class="d-desc">{{ detail.description }}</p>

            <div v-if="detail.aliases && detail.aliases.length" class="d-row">
              <span class="d-label">别名</span>
              <span class="d-value">{{ detail.aliases.join('、') }}</span>
            </div>
            <div v-if="detail.parentName" class="d-row">
              <span class="d-label">上位概念</span>
              <span class="d-value">{{ detail.parentName }}</span>
            </div>
            <div class="d-row">
              <span class="d-label">代码</span>
              <span class="d-value mono">{{ detail.code }}</span>
            </div>
            <div class="d-row">
              <span class="d-label">难度 / 重要度</span>
              <span class="d-value">
                <el-rate :model-value="detail.difficulty" disabled size="small" :max="5" />
                <span class="rate-num">{{ detail.difficulty }} · {{ detail.importance }}</span>
              </span>
            </div>
            <div class="d-row">
              <span class="d-label">关联知识</span>
              <span class="d-value">{{ detail.itemCount }} 条</span>
            </div>

            <!-- 关系列表：也可作为“非视觉”的等价视图 -->
            <div v-if="detail.relations && detail.relations.length" class="d-relations">
              <div class="d-label" style="margin-bottom: 6px">
                本体关系（{{ detail.relations.length }}）
              </div>
              <div v-for="r in detail.relations.slice(0, 12)" :key="r.id" class="rel-item">
                <span class="rel-stmt">{{ r.statement }}</span>
              </div>
            </div>

            <!-- 关联知识条目 -->
            <div v-if="relatedItems.length" class="d-related">
              <div class="d-label" style="margin-bottom: 6px">关联知识条目</div>
              <div v-for="it in relatedItems" :key="it.id" class="related-item" @click="openItem(it)">
                <el-icon :size="12"><Document /></el-icon>
                <span class="ri-title">{{ it.title }}</span>
                <el-tag size="small" effect="plain" disable-transitions class="ri-type">
                  {{ assetTypeMeta(it.assetType).label }}
                </el-tag>
              </div>
            </div>
          </div>

          <el-empty v-else description="点击图谱中的节点查看知识点详情" :image-size="70" />
        </div>

        <!-- 本体推理 -->
        <div class="side-card card">
          <h3 class="section-title">
            学习路径推理
            <span class="section-hint">沿关系逐层展开</span>
          </h3>

          <div class="infer-controls">
            <el-select v-model="inference.relationType" size="small" class="full-width">
              <el-option
                v-for="t in graph.relationTypes"
                :key="t.code"
                :label="t.name"
                :value="t.code"
              />
            </el-select>
            <div class="infer-row">
              <el-slider v-model="inference.depth" :min="1" :max="5" size="small" class="depth-slider" />
              <span class="depth-text">{{ inference.depth }} 层</span>
              <el-button size="small" type="primary" :loading="inference.loading" @click="runInference">
                推理
              </el-button>
            </div>
          </div>

          <div v-if="inferencePaths.length" class="infer-result">
            <div class="infer-origin">
              起点：<b>{{ inference.result.originName }}</b>
              · 沿「{{ graph.relationTypes.find((t) => t.code === inference.relationType)?.name }}」
            </div>
            <div v-for="(p, i) in inferencePaths.slice(0, 14)" :key="i" class="infer-path">
              <span class="path-depth">L{{ p.depth }}</span>
              <span class="path-text">{{ p.path }}</span>
            </div>
            <div v-if="inferencePaths.length > 14" class="infer-more">
              还有 {{ inferencePaths.length - 14 }} 条路径……
            </div>
          </div>

          <div v-else class="infer-hint">
            选择关系类型与展开层数后点击「推理」。
            例如从「关系模型」出发沿「先修」关系展开，
            即可得到一条有依据的学习顺序。
          </div>
        </div>
      </aside>
    </div>
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

.graph-layout {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}

.graph-main {
  flex: 1;
  min-width: 0;
  padding: 14px 16px 12px;
}

/* ---------------- 控制条 ---------------- */
.graph-controls {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}

.concept-search {
  width: 220px;
}

.depth-select {
  width: 116px;
}

.domain-filter {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-left: auto;
}

.legend-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 5px;
  vertical-align: -1px;
}

/* ---------------- 关系筛选 ---------------- */
.relation-filter {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding: 9px 12px;
  background: var(--page-plane);
  border-radius: 8px;
  margin-bottom: 10px;
}

.rf-label {
  font-size: 12px;
  color: var(--ink-muted);
  margin-right: 2px;
}

.rf-tag {
  font-size: 12px;
}

.edge-swatch {
  display: inline-block;
  width: 12px;
  height: 3px;
  border-radius: 2px;
  margin-right: 5px;
  vertical-align: 2px;
}

.rf-count {
  color: var(--ink-muted);
  font-size: 11px;
  margin-left: 3px;
}

.graph-body {
  min-height: 560px;
  border-radius: 10px;
  background: #fff;
}

.graph-hint {
  font-size: 12px;
  color: var(--ink-muted);
  line-height: 1.7;
  padding: 8px 2px 0;
  border-top: 1px solid #f2f3f5;
  margin-top: 8px;
}

/* ---------------- 侧栏 ---------------- */
.graph-side {
  width: 340px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.side-card {
  padding: 16px 18px;
}

.detail-body {
  font-size: 13px;
}

.d-name {
  font-size: 17px;
  font-weight: 650;
  line-height: 1.35;
}

.d-en {
  font-size: 12px;
  color: var(--ink-muted);
  margin-top: 2px;
}

.d-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin: 10px 0;
}

.d-domain {
  border: none;
  color: #fff;
}

.d-desc {
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--ink-secondary);
  margin: 0 0 12px;
  padding: 9px 12px;
  background: var(--page-plane);
  border-radius: 7px;
}

.d-row {
  display: flex;
  gap: 10px;
  padding: 5px 0;
  font-size: 12.5px;
  align-items: flex-start;
}

.d-label {
  color: var(--ink-muted);
  width: 76px;
  flex-shrink: 0;
  font-size: 12px;
}

.d-value {
  flex: 1;
  min-width: 0;
  color: var(--ink-primary);
  word-break: break-all;
  display: flex;
  align-items: center;
  gap: 6px;
}

.mono {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 11.5px;
  color: var(--ink-secondary);
}

.rate-num {
  font-size: 11.5px;
  color: var(--ink-muted);
}

.d-relations,
.d-related {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--gridline);
}

.rel-item {
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-secondary);
  padding: 3px 0;
}

.rel-stmt {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.related-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 0;
  font-size: 12.5px;
  cursor: pointer;
  color: var(--ink-secondary);

  &:hover .ri-title {
    color: var(--app-primary);
  }
}

.ri-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: color 0.14s ease;
}

.ri-type {
  flex-shrink: 0;
}

/* ---------------- 推理 ---------------- */
.infer-controls {
  margin-bottom: 12px;
}

.full-width {
  width: 100%;
}

.infer-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
}

.depth-slider {
  flex: 1;
}

.depth-text {
  font-size: 12px;
  color: var(--ink-muted);
  width: 30px;
  flex-shrink: 0;
}

.infer-result {
  border-top: 1px dashed var(--gridline);
  padding-top: 12px;
}

.infer-origin {
  font-size: 12.5px;
  color: var(--ink-secondary);
  margin-bottom: 8px;
  line-height: 1.6;
}

.infer-path {
  display: flex;
  gap: 8px;
  padding: 5px 0;
  font-size: 12px;
  line-height: 1.6;
  align-items: flex-start;
}

.path-depth {
  flex-shrink: 0;
  width: 22px;
  height: 18px;
  border-radius: 4px;
  background: var(--app-primary-soft);
  color: var(--app-primary);
  font-size: 10.5px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 2px;
}

.path-text {
  color: var(--ink-secondary);
  word-break: break-all;
}

.infer-more {
  font-size: 11.5px;
  color: var(--ink-muted);
  margin-top: 6px;
}

.infer-hint {
  font-size: 12px;
  line-height: 1.8;
  color: var(--ink-muted);
  padding-top: 10px;
  border-top: 1px dashed var(--gridline);
}

@media (max-width: 1200px) {
  .graph-layout {
    flex-direction: column;
  }

  .graph-side {
    width: 100%;
  }
}
</style>
