<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { knowledgeApi } from '@/api'
import { useUserStore } from '@/stores/user'
import {
  assetTypeMeta,
  statusMeta,
  formatDateTime,
  formatNumber,
  abbreviateNumber,
  formatFileSize
} from '@/utils/format'
import MarkdownView from '@/components/MarkdownView.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const loadFailed = ref(false)
const item = ref(null)
const liked = ref(false)
const related = ref([])

const itemId = computed(() => route.params.id)

const assetMeta = computed(() => assetTypeMeta(item.value?.assetType))
const statusInfo = computed(() => statusMeta(item.value?.status))

/** 知识点名称与其 ID 平行存储，用下标配对还原「名称 + 可跳转 ID」 */
const concepts = computed(() => {
  const names = item.value?.conceptNames || []
  const ids = item.value?.conceptIds || []
  return names.map((name, index) => ({ name, id: ids[index] }))
})

const attachments = computed(() => item.value?.attachments || [])
const imageAtts = computed(() => attachments.value.filter((a) => a.mediaType === 'IMAGE'))
const videoAtts = computed(() => attachments.value.filter((a) => a.mediaType === 'VIDEO'))
const fileAtts = computed(
  () => attachments.value.filter((a) => a.mediaType !== 'IMAGE' && a.mediaType !== 'VIDEO')
)

const hasSource = computed(() => Boolean(item.value?.sourceUrl) || Boolean(item.value?.sourceDesc))

const canPublish = computed(() => ['DRAFT', 'PENDING'].includes(item.value?.status))
const canUnpublish = computed(() => item.value?.status === 'PUBLISHED')
const isArchived = computed(() => item.value?.status === 'ARCHIVED')

async function load() {
  loading.value = true
  loadFailed.value = false
  try {
    const data = await knowledgeApi.detail(itemId.value)
    item.value = data
    liked.value = Boolean(data.liked)
    loadRelated(data.conceptIds)
  } catch {
    // 错误提示已由 axios 拦截器统一弹出，这里只切换到失败态
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

async function loadRelated(conceptIds) {
  related.value = []
  if (!conceptIds?.length) return
  try {
    const list = await knowledgeApi.byConcepts(conceptIds, 6)
    related.value = (list || []).filter((r) => String(r.id) !== String(itemId.value))
  } catch {
    /* 相关阅读属于辅助信息，失败不影响正文阅读 */
  }
}

onMounted(load)
// 路由复用时组件不会重建（例如从「相关阅读」再进一条），必须监听 id 重新拉取
watch(itemId, load)

function goBack() {
  router.push('/knowledge')
}

function openConcept(concept) {
  if (!concept.id) return
  router.push({ path: '/ontology', query: { conceptId: concept.id } })
}

async function toggleLike() {
  try {
    const res = await knowledgeApi.like(itemId.value, !liked.value)
    liked.value = !liked.value
    item.value.likeCount = res.likeCount
  } catch {
    /* 拦截器已提示 */
  }
}

async function handlePublish() {
  try {
    const { value } = await ElMessageBox.prompt(
      '发布后将生成新版本快照，请填写本次发布的版本说明。',
      '发布确认',
      {
        confirmButtonText: '发布',
        cancelButtonText: '取消',
        inputPlaceholder: '例如：补充第三章的实验数据',
        inputValidator: (v) => (v && v.trim() ? true : '版本说明不能为空')
      }
    )
    await knowledgeApi.publish(itemId.value, value.trim())
    ElMessage.success('已发布')
    load()
  } catch {
    /* 用户取消；接口错误由拦截器提示 */
  }
}

async function handleUnpublish() {
  try {
    await ElMessageBox.confirm('撤回发布后该条目将回到未发布状态，确定继续吗？', '撤回发布', {
      type: 'warning',
      confirmButtonText: '撤回',
      cancelButtonText: '取消'
    })
    await knowledgeApi.unpublish(itemId.value)
    ElMessage.success('已撤回发布')
    load()
  } catch {
    /* 用户取消 */
  }
}

async function handleArchive() {
  try {
    const { value } = await ElMessageBox.prompt('请填写归档原因，便于后续追溯。', '归档确认', {
      confirmButtonText: '归档',
      cancelButtonText: '取消',
      inputPlaceholder: '例如：内容已过时，由新版本文档替代',
      inputValidator: (v) => (v && v.trim() ? true : '归档原因不能为空')
    })
    await knowledgeApi.archive(itemId.value, value.trim())
    ElMessage.success('已归档')
    load()
  } catch {
    /* 用户取消 */
  }
}

async function handleUnarchive() {
  try {
    await knowledgeApi.unarchive(itemId.value)
    ElMessage.success('已恢复')
    load()
  } catch {
    /* 拦截器已提示 */
  }
}

async function handleDelete() {
  try {
    await ElMessageBox.confirm(
      `确定删除《${item.value.title}》吗？删除后该条目将从知识库中移除，历史版本仍保留在数据库中，此操作不可撤销。`,
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
    await knowledgeApi.remove(itemId.value)
    ElMessage.success('已删除')
    router.push('/knowledge')
  } catch {
    /* 用户取消 */
  }
}

/** 模板表达式取不到全局 window，统一用方法包一层 */
function openInNewTab(url) {
  if (url) window.open(url, '_blank', 'noopener')
}

/** 附件文本抽取状态：成功时顺带给出正文字数，让检索命中的来源可解释 */
function extractLabel(att) {
  return att.extractStatus === 'SUCCESS'
    ? `已提取正文 · ${formatNumber(att.textLength || 0)} 字`
    : '未提取正文'
}
</script>

<template>
  <div class="page-container">
    <!-- ============ 加载失败 ============ -->
    <div v-if="loadFailed" class="card failed-card">
      <el-empty description="知识条目加载失败，可能已被删除或不存在">
        <el-button type="primary" :icon="'Back'" @click="goBack">返回知识库</el-button>
      </el-empty>
    </div>

    <!-- ============ 正文 ============ -->
    <div v-else v-loading="loading" class="detail-wrap">
      <template v-if="item">
        <!-- 头部 -->
        <header class="card detail-header">
          <div class="head-main">
            <div class="title-line">
              <el-tag :color="assetMeta.color" effect="dark" class="asset-chip">
                <el-icon><component :is="assetMeta.icon" /></el-icon>
                <span>{{ assetMeta.label }}</span>
              </el-tag>
              <el-tag :type="statusInfo.type" effect="plain">{{ statusInfo.label }}</el-tag>
              <el-tag v-if="item.snapshotDue" type="warning" effect="plain" :icon="'Timer'">
                版本待更新
              </el-tag>
            </div>
            <h1 class="detail-title">{{ item.title }}</h1>
            <p v-if="item.summary" class="detail-summary">{{ item.summary }}</p>

            <div class="meta-row">
              <span class="meta-item"><el-icon><Folder /></el-icon>{{ item.categoryName || '未分类' }}</span>
              <span class="meta-item"><el-icon><User /></el-icon>{{ item.authorName || '未知' }}</span>
              <span class="meta-item"><el-icon><View /></el-icon>{{ abbreviateNumber(item.viewCount) }} 浏览</span>
              <span class="meta-item"><el-icon><Star /></el-icon>{{ abbreviateNumber(item.likeCount) }} 点赞</span>
              <span class="meta-item"><el-icon><Download /></el-icon>{{ abbreviateNumber(item.downloadCount) }} 下载</span>
              <span class="meta-item"><el-icon><Refresh /></el-icon>{{ formatDateTime(item.updatedAt) }}</span>
              <span class="meta-item">
                <el-icon><Files /></el-icon>
                <el-tag size="small" effect="plain">v{{ item.versionNo }}</el-tag>
              </span>
            </div>
          </div>

          <div class="action-bar">
            <el-button
              :type="liked ? 'primary' : 'default'"
              :icon="liked ? 'StarFilled' : 'Star'"
              @click="toggleLike"
            >
              {{ abbreviateNumber(item.likeCount) }}
            </el-button>
            <el-button v-if="userStore.canEdit" :icon="'Edit'" @click="router.push(`/editor/${item.id}`)">
              编辑
            </el-button>
            <el-button :icon="'Clock'" @click="router.push(`/knowledge/${item.id}/versions`)">
              版本历史
            </el-button>
            <el-button
              v-if="userStore.canEdit && canPublish"
              type="success"
              :icon="'Upload'"
              @click="handlePublish"
            >
              发布
            </el-button>
            <el-button
              v-if="userStore.canEdit && canUnpublish"
              :icon="'Download'"
              @click="handleUnpublish"
            >
              撤回发布
            </el-button>
            <el-button
              v-if="userStore.canEdit && !isArchived"
              :icon="'Box'"
              @click="handleArchive"
            >
              归档
            </el-button>
            <el-button
              v-if="userStore.canEdit && isArchived"
              :icon="'RefreshLeft'"
              @click="handleUnarchive"
            >
              恢复
            </el-button>
            <el-button v-if="userStore.canEdit" type="danger" plain :icon="'Delete'" @click="handleDelete">
              删除
            </el-button>
          </div>
        </header>

        <!-- 主体：正文 + 侧栏 -->
        <div class="detail-layout">
          <main class="card main-col">
            <article class="article-body">
              <MarkdownView :content="item.content" />
            </article>

            <!-- 附件区 -->
            <section v-if="attachments.length" class="attach-section">
              <h3 class="section-title">
                附件
                <span class="section-hint">共 {{ attachments.length }} 个</span>
              </h3>

              <!-- 图片：缩略图网格 -->
              <div v-if="imageAtts.length" class="image-grid">
                <figure
                  v-for="att in imageAtts"
                  :key="att.id"
                  class="image-cell"
                  title="点击在新标签页查看原图"
                  @click="openInNewTab(att.url)"
                >
                  <img :src="att.url" :alt="att.fileName" loading="lazy" />
                  <figcaption>{{ att.fileName }}</figcaption>
                </figure>
              </div>

              <!-- 视频：内嵌播放器 -->
              <div v-if="videoAtts.length" class="video-list">
                <div v-for="att in videoAtts" :key="att.id" class="video-item">
                  <video controls :src="att.url" :poster="att.poster || undefined" preload="metadata" />
                  <div class="video-name">{{ att.fileName }}</div>
                </div>
              </div>

              <!-- 其他文件：列表行 -->
              <ul v-if="fileAtts.length" class="file-list">
                <li v-for="att in fileAtts" :key="att.id" class="file-row">
                  <el-icon class="file-icon"><Document /></el-icon>
                  <div class="file-info">
                    <div class="file-name" :title="att.fileName">{{ att.fileName }}</div>
                    <div class="file-sub tabular">{{ att.readableSize || formatFileSize(att.fileSize) }}</div>
                  </div>
                  <el-tag
                    size="small"
                    effect="plain"
                    :type="att.extractStatus === 'SUCCESS' ? 'success' : 'info'"
                  >
                    {{ extractLabel(att) }}
                  </el-tag>
                  <el-button
                    text
                    type="primary"
                    :icon="'Download'"
                    @click="openInNewTab(att.downloadUrl)"
                  >
                    下载
                  </el-button>
                </li>
              </ul>
            </section>

            <!-- 来源信息 -->
            <section v-if="hasSource" class="source-section">
              <h3 class="section-title">来源信息</h3>
              <div class="source-box">
                <p v-if="item.sourceDesc" class="source-desc">{{ item.sourceDesc }}</p>
                <a
                  v-if="item.sourceUrl"
                  :href="item.sourceUrl"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="source-link"
                >
                  <el-icon><Link /></el-icon>{{ item.sourceUrl }}
                </a>
              </div>
            </section>
          </main>

          <!-- 侧栏 -->
          <aside class="side-col">
            <div class="card side-card">
              <h3 class="section-title">关联知识点</h3>
              <div v-if="concepts.length" class="concept-tags">
                <el-tag
                  v-for="c in concepts"
                  :key="c.name"
                  class="concept-tag"
                  effect="plain"
                  @click="openConcept(c)"
                >
                  {{ c.name }}
                </el-tag>
              </div>
              <div v-else class="empty-hint">暂无关联知识点</div>
            </div>

            <div class="card side-card">
              <h3 class="section-title">相关阅读</h3>
              <ul v-if="related.length" class="related-list">
                <li v-for="r in related" :key="r.id">
                  <router-link :to="`/knowledge/${r.id}`" class="related-link">
                    {{ r.title }}
                  </router-link>
                  <span class="related-meta">{{ assetTypeMeta(r.assetType).label }}</span>
                </li>
              </ul>
              <div v-else class="empty-hint">暂无相关条目</div>
            </div>

            <el-alert
              v-if="item.snapshotDue"
              class="version-alert"
              type="warning"
              :closable="false"
              show-icon
              title="该条目已触发版本策略"
            >
              <p class="alert-text">
                自 {{ formatDateTime(item.lastSnapshotAt) }} 上次快照以来已累计
                <b>{{ item.changeCount }}</b> 次修改，达到版本策略阈值，建议创建新版本。
              </p>
              <el-button
                size="small"
                type="warning"
                plain
                :icon="'Clock'"
                @click="router.push(`/knowledge/${item.id}/versions`)"
              >
                前往版本历史
              </el-button>
            </el-alert>
          </aside>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.detail-wrap {
  min-height: 320px;
}

.failed-card {
  padding: 40px 24px;
}

/* ---------------- 头部 ---------------- */
.detail-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
  padding: 20px 22px;
  margin-bottom: 16px;
}

.head-main {
  min-width: 0;
  flex: 1;
}

.title-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

.asset-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: none;
  color: #fff;
}

.detail-title {
  font-size: 22px;
  font-weight: 650;
  line-height: 1.4;
  letter-spacing: -0.01em;
  margin: 0 0 8px;
}

.detail-summary {
  font-size: 13px;
  color: var(--ink-secondary);
  line-height: 1.7;
  margin: 0 0 12px;
}

.meta-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 16px;
  font-size: 12.5px;
  color: var(--ink-muted);
}

.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.action-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  max-width: 420px;
  justify-content: flex-end;
}

/* ---------------- 两栏布局 ---------------- */
.detail-layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.main-col {
  flex: 1;
  min-width: 0;
  padding: 24px 28px;
}

.side-col {
  width: 280px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.side-card {
  padding: 16px 18px;
}

.article-body {
  min-height: 120px;
}

/* ---------------- 正文下方的分节 ---------------- */
/* 附件与来源信息是两类不同的内容，用各自的类名而不是共用一个，
   否则样式耦合在一起，后续调整其中一处会意外影响另一处 */
.attach-section,
.source-section {
  margin-top: 30px;
  padding-top: 22px;
  border-top: 1px solid var(--hairline);
}

.image-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.image-cell {
  margin: 0;
  border: 1px solid var(--hairline);
  border-radius: var(--app-radius-sm);
  overflow: hidden;
  cursor: pointer;
  background: var(--page-plane);
}

.image-cell img {
  display: block;
  width: 100%;
  height: 120px;
  object-fit: cover;
}

.image-cell figcaption {
  font-size: 11.5px;
  color: var(--ink-muted);
  padding: 6px 8px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.video-list {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-bottom: 16px;
}

.video-item video {
  width: 340px;
  max-width: 100%;
  border-radius: var(--app-radius-sm);
  background: #000;
}

.video-name {
  font-size: 12px;
  color: var(--ink-muted);
  margin-top: 6px;
}

.file-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.file-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 4px;
  border-bottom: 1px solid var(--hairline);
}

.file-row:last-child {
  border-bottom: none;
}

.file-icon {
  font-size: 20px;
  color: var(--app-primary);
  flex-shrink: 0;
}

.file-info {
  flex: 1;
  min-width: 0;
}

.file-name {
  font-size: 13.5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.file-sub {
  font-size: 12px;
  color: var(--ink-muted);
  margin-top: 2px;
}

/* ---------------- 来源 ---------------- */
.source-box {
  font-size: 13px;
  color: var(--ink-secondary);
  line-height: 1.8;
}

.source-desc {
  margin: 0 0 6px;
}

.source-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  word-break: break-all;
}

/* ---------------- 侧栏内容 ---------------- */
.concept-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.concept-tag {
  cursor: pointer;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
}

.related-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.related-list li {
  padding: 7px 0;
  border-bottom: 1px dashed var(--hairline);
  line-height: 1.5;
}

.related-list li:last-child {
  border-bottom: none;
}

.related-link {
  font-size: 13.5px;
  display: block;
}

.related-meta {
  font-size: 11.5px;
  color: var(--ink-muted);
}

.version-alert .alert-text {
  font-size: 12.5px;
  line-height: 1.7;
  margin: 0 0 10px;
}

/* ---------------- 响应式 ---------------- */
@media (max-width: 1000px) {
  .detail-layout {
    flex-direction: column;
  }

  .side-col {
    width: 100%;
  }

  .action-bar {
    max-width: none;
    justify-content: flex-start;
  }
}
</style>
