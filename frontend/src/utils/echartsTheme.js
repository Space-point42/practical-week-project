/**
 * ECharts 统一主题参数。
 *
 * 图表外壳（网格线、坐标轴、文字）一律用中性墨色保持「后退」，
 * 只有承载数据的图元使用分类色 —— 这样读者的注意力自然落在数据上。
 * 分类色顺序与 styles/index.scss 中的 --series-N 一致，且已通过
 * 色觉缺陷可辨识性校验。
 */

export const SERIES = [
  '#2a78d6',
  '#eb6834',
  '#1baf7a',
  '#eda100',
  '#e87ba4',
  '#008300',
  '#4a3aa7',
  '#e34948'
]

export const INK = {
  primary: '#0b0b0b',
  secondary: '#52514e',
  muted: '#898781',
  gridline: '#e1e0d9',
  baseline: '#c3c2b7',
  surface: '#fcfcfb'
}

export const FONT_FAMILY =
  "system-ui, -apple-system, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif"

/** 给某个系列索引取色（超出 8 个应归并为「其它」，而非循环取色） */
export function seriesColor(index) {
  return SERIES[index % SERIES.length]
}

/** 分类轴的通用样式 */
export const categoryAxis = {
  type: 'category',
  axisLine: { lineStyle: { color: INK.baseline } },
  axisTick: { show: false },
  axisLabel: { color: INK.muted, fontSize: 12, fontFamily: FONT_FAMILY }
}

/** 数值轴的通用样式：只保留水平网格线 */
export const valueAxis = {
  type: 'value',
  axisLine: { show: false },
  axisTick: { show: false },
  axisLabel: { color: INK.muted, fontSize: 12, fontFamily: FONT_FAMILY },
  splitLine: { lineStyle: { color: INK.gridline, type: 'solid' } }
}

/** 统一的 tooltip 样式 */
export const tooltip = {
  backgroundColor: 'rgba(255,255,255,0.98)',
  borderColor: 'rgba(11,11,11,0.10)',
  borderWidth: 1,
  padding: [8, 12],
  textStyle: { color: INK.primary, fontSize: 13, fontFamily: FONT_FAMILY },
  extraCssText: 'box-shadow: 0 4px 16px rgba(11,11,11,0.10); border-radius: 8px;'
}

/** 统一的图例样式 */
export const legend = {
  textStyle: { color: INK.secondary, fontSize: 12, fontFamily: FONT_FAMILY },
  itemWidth: 10,
  itemHeight: 10,
  icon: 'roundRect'
}

/** 统一的网格留白 */
export const grid = {
  left: 8,
  right: 16,
  top: 28,
  bottom: 8,
  containLabel: true
}
