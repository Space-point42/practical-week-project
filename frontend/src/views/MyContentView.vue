<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { knowledgeApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { assetTypeMeta, statusMeta, formatDateTime, fromNow } from '@/utils/format'
import StatTile from '@/components/StatTile.vue'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const items = ref([])
const total = ref(0)
const query = reactive({ status: '', keyword: '', page: 1, size: 10 })

onMounted(load)

async function load() {
  loading.value = true
  try {
    const res = await knowledgeApi.mine({
      status: query.status || undefined,
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

/** 本地统计当前页之外的全局状态需要额外接口，这里按已加载数据给出可读概览 */
const summary = computed(() => {
  const list = items.value || []
  return {
    total,
    draft: list.filter((i) => i.status === 'DRAFT').length,
    pending: list.filter((i) => i.status === 'PENDING').length,
    published: list.filter((i) => i.status === 'PUBLISHED').length,
    needSnapshot: list.filter((i) => i.snapshotDue).length
  }
})

async function submitForReview(item) {
  try {
    await knowledgeApi.submit(item.id)
    ElMessage.success('已提交审核')
    load()
  } catch {
    /* 拦截器已提示 */
  }
}

async function publish(item) {
  try {
    const { value } = await ElMessageBox.prompt('请填写发布说明（会记入版本历史）', '发布确认', {
      confirmButtonText: '发布',
      cancelButtonText: '取消',
      inputValidator: (v) => (v && v.trim().length >= 2 ? true : '请至少填写 2 个字符')
    })
    await knowledgeApi.publish(item.id, value)
    ElMessage.success('已发布')
    load()
  } catch {
    /* 取消 */
  }
}

async function archive(item) {
  try {
    const { value } = await ElMessageBox.prompt('请填写归档原因', '归档确认', {
      confirmButtonText: '归档',
      cancelButtonText: '取消',
      inputPlaceholder: '例如：内容已被新版取代'
    })
    await knowledgeApi.archive(item.id, value)
    ElMessage.success('已归档')
    load()
  } catch {
    /* 取消 */
  }
}

async function remove(item) {
  try {
    await ElMessageBox.confirm(`确定删除《${item.title}》吗？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
    await knowledgeApi.remove(item.id)
    ElMessage.success('已删除')
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
        <h1 class="page-title">我的内容</h1>
        <p class="page-subtitle">
          {{ userStore.displayName }} 创建的知识条目，可按状态筛选并直接推进流程
        </p>
      </div>
      <el-button type="primary" :icon="'Plus'" @click="router.push('/editor')">新建知识</el-button>
    </div>

    <el-row :gutter="14" class="stat-row">
      <el-col :xs="12" :sm="6">
        <StatTile label="我的条目" :value="summary.total" icon="Files" accent="var(--series-1)" />
      </el-col>
      <el-col :xs="12" :sm="6">
        <StatTile label="草稿" :value="summary.draft" icon="EditPen" accent="var(--series-4)" hint="当前页统计" />
      </el-col>
      <el-col :xs="12" :sm="6">
        <StatTile label="待审核" :value="summary.pending" icon="Clock" accent="var(--series-2)" hint="当前页统计" />
      </el-col>
      <el-col :xs="12" :sm="6">
        <StatTile
          label="待建版本"
          :value="summary.needSnapshot"
          icon="Timer"
          accent="var(--series-5)"
          hint="已达到版本策略阈值"
        />
      </el-col>
    </el-row>

    <div class="card filter-bar">
      <el-input
        v-model="query.keyword"
        placeholder="搜索我的内容"
        clearable
        class="kw-input"
        @keyup.enter="query.page = 1; load()"
        @clear="query.page = 1; load()"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-select v-model="query.status" clearable placeholder="全部状态" class="status-select" @change="query.page = 1; load()">
        <el-option label="草稿" value="DRAFT" />
        <el-option label="待审核" value="PENDING" />
        <el-option label="已发布" value="PUBLISHED" />
        <el-option label="已归档" value="ARCHIVED" />
      </el-select>
    </div>

    <div v-loading="loading" class="card table-card">
      <el-table :data="items" stripe>
        <el-table-column label="标题" min-width="260">
          <template #default="{ row }">
            <div class="title-cell">
              <span class="t-title" @click="router.push(`/knowledge/${row.id}`)">{{ row.title }}</span>
              <div class="t-tags">
                <el-tag size="small" effect="plain" disable-transitions>
                  {{ assetTypeMeta(row.assetType).label }}
                </el-tag>
                <el-tag v-if="row.categoryName" size="small" type="info" effect="plain" disable-transitions>
                  {{ row.categoryName }}
                </el-tag>
                <el-tag v-if="row.snapshotDue" size="small" type="warning" effect="plain" disable-transitions>
                  建议建版
                </el-tag>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusMeta(row.status).type" size="small" effect="light" disable-transitions>
              {{ statusMeta(row.status).label }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="版本" width="110" align="center">
          <template #default="{ row }">
            <div class="tabular">v{{ row.versionNo }}</div>
            <div v-if="row.changeCount" class="sub-text">待存 {{ row.changeCount }} 次修改</div>
          </template>
        </el-table-column>

        <el-table-column label="浏览 / 点赞" width="110" align="center">
          <template #default="{ row }">
            <span class="tabular">{{ row.viewCount }} / {{ row.likeCount }}</span>
          </template>
        </el-table-column>

        <el-table-column label="更新时间" width="150">
          <template #default="{ row }">
            <div>{{ formatDateTime(row.updatedAt) }}</div>
            <div class="sub-text">{{ fromNow(row.updatedAt) }}</div>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="290" fixed="right">
          <template #default="{ row }">
            <el-button text size="small" :icon="'Edit'" @click="router.push(`/editor/${row.id}`)">编辑</el-button>
            <el-button text size="small" :icon="'Timer'" @click="router.push(`/knowledge/${row.id}/versions`)">
              版本
            </el-button>
            <el-button
              v-if="row.status === 'DRAFT'"
              text
              size="small"
              :icon="'Upload'"
              @click="submitForReview(row)"
            >
              提交审核
            </el-button>
            <el-button
              v-if="row.status === 'DRAFT' || row.status === 'PENDING'"
              text
              size="small"
              type="primary"
              :icon="'Promotion'"
              @click="publish(row)"
            >
              发布
            </el-button>
            <el-button
              v-if="row.status !== 'ARCHIVED'"
              text
              size="small"
              :icon="'Box'"
              @click="archive(row)"
            >
              归档
            </el-button>
            <el-button text size="small" type="danger" :icon="'Delete'" @click="remove(row)" />
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && !items.length" description="你还没有创建任何知识内容" :image-size="80">
        <el-button type="primary" @click="router.push('/editor')">创建第一条</el-button>
      </el-empty>

      <div v-if="total > query.size" class="pagination-wrap">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          layout="total, sizes, prev, pager, next"
          :page-sizes="[10, 20, 50]"
          background
          @current-change="load"
          @size-change="query.page = 1; load()"
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
  width: 300px;
}

.status-select {
  width: 150px;
}

.table-card {
  padding: 6px 8px 12px;
}

.t-title {
  font-size: 13.5px;
  font-weight: 550;
  cursor: pointer;

  &:hover {
    color: var(--app-primary);
  }
}

.t-tags {
  display: flex;
  gap: 5px;
  margin-top: 5px;
  flex-wrap: wrap;
}

.sub-text {
  font-size: 11.5px;
  color: var(--ink-muted);
  margin-top: 2px;
}

.pagination-wrap {
  display: flex;
  justify-content: center;
  padding: 16px 0 6px;
}
</style>
