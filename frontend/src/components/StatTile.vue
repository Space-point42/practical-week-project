<script setup>
/**
 * 统计磁贴。
 *
 * 设计取舍：单一数值不画图 —— 一个 60 分的饼图不如一个大字号数字
 * 加一句说明来得直接。只有需要比较多个值时，才交给图表组件。
 */
import { computed } from 'vue'
import { abbreviateNumber, formatNumber } from '@/utils/format'

const props = defineProps({
  label: { type: String, required: true },
  value: { type: [Number, String], default: 0 },
  unit: { type: String, default: '' },
  icon: { type: String, default: '' },
  accent: { type: String, default: 'var(--series-1)' },
  hint: { type: String, default: '' },
  /** 是否用缩写显示大数（列表里更紧凑） */
  compact: { type: Boolean, default: false }
})

const display = computed(() => {
  if (typeof props.value === 'string') return props.value
  return props.compact ? abbreviateNumber(props.value) : formatNumber(props.value)
})
</script>

<template>
  <div class="stat-tile">
    <div class="stat-label">
      <span class="stat-accent" :style="{ background: accent }" />
      <el-icon v-if="icon" :size="14"><component :is="icon" /></el-icon>
      <span>{{ label }}</span>
    </div>
    <div class="stat-value tabular">
      {{ display }}<span v-if="unit" class="stat-unit">{{ unit }}</span>
    </div>
    <div v-if="hint" class="stat-foot">{{ hint }}</div>
  </div>
</template>
