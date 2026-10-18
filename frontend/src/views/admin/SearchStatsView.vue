<script setup>
import { computed, onMounted, ref } from 'vue'
import { searchApi } from '@/api'
import { SERIES, INK, FONT_FAMILY, tooltip, valueAxis } from '@/utils/echartsTheme'
import { formatDuration } from '@/utils/format'
import StatTile from '@/components/StatTile.vue'
import ChartCard from '@/components/ChartCard.vue'

/**
 * 检索分析。
 *
 * 两个榜单都是「量」的比较，因此使用单一蓝色横向条形图 —— 类别名已在
 * 轴上给出，再给每根条上不同颜色只会误导读者以为颜色另有含义。
 */
const loading = ref(true)
const stats = ref({})

const hotRows = computed(() =>
  (stats.value.hotKeywords || []).map((k) => ({ name: k.keyword, value: k.count }))
)
const noResultRows = computed(() =>
  (stats.value.noResultKeywords || []).map((k) => ({ name: k.keyword, value: k.count }))
)

onMounted(load)

async function load() {
  loading.value = true
  try {
    stats.value = (await searchApi.stats(10)) || {}
  } finally {
    loading.value = false
  }
}

/**
 * 横向条形图通用配置。
 *
 * ECharts 的类目轴自下而上排列，故升序排序后最大值会落在顶部。
 */
function hBarOption(rows) {
  const data = [...(rows || [])].sort((a, b) => a.value - b.value)
  return {
    grid: { left: 8, right: 44, top: 8, bottom: 8, containLabel: true },
    tooltip: { ...tooltip, trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { ...valueAxis, axisLabel: { show: false }, splitLine: { show: false } },
    yAxis: {
      type: 'category',
      data: data.map((d) => d.name),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: INK.secondary, fontSize: 12, fontFamily: FONT_FAMILY }
    },
    series: [
      {
        type: 'bar',
        data: data.map((d) => d.value),
        barWidth: 12,
        itemStyle: { color: SERIES[0], borderRadius: [0, 4, 4, 0] },
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

const hotOption = computed(() => hBarOption(hotRows.value))
const noResultOption = computed(() => hBarOption(noResultRows.value))
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h1 class="page-title">检索分析</h1>
        <p class="page-subtitle">从用户的检索行为反推知识库的热点与内容缺口</p>
      </div>
      <el-button :icon="'Refresh'" @click="load">刷新</el-button>
    </div>

    <!-- ============ 概览指标 ============ -->
    <el-row :gutter="14" class="stat-row">
      <el-col :xs="12" :sm="12" :md="6">
        <StatTile
          label="近 30 天检索次数"
          :value="Number(stats.totalSearches || 0)"
          icon="Search"
          accent="var(--series-1)"
          hint="含普通检索与高级检索"
        />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatTile
          label="平均耗时"
          :value="formatDuration(stats.averageCostMs || 0)"
          icon="Timer"
          accent="var(--series-4)"
          hint="单次检索的端到端平均响应时间"
        />
      </el-col>
    </el-row>

    <!-- ============ 榜单 ============ -->
    <el-row :gutter="14" class="chart-row">
      <el-col :xs="24" :lg="12">
        <ChartCard
          v-if="hotRows.length"
          title="热搜词"
          subtitle="用户检索最多的关键词"
          :option="hotOption"
          :height="340"
          :loading="loading"
          :table-data="hotRows"
          :table-columns="['检索词', '次数']"
        />
        <div v-else class="card chart-card empty-card">
          <h3 class="chart-title">热搜词</h3>
          <div class="empty-hint">暂无检索记录</div>
        </div>
      </el-col>

      <el-col :xs="24" :lg="12">
        <ChartCard
          v-if="noResultRows.length"
          title="零结果检索词"
          subtitle="搜过但知识库没有覆盖的词 —— 这些正是内容缺口所在"
          :option="noResultOption"
          :height="340"
          :loading="loading"
          :table-data="noResultRows"
          :table-columns="['检索词', '次数']"
        />
        <div v-else class="card chart-card empty-card">
          <h3 class="chart-title">零结果检索词</h3>
          <div class="empty-hint">暂无零结果检索，说明当前语料覆盖情况良好</div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.stat-row {
  margin-bottom: 0;

  :deep(.el-col) {
    margin-bottom: 14px;
  }
}

.chart-row {
  :deep(.el-col) {
    margin-bottom: 14px;
  }
}

.chart-title {
  font-size: 14px;
  font-weight: 600;
  margin: 0;
}

.empty-card {
  height: 100%;
}

.empty-card .empty-hint {
  padding: 60px 0;
}

@media (min-width: 1200px) {
  .empty-card {
    min-height: 380px;
  }
}
</style>
