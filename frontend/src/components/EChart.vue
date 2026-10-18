<script setup>
/**
 * ECharts 轻封装。
 *
 * 负责三件容易出错的事：
 *   1. 实例的创建与销毁（component unmount 时必须 dispose，否则内存泄漏）；
 *   2. 容器尺寸变化时 resize（含侧边栏折叠、窗口缩放）；
 *   3. option 变化时增量更新而非重建（setOption 的 notMerge 语义）。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  option: { type: Object, required: true },
  height: { type: [String, Number], default: 280 },
  /** 数据加载中时显示骨架，避免图表区域先空后跳 */
  loading: { type: Boolean, default: false }
})

const emit = defineEmits(['click', 'ready'])

const el = ref(null)
const chart = shallowRef(null)
let observer = null

/**
 * 归一化高度为合法的 CSS 值。
 *
 * 必须补单位：`height="560"` 传入的是字符串 "560"，
 * 而 `height: 560` 是非法声明会被浏览器丢弃 —— 容器高度为 0，
 * ECharts 便会以一个 0 高度的画布初始化，表现为「图表区域一片空白」。
 * 数值型 props 之所以正常，正是因为模板里写的是 `:height="286"`。
 */
const heightStyle = computed(() => {
  const h = props.height
  if (typeof h === 'number') return `${h}px`
  const s = String(h ?? '').trim()
  return /^\d+(\.\d+)?$/.test(s) ? `${s}px` : s
})

function render() {
  if (!chart.value) return
  // notMerge=true：系列数量变化时（如筛选后从 5 个变 3 个）避免残留旧系列
  chart.value.setOption(props.option, true)
}

function init() {
  if (!el.value) return
  chart.value = echarts.init(el.value, null, { renderer: 'canvas' })
  chart.value.on('click', (params) => emit('click', params))
  render()
  emit('ready', chart.value)
}

onMounted(async () => {
  await nextTick()
  init()

  // 容器尺寸变化（窗口缩放、侧边栏收起）时重绘
  if (typeof ResizeObserver !== 'undefined' && el.value) {
    observer = new ResizeObserver(() => chart.value?.resize())
    observer.observe(el.value)
  } else {
    window.addEventListener('resize', handleWindowResize)
  }
})

function handleWindowResize() {
  chart.value?.resize()
}

watch(() => props.option, render, { deep: true })

watch(
  () => props.loading,
  (v) => {
    if (!chart.value) return
    if (v) chart.value.showLoading('default', { text: '', color: '#2a78d6', maskColor: 'rgba(255,255,255,0.6)' })
    else chart.value.hideLoading()
  }
)

onBeforeUnmount(() => {
  observer?.disconnect()
  window.removeEventListener('resize', handleWindowResize)
  chart.value?.dispose()
  chart.value = null
})

defineExpose({
  resize: () => chart.value?.resize(),
  getInstance: () => chart.value
})
</script>

<template>
  <div ref="el" class="echart" :style="{ height: heightStyle }" />
</template>

<style scoped>
.echart {
  width: 100%;
}
</style>
