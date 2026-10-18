<script setup>
/**
 * 图表卡片：标题 + 图表 + 等价数据表。
 *
 * 「表格视图」不是可选项而是必需项：图表用颜色传达的差异，
 * 对色觉障碍用户或黑白打印场景会丢失；一张数据表保证信息始终可达。
 */
import { ref } from 'vue'
import EChart from './EChart.vue'

const props = defineProps({
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
  option: { type: Object, required: true },
  height: { type: [String, Number], default: 280 },
  loading: { type: Boolean, default: false },
  /** 表格视图的数据：[{ name, value }, ...] */
  tableData: { type: Array, default: () => [] },
  /** 表格列名 */
  tableColumns: { type: Array, default: () => ['名称', '数量'] }
})

const showTable = ref(false)
</script>

<template>
  <div class="chart-card">
    <div class="chart-head">
      <div>
        <h3 class="chart-title">{{ title }}</h3>
        <div v-if="subtitle" class="chart-sub">{{ subtitle }}</div>
      </div>
      <span
        v-if="tableData.length"
        class="chart-table-toggle no-print"
        @click="showTable = !showTable"
      >
        {{ showTable ? '显示图表' : '显示数据表' }}
      </span>
    </div>

    <div v-show="!showTable" class="chart-body">
      <EChart :option="option" :height="height" :loading="loading" />
    </div>

    <div v-if="showTable" class="chart-body" :style="{ height: `${Number(height) || 280}px`, overflow: 'auto' }">
      <el-table :data="tableData" size="small" stripe max-height="100%">
        <el-table-column prop="name" :label="tableColumns[0]" show-overflow-tooltip />
        <el-table-column prop="value" :label="tableColumns[1]" width="110" align="right">
          <template #default="{ row }">
            <span class="tabular">{{ row.value }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>
