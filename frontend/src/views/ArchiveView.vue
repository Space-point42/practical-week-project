<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { knowledgeApi } from '@/api'
import { assetTypeMeta, formatDateTime, fromNow } from '@/utils/format'
import StatTile from '@/components/StatTile.vue'
import { statsApi } from '@/api'

const router = useRouter()

const loading = ref(false)
const items = ref([])
const total = ref(0)
const overview = ref({})
const query = reactive({ keyword: '', page: 1, size: 10 })

onMounted(async () => {
  const [ov] = await Promise.all([statsApi.overview().catch(() => ({})), load()])
  overview.value = ov || {}
})

async function load() {
  loading.value = true
  try {
    const res = await knowledgeApi.manage({
      status: 'ARCHIVED',
      keyword: query.keyword || undefined,
      page: query.page,
      size: query.size,
      sortBy: 'updatedAt',
      sortDir: 'desc'
    })
    items.value = res.list || []
    total.value = res.total || 0
  } finally {
    loading.value = false
  }
}

async function restore(item) {
  try {
    await ElMessageBox.confirm(
      `确定恢复《${item.title}》吗？\n\n恢复后条目会回到「草稿」状态而非直接发布 —— ` +
        '归档通常意味着内容可能已过时，需要重新确认后再发布。',
      '恢复归档',
      { type: 'info', confirmButtonText: '恢复', cancelButtonText: '取消' }
    )
    await knowledgeApi.unarchive(item.id)
    ElMessage.success('已恢复到草稿')
    load()
  } catch {
    /* 取消 */
  }
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h1 class="page-title">归档管理</h1>
        <p class="page-subtitle">
          归档不是删除：正文、附件与版本历史全部保留，只是移出常规检索与问答的召回范围。
          记录「为什么不再推荐这份资料」本身也是有价值的信息
        </p>
      </div>
    </div>

    <el-row :gutter="14" class="stat-row">
      <el-col :xs="12" :sm="6">
        <StatTile label="已归档" :value="overview.archivedCount || 0" icon="Box" accent="var(--series-7)" />
      </el-col>
      <el-col :xs="12" :sm="6">
        <StatTile label="已发布" :value="overview.publishedCount || 0" icon="CircleCheck" accent="var(--series-3)" />
      </el-col>
      <el-col :xs="12" :sm="6">
        <StatTile label="草稿" :value="overview.draftCount || 0" icon="EditPen" accent="var(--series-4)" />
      </el-col>
      <el-col :xs="12" :sm="6">
        <StatTile label="知识总量" :value="overview.knowledgeCount || 0" icon="Collection" accent="var(--series-1)" />
      </el-col>
    </el-row>

    <div class="card filter-bar">
      <el-input
        v-model="query.keyword"
        placeholder="按标题搜索归档内容"
        clearable
        class="kw-input"
        @keyup.enter="query.page = 1; load()"
        @clear="query.page = 1; load()"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-button type="primary" @click="query.page = 1; load()">查询</el-button>
    </div>

    <div v-loading="loading" class="card table-card">
      <el-table :data="items" stripe>
        <el-table-column label="标题" min-width="260">
          <template #default="{ row }">
            <div class="title-cell">
              <span class="t-title" @click="router.push(`/knowledge/${row.id}`)">{{ row.title }}</span>
              <div class="t-summary">{{ row.summary }}</div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="类型" width="106">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" disable-transitions>
              {{ assetTypeMeta(row.assetType).label }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="categoryName" label="分类" width="130" show-overflow-tooltip />

        <el-table-column label="归档时间" width="160">
          <template #default="{ row }">
            <div>{{ formatDateTime(row.archivedAt) }}</div>
            <div class="time-ago">{{ fromNow(row.archivedAt) }}</div>
          </template>
        </el-table-column>

        <el-table-column label="归档原因" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ 'no-reason': !row.archiveReason }">
              {{ row.archiveReason || '未填写' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="版本" width="76" align="center">
          <template #default="{ row }">
            <span class="tabular">v{{ row.versionNo }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button text size="small" :icon="'RefreshLeft'" @click="restore(row)">恢复</el-button>
            <el-button text size="small" :icon="'View'" @click="router.push(`/knowledge/${row.id}`)" />
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && !items.length" description="没有归档的内容" :image-size="80" />

      <div v-if="total > query.size" class="pagination-wrap">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          background
          @current-change="load"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.stat-row {
  margin-bottom: 14px;

  :deep(.el-col) {
    margin-bottom: 14px;
  }
}

.filter-bar {
  display: flex;
  gap: 10px;
  padding: 14px 16px;
  margin-bottom: 14px;
}

.kw-input {
  width: 320px;
}

.table-card {
  padding: 6px 8px 12px;
}

.title-cell {
  cursor: default;
}

.t-title {
  font-size: 13.5px;
  font-weight: 550;
  cursor: pointer;

  &:hover {
    color: var(--app-primary);
  }
}

.t-summary {
  font-size: 12px;
  color: var(--ink-muted);
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 460px;
}

.time-ago {
  font-size: 11.5px;
  color: var(--ink-muted);
}

.no-reason {
  color: var(--ink-muted);
}

.pagination-wrap {
  display: flex;
  justify-content: center;
  padding: 16px 0 6px;
}
</style>
