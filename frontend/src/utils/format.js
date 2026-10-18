import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/zh-cn'

dayjs.extend(relativeTime)
dayjs.locale('zh-cn')

/** 标准日期时间 */
export function formatDateTime(value) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—'
}

/** 仅日期 */
export function formatDate(value) {
  return value ? dayjs(value).format('YYYY-MM-DD') : '—'
}

/** 相对时间（3 天前），用于列表页的时间列 */
export function fromNow(value) {
  return value ? dayjs(value).fromNow() : '—'
}

/** 千分位 */
export function formatNumber(value) {
  if (value === null || value === undefined) return '0'
  return Number(value).toLocaleString('zh-CN')
}

/** 大数缩写：1200 → 1.2k */
export function abbreviateNumber(value) {
  const n = Number(value || 0)
  if (n < 1000) return String(n)
  if (n < 10000) return (n / 1000).toFixed(1).replace(/\.0$/, '') + 'k'
  return (n / 10000).toFixed(1).replace(/\.0$/, '') + 'w'
}

/** 文件大小 */
export function formatFileSize(bytes) {
  const b = Number(bytes || 0)
  if (b < 1024) return `${b} B`
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`
  if (b < 1024 * 1024 * 1024) return `${(b / 1024 / 1024).toFixed(1)} MB`
  return `${(b / 1024 / 1024 / 1024).toFixed(2)} GB`
}

/** 毫秒耗时，超过 1000ms 转成秒 */
export function formatDuration(ms) {
  const n = Number(ms || 0)
  return n < 1000 ? `${n} ms` : `${(n / 1000).toFixed(2)} s`
}

/** 百分比 */
export function formatPercent(value, digits = 1) {
  return `${Number(value || 0).toFixed(digits)}%`
}

/** 头像占位文字：取姓名首字 */
export function initialOf(name) {
  if (!name) return '?'
  return String(name).trim().charAt(0).toUpperCase()
}

/** 依据 ID 生成一个稳定的头像底色（同一用户始终同色） */
export function avatarColor(seed) {
  const palette = ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4', '#008300', '#4a3aa7']
  const s = String(seed ?? '')
  let hash = 0
  for (let i = 0; i < s.length; i += 1) {
    hash = (hash * 31 + s.charCodeAt(i)) >>> 0
  }
  return palette[hash % palette.length]
}

/** 资产类型 → 展示信息 */
export const ASSET_TYPES = [
  { value: 'DOCUMENT', label: '文档', icon: 'Document', color: '#2a78d6' },
  { value: 'REPORT', label: '技术报告', icon: 'DataAnalysis', color: '#eb6834' },
  { value: 'PAPER', label: '论文', icon: 'DocumentCopy', color: '#1baf7a' },
  { value: 'VIDEO', label: '视频', icon: 'VideoCamera', color: '#eda100' },
  { value: 'IMAGE', label: '图片', icon: 'Picture', color: '#e87ba4' },
  { value: 'CODE', label: '代码', icon: 'Cpu', color: '#008300' },
  { value: 'MINDMAP', label: '思维导图', icon: 'Share', color: '#4a3aa7' },
  { value: 'OTHER', label: '其他', icon: 'More', color: '#898781' }
]

export function assetTypeMeta(value) {
  return ASSET_TYPES.find((t) => t.value === value) || ASSET_TYPES[ASSET_TYPES.length - 1]
}

/** 内容状态 → 展示信息。状态色为保留色，始终配文字标签使用。 */
export const STATUS_META = {
  DRAFT: { label: '草稿', type: 'info', color: '#898781' },
  PENDING: { label: '待审核', type: 'warning', color: '#eda100' },
  PUBLISHED: { label: '已发布', type: 'success', color: '#0ca30c' },
  ARCHIVED: { label: '已归档', type: 'danger', color: '#898781' }
}

export function statusMeta(value) {
  return STATUS_META[value] || { label: value || '未知', type: 'info', color: '#898781' }
}

/** 版本快照来源 */
export const CHANGE_SOURCE_META = {
  AUTO: { label: '自动快照', type: 'info' },
  MANUAL: { label: '手工创建', type: 'primary' },
  PUBLISH: { label: '发布触发', type: 'success' },
  ROLLBACK: { label: '回滚生成', type: 'warning' }
}
