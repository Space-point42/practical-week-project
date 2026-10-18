<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { statsApi, knowledgeApi, versionApi } from '@/api'
import { SERIES, INK, FONT_FAMILY, tooltip, legend, grid, categoryAxis, valueAxis } from '@/utils/echartsTheme'
import { formatDateTime, fromNow, assetTypeMeta } from '@/utils/format'
import StatTile from '@/components/StatTile.vue'
import ChartCard from '@/components/ChartCard.vue'

const router = useRouter()

const loading = ref(true)
const overview = ref({})
const distribution = ref({})
const trend = ref([])
const hotItems = ref([])
const recentItems = ref([])
const activities = ref([])
const recentVersions = ref([])

const ACTION_TEXT = {
  CREATE: '创建', UPDATE: '更新', DELETE: '删除', PUBLISH: '发布', UNPUBLISH: '撤回发布',
  SUBMIT: '提交审核', ARCHIVE: '归档', UNARCHIVE: '恢复归档', ROLLBACK: '版本回滚',
  CREATE_VERSION: '创建版本', LOGIN: '登录', REGISTER: '注册', CHANGE_PASSWORD: '修改口令'
}

const TARGET_TEXT = {
  ITEM: '知识条目', CONCEPT: '知识点', RELATION: '本体关系', CATEGORY: '分类',
  USER: '用户', POLICY: '版本策略'
}

onMounted(load)

async function load() {
  loading.value = true
  try {
    const [ov, dist, tr, hot, recent, acts, vers] = await Promise.all([
      statsApi.overview(),
      statsApi.distribution(),
      statsApi.trend(14),
      knowledgeApi.hot(6),
      knowledgeApi.recent(6),
      statsApi.activity(8),
      versionApi.recent(6)
    ])
    overview.value = ov
    distribution.value = dist
    trend.value = tr
    hotItems.value = hot
    recentItems.value = recent
    activities.value = acts
    recentVersions.value = vers
  } finally {
    loading.value = false
  }
}

// ---------------------------------------------------------------------
// 图表配置
//
// 分布类图统一用横向条形图 + 单一色相：类别名已在轴上标明，
// 再给每根条上不同颜色只会让读者去猜颜色代表什么（彩虹条形图是典型反模式）。
// 只有「需要区分身份」的多系列图表才使用分类色板。
// ---------------------------------------------------------------------

const H_BAR_HEIGHT = 260

/** 生成横向条形图的通用配置 */
function hBarOption(data, colorIndex = 0) {
  const rows = [...(data || [])].sort((a, b) => a.value - b.value)
  return {
    grid: { left: 8, right: 40, top: 8, bottom: 8, containLabel: true },
    tooltip: { ...tooltip, trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { ...valueAxis, splitLine: { show: false }, axisLabel: { show: false } },
    yAxis: {
      type: 'category',
      data: rows.map((d) => d.name),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: INK.secondary, fontSize: 12, fontFamily: FONT_FAMILY }
    },
    series: [
      {
        type: 'bar',
        data: rows.map((d) => d.value),
        barWidth: 12,
        itemStyle: { color: SERIES[colorIndex], borderRadius: [0, 4, 4, 0] },
        label: {
          show: true,
          position: 'right',
          color: INK.secondary,
          fontSize: 12,
          fontFamily: FONT_FAMILY
        }
      }
    ]
  }
}

/** 趋势折线：两个系列（新增 / 发布），配图例 */
const trendOption = computed(() => {
  const rows = trend.value || []
  return {
    grid: { ...grid, top: 34 },
    tooltip: { ...tooltip, trigger: 'axis', axisPointer: { type: 'line', lineStyle: { color: INK.baseline } } },
    legend: { ...legend, top: 0, right: 0, data: ['新增条目', '发布条目'] },
    xAxis: {
      ...categoryAxis,
      boundaryGap: false,
      data: rows.map((d) => d.date.slice(5))
    },
    yAxis: { ...valueAxis, minInterval: 1 },
    series: [
      {
        name: '新增条目',
        type: 'line',
        smooth: true,
        showSymbol: false,
        symbolSize: 8,
        lineStyle: { width: 2, color: SERIES[0] },
        itemStyle: { color: SERIES[0] },
        areaStyle: {
          color: {
            type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(42,120,214,0.18)' },
              { offset: 1, color: 'rgba(42,120,214,0.01)' }
            ]
          }
        },
        data: rows.map((d) => d.created)
      },
      {
        name: '发布条目',
        type: 'line',
        smooth: true,
        showSymbol: false,
        symbolSize: 8,
        lineStyle: { width: 2, color: SERIES[1] },
        itemStyle: { color: SERIES[1] },
        data: rows.map((d) => d.published)
      }
    ]
  }
})

/** 知识域分布 —— 单一蓝色 */
const domainOption = computed(() => hBarOption(distribution.value.domains, 0))

/** 分类分布 —— 单一蓝色（同一页面出现第二个顺序量时改用第二个色相，此处仍属「量」的范畴） */
const categoryOption = computed(() => hBarOption(distribution.value.categories, 2))

/** 资产类型分布 */
const assetTypeOption = computed(() => hBarOption(distribution.value.assetTypes, 1))

// 表格视图数据（图表的等价文本表示）
const domainTable = computed(() => distribution.value.domains || [])
const categoryTable = computed(() => distribution.value.categories || [])
const assetTypeTable = computed(() => distribution.value.assetTypes || [])
const trendTable = computed(() =>
  (trend.value || []).map((d) => ({ name: d.date, value: `${d.created} / ${d.published}` }))
)

const ontologyCoverage = computed(() => overview.value.ontologyCoverage || 0)
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h1 class="page-title">首页看板</h1>
        <p class="page-subtitle">
          知识库整体概况 · 数据每 {{ 60 }} 秒刷新一次，最近更新于 {{ fromNow(new Date()) }}
        </p>
      </div>
      <div class="header-actions">
        <el-button :icon="'Refresh'" @click="load">刷新</el-button>
        <el-button type="primary" :icon="'Search'" @click="router.push('/search')">高级检索</el-button>
      </div>
    </div>

    <!-- ============ 核心指标 ============ -->
    <el-row :gutter="14" class="stat-row">
      <el-col :xs="12" :sm="8" :md="6" :lg="4">
        <StatTile
          label="知识条目"
          :value="overview.knowledgeCount || 0"
          icon="Collection"
          accent="var(--series-1)"
          :hint="`已发布 ${overview.publishedCount || 0} 条`"
        />
      </el-col>
      <el-col :xs="12" :sm="8" :md="6" :lg="4">
        <StatTile
          label="知识点"
          :value="overview.conceptCount || 0"
          icon="Grid"
          accent="var(--series-3)"
          :hint="`覆盖率 ${ontologyCoverage}%`"
        />
      </el-col>
      <el-col :xs="12" :sm="8" :md="6" :lg="4">
        <StatTile
          label="本体关系"
          :value="overview.relationCount || 0"
          icon="Share"
          accent="var(--series-7)"
          :hint="`${overview.relationTypeCount || 0} 种关系类型`"
        />
      </el-col>
      <el-col :xs="12" :sm="8" :md="6" :lg="4">
        <StatTile
          label="多媒体附件"
          :value="overview.attachmentCount || 0"
          icon="Files"
          accent="var(--series-4)"
          :hint="`视频 ${overview.videoCount || 0} · 图片 ${overview.imageCount || 0}`"
        />
      </el-col>
      <el-col :xs="12" :sm="8" :md="6" :lg="4">
        <StatTile
          label="版本快照"
          :value="overview.versionCount || 0"
          icon="Timer"
          accent="var(--series-5)"
          hint="按策略自动留存"
        />
      </el-col>
      <el-col :xs="12" :sm="8" :md="6" :lg="4">
        <StatTile
          label="检索分块"
          :value="overview.chunkCount || 0"
          icon="Connection"
          accent="var(--series-6)"
          hint="供 RAG 混合检索召回"
        />
      </el-col>
    </el-row>

    <!-- ============ 趋势与分布 ============ -->
    <el-row :gutter="14" class="chart-row">
      <el-col :xs="24" :lg="16">
        <ChartCard
          title="近 14 天内容趋势"
          subtitle="新增与发布的条目数"
          :option="trendOption"
          :height="286"
          :loading="loading"
          :table-data="trendTable"
          :table-columns="['日期', '新增 / 发布']"
        />
      </el-col>
      <el-col :xs="24" :lg="8">
        <ChartCard
          title="知识域分布"
          subtitle="按本体概念所属知识域统计"
          :option="domainOption"
          :height="286"
          :loading="loading"
          :table-data="domainTable"
          :table-columns="['知识域', '知识点数']"
        />
      </el-col>
    </el-row>

    <el-row :gutter="14" class="chart-row">
      <el-col :xs="24" :lg="8">
        <ChartCard
          title="资产类型分布"
          subtitle="文档 / 报告 / 论文 / 视频等"
          :option="assetTypeOption"
          :height="260"
          :loading="loading"
          :table-data="assetTypeTable"
          :table-columns="['资产类型', '条目数']"
        />
      </el-col>
      <el-col :xs="24" :lg="8">
        <ChartCard
          title="分类分布"
          subtitle="条目数最多的分类"
          :option="categoryOption"
          :height="260"
          :loading="loading"
          :table-data="categoryTable"
          :table-columns="['分类', '条目数']"
        />
      </el-col>
      <el-col :xs="24" :lg="8">
        <div class="chart-card">
          <h3 class="chart-title">知识库健康度</h3>
          <div class="health-body">
            <el-progress
              type="dashboard"
              :percentage="ontologyCoverage"
              :width="118"
              :stroke-width="10"
              color="#2a78d6"
            >
              <template #default="{ percentage }">
                <div class="health-value tabular">{{ percentage }}%</div>
                <div class="health-label">本体覆盖率</div>
              </template>
            </el-progress>
            <div class="health-notes">
              <div class="health-note">
                <span class="hn-dot" style="background: var(--series-1)" />
                <span class="hn-text">
                  {{ overview.conceptCount || 0 }} 个知识点中，
                  已有 {{ overview.ontologyCoverage ? Math.round((overview.conceptCount || 0) * ontologyCoverage / 100) : 0 }}
                  个被知识条目引用
                </span>
              </div>
              <div class="health-note">
                <span class="hn-dot" style="background: var(--series-3)" />
                <span class="hn-text">
                  未覆盖的知识点即知识库的内容缺口，可在「知识点管理」中查看
                </span>
              </div>
              <div class="health-note">
                <span class="hn-dot" style="background: var(--series-4)" />
                <span class="hn-text">
                  本周新增 {{ overview.weekNewCount || 0 }} 条，今日新增 {{ overview.todayNewCount || 0 }} 条
                </span>
              </div>
            </div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- ============ 列表区 ============ -->
    <el-row :gutter="14" class="chart-row">
      <el-col :xs="24" :lg="6">
        <div class="list-card">
          <div class="list-head">
            <h3 class="chart-title">热门知识</h3>
            <span class="chart-sub">按浏览量</span>
          </div>
          <div v-if="hotItems.length" class="list-body">
            <div
              v-for="(item, idx) in hotItems"
              :key="item.id"
              class="rank-item"
              @click="router.push(`/knowledge/${item.id}`)"
            >
              <span class="rank-no" :class="{ top: idx < 3 }">{{ idx + 1 }}</span>
              <div class="rank-main">
                <div class="rank-title">{{ item.title }}</div>
                <div class="rank-meta">
                  <el-tag size="small" effect="plain" disable-transitions>
                    {{ assetTypeMeta(item.assetType).label }}
                  </el-tag>
                  <span>{{ item.viewCount }} 次浏览</span>
                </div>
              </div>
            </div>
          </div>
          <div v-else class="empty-hint">暂无数据</div>
        </div>
      </el-col>

      <el-col :xs="24" :lg="6">
        <div class="list-card">
          <div class="list-head">
            <h3 class="chart-title">最近更新</h3>
            <span class="chart-sub">按更新时间</span>
          </div>
          <div v-if="recentItems.length" class="list-body">
            <div
              v-for="item in recentItems"
              :key="item.id"
              class="rank-item"
              @click="router.push(`/knowledge/${item.id}`)"
            >
              <div class="rank-main">
                <div class="rank-title">{{ item.title }}</div>
                <div class="rank-meta">
                  <span>{{ fromNow(item.updatedAt) }}</span>
                  <span v-if="item.authorName">· {{ item.authorName }}</span>
                </div>
              </div>
            </div>
          </div>
          <div v-else class="empty-hint">暂无数据</div>
        </div>
      </el-col>

      <el-col :xs="24" :lg="6">
        <div class="list-card">
          <div class="list-head">
            <h3 class="chart-title">版本动态</h3>
            <span class="chart-sub">最近生成的快照</span>
          </div>
          <div v-if="recentVersions.length" class="list-body">
            <div
              v-for="v in recentVersions"
              :key="v.id"
              class="rank-item"
              @click="router.push(`/knowledge/${v.itemId}/versions`)"
            >
              <el-tag size="small" type="primary" effect="plain" disable-transitions class="v-chip">
                v{{ v.versionNo }}
              </el-tag>
              <div class="rank-main">
                <div class="rank-title">{{ v.title }}</div>
                <div class="rank-meta">
                  <span>{{ v.changeSourceName }}</span>
                  <span>· {{ fromNow(v.createdAt) }}</span>
                </div>
              </div>
            </div>
          </div>
          <div v-else class="empty-hint">暂无数据</div>
        </div>
      </el-col>

      <el-col :xs="24" :lg="6">
        <div class="list-card">
          <div class="list-head">
            <h3 class="chart-title">最新动态</h3>
            <span class="chart-sub">审计日志</span>
          </div>
          <div v-if="activities.length" class="list-body">
            <div v-for="a in activities" :key="a.id" class="activity-item">
              <span class="act-dot" />
              <div class="act-main">
                <div class="act-text">
                  <b>{{ a.username || '系统' }}</b>
                  {{ ACTION_TEXT[a.action] || a.action }}
                  {{ TARGET_TEXT[a.targetType] || '' }}
                </div>
                <div class="act-detail">{{ a.detail }}</div>
                <div class="act-time" :title="formatDateTime(a.createdAt)">
                  {{ fromNow(a.createdAt) }}
                </div>
              </div>
            </div>
          </div>
          <div v-else class="empty-hint">暂无数据</div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.header-actions {
  display: flex;
  gap: 10px;
}

.stat-row {
  margin-bottom: 14px;

  :deep(.el-col) {
    margin-bottom: 14px;
  }
}

.chart-row {
  margin-bottom: 14px;

  :deep(.el-col) {
    margin-bottom: 14px;
  }
}

.chart-title {
  font-size: 14px;
  font-weight: 600;
  margin: 0;
}

.chart-card {
  padding: 16px 18px;
}

/* ---------------- 健康度 ---------------- */
.health-body {
  display: flex;
  align-items: center;
  gap: 18px;
  margin-top: 8px;
}

.health-value {
  font-size: 20px;
  font-weight: 700;
  line-height: 1.2;
}

.health-label {
  font-size: 11px;
  color: var(--ink-muted);
  margin-top: 2px;
}

.health-notes {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.health-note {
  display: flex;
  gap: 8px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-secondary);
}

.hn-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-top: 6px;
  flex-shrink: 0;
}

/* ---------------- 列表卡片 ---------------- */
.list-card {
  background: #fff;
  border: 1px solid var(--hairline);
  border-radius: var(--app-radius);
  padding: 16px 18px;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.list-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 12px;
}

.chart-sub {
  font-size: 12px;
  color: var(--ink-muted);
}

.list-body {
  flex: 1;
  overflow-y: auto;
  max-height: 320px;
}

.rank-item {
  display: flex;
  gap: 10px;
  padding: 9px 0;
  border-bottom: 1px solid #f2f3f5;
  cursor: pointer;
  align-items: flex-start;

  &:last-child {
    border-bottom: none;
  }

  &:hover .rank-title {
    color: var(--app-primary);
  }
}

.rank-no {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
  border-radius: 5px;
  background: var(--page-plane);
  color: var(--ink-muted);
  font-size: 11px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 1px;

  &.top {
    background: var(--app-primary-soft);
    color: var(--app-primary);
  }
}

.v-chip {
  flex-shrink: 0;
  margin-top: 1px;
}

.rank-main {
  min-width: 0;
  flex: 1;
}

.rank-title {
  font-size: 13px;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  transition: color 0.14s ease;
}

.rank-meta {
  font-size: 11.5px;
  color: var(--ink-muted);
  margin-top: 4px;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

/* ---------------- 动态 ---------------- */
.activity-item {
  display: flex;
  gap: 10px;
  padding: 9px 0;
  border-bottom: 1px solid #f2f3f5;

  &:last-child {
    border-bottom: none;
  }
}

.act-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--series-1);
  margin-top: 7px;
  flex-shrink: 0;
}

.act-main {
  min-width: 0;
  flex: 1;
}

.act-text {
  font-size: 12.5px;
  line-height: 1.5;

  b {
    font-weight: 600;
  }
}

.act-detail {
  font-size: 11.5px;
  color: var(--ink-muted);
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.act-time {
  font-size: 11px;
  color: var(--ink-muted);
  margin-top: 2px;
}

@media (max-width: 1200px) {
  .health-body {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
