<script setup>
/**
 * 知识条目卡片。
 *
 * 既用于列表页的浏览，也用于检索结果 —— 检索时额外展示
 * 命中高亮标题与摘要片段，让用户一眼看出「为什么这条被召回」。
 */
import { computed } from 'vue'
import { assetTypeMeta, formatDateTime, fromNow, abbreviateNumber } from '@/utils/format'

const props = defineProps({
  item: { type: Object, required: true },
  /** 检索结果模式：显示高亮标题、摘要片段与得分 */
  searchMode: { type: Boolean, default: false },
  /** 管理视图：显示状态与版本信息 */
  manageMode: { type: Boolean, default: false }
})

const emit = defineEmits(['click'])

const typeMeta = computed(() => assetTypeMeta(props.item.assetType))

const title = computed(() =>
  props.searchMode && props.item.titleHighlight ? props.item.titleHighlight : props.item.title
)

const concepts = computed(() => (props.item.conceptNames || []).slice(0, 4))

const tags = computed(() => {
  const t = props.item.tags || []
  return t.slice(0, 4).map((x) => (typeof x === 'string' ? { name: x } : x))
})

const statusText = computed(() => {
  const map = { DRAFT: '草稿', PENDING: '待审核', PUBLISHED: '已发布', ARCHIVED: '已归档' }
  return map[props.item.status] || props.item.status
})

const statusType = computed(() => {
  const map = { DRAFT: 'info', PENDING: 'warning', PUBLISHED: 'success', ARCHIVED: 'info' }
  return map[props.item.status] || 'info'
})
</script>

<template>
  <div class="knowledge-card" @click="emit('click', item)">
    <div class="kc-head">
      <el-tag :color="typeMeta.color" effect="dark" size="small" class="type-chip" disable-transitions>
        <el-icon :size="11" style="margin-right: 3px; vertical-align: -1px">
          <component :is="typeMeta.icon" />
        </el-icon>
        {{ typeMeta.label }}
      </el-tag>
      <el-tag v-if="manageMode" :type="statusType" size="small" effect="plain" disable-transitions>
        {{ statusText }}
      </el-tag>
      <!-- 得分构成用 title 提示：答辩时鼠标悬停即可解释排序依据，不占用卡片空间 -->
      <span
        v-if="searchMode && item.score !== undefined"
        class="score-chip tabular"
        :title="item.scoreDetail || ''"
      >
        相关度 {{ item.score }}
      </span>
    </div>

    <h3 class="kc-title" v-html="title" />

    <p v-if="searchMode && item.snippet" class="snippet" v-html="item.snippet" />
    <p v-else class="kc-summary">{{ item.summary }}</p>

    <div v-if="concepts.length || tags.length" class="kc-tags">
      <el-tag
        v-for="c in concepts"
        :key="`c-${c}`"
        size="small"
        effect="plain"
        type="primary"
        disable-transitions
      >
        {{ c }}
      </el-tag>
      <el-tag v-for="t in tags" :key="`t-${t.name}`" size="small" type="info" effect="plain" disable-transitions>
        #{{ t.name }}
      </el-tag>
    </div>

    <div class="kc-meta">
      <span v-if="item.categoryName">
        <el-icon :size="12"><Folder /></el-icon> {{ item.categoryName }}
      </span>
      <span v-if="item.authorName">
        <el-icon :size="12"><User /></el-icon> {{ item.authorName }}
      </span>
      <span>
        <el-icon :size="12"><View /></el-icon> {{ abbreviateNumber(item.viewCount) }}
      </span>
      <span v-if="manageMode && item.versionNo">
        <el-icon :size="12"><Timer /></el-icon> v{{ item.versionNo }}
      </span>
      <span class="kc-time" :title="formatDateTime(item.updatedAt)">
        {{ fromNow(item.updatedAt) }}
      </span>
    </div>

    <el-alert
      v-if="manageMode && item.snapshotDue"
      type="warning"
      :closable="false"
      show-icon
      class="snapshot-tip"
      title="已达到版本策略阈值，建议创建新版本"
    />
  </div>
</template>

<style scoped>
.kc-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.type-chip {
  border: none;
  color: #fff;
  font-weight: 500;
}

.score-chip {
  margin-left: auto;
  font-size: 12px;
  color: var(--ink-muted);
}

.kc-meta span {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.kc-time {
  margin-left: auto;
}

.snapshot-tip {
  margin-top: 10px;
  padding: 6px 10px;
}

:deep(.snapshot-tip .el-alert__title) {
  font-size: 12px;
}

/* 检索高亮：用背景色标记，保证在深色文字上仍有足够对比度 */
:deep(mark) {
  background: #fff3c4;
  color: inherit;
  padding: 0 2px;
  border-radius: 2px;
}
</style>
