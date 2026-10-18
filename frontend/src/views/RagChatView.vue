<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ragApi, categoryApi } from '@/api'
import { renderMarkdown } from '@/utils/markdown'
import { formatDuration, formatDateTime, fromNow } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const sessions = ref([])
const messages = ref([])
const currentSessionId = ref(null)
const question = ref('')
const asking = ref(false)
const loadingSessions = ref(false)
const categoryTree = ref([])
const categoryId = ref(null)
const ontologyExpansion = ref(true)
const systemInfo = ref({})

const scrollRef = ref(null)
const inputRef = ref(null)

const SUGGESTED_QUESTIONS = [
  '需求分析和需求获取有什么区别？',
  '关系代数和 SQL 之间是什么关系？',
  '版本管理为什么需要按天数或修改次数来建版本？',
  '全文检索和向量检索各有什么优缺点？',
  '数据库三阶段设计分别做什么？'
]

/** 当前对话轮次（一问一答算一轮），用于展示上下文规模 */
const rounds = computed(() => messages.value.filter((m) => m.role === 'user').length)

onMounted(async () => {
  loadingSessions.value = true
  try {
    const [list, tree, info] = await Promise.all([
      ragApi.sessions(),
      categoryApi.tree(),
      ragApi.indexStatus().catch(() => ({}))
    ])
    sessions.value = list || []
    categoryTree.value = tree || []
    systemInfo.value = info || {}

    // 支持从检索页带问题跳转过来
    if (route.query.q) {
      question.value = String(route.query.q)
      await nextTick()
      handleAsk()
    } else if (sessions.value.length) {
      openSession(sessions.value[0].id)
    }
  } finally {
    loadingSessions.value = false
  }
})

async function loadSessions() {
  try {
    sessions.value = (await ragApi.sessions()) || []
  } catch {
    /* 静默 */
  }
}

async function openSession(id) {
  currentSessionId.value = id
  try {
    const list = await ragApi.messages(id)
    messages.value = (list || []).map((m) => ({
      ...m,
      citations: m.citations || [],
      meta: m.retrievalMeta ? safeParse(m.retrievalMeta) : null,
      showCitations: true,
      showTrace: false
    }))
    scrollToBottom()
  } catch {
    messages.value = []
  }
}

function safeParse(text) {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

function newSession() {
  currentSessionId.value = null
  messages.value = []
  question.value = ''
  nextTick(() => inputRef.value?.focus())
}

async function handleAsk() {
  const q = question.value.trim()
  if (!q || asking.value) return

  messages.value.push({ role: 'user', content: q, createdAt: new Date().toISOString() })
  question.value = ''
  asking.value = true
  scrollToBottom()

  try {
    const data = await ragApi.ask({
      question: q,
      sessionId: currentSessionId.value || undefined,
      categoryId: categoryId.value || undefined,
      ontologyExpansion: ontologyExpansion.value,
      topK: 6
    })

    currentSessionId.value = data.sessionId
    messages.value.push({
      id: data.messageId,
      role: 'assistant',
      content: data.answer,
      citations: data.citations || [],
      meta: data.retrieval,
      model: data.model,
      degraded: data.degraded,
      notice: data.notice,
      latencyMs: data.latencyMs,
      createdAt: data.createdAt,
      showCitations: true,
      showTrace: false
    })
    // 新会话产生后刷新左侧列表，让标题及时出现
    loadSessions()
  } catch {
    messages.value.push({
      role: 'assistant',
      content: '提问失败，请稍后重试。若持续失败，请到「系统管理 → 检索索引」确认索引状态。',
      citations: [],
      error: true
    })
  } finally {
    asking.value = false
    scrollToBottom()
  }
}

async function removeSession(session) {
  try {
    await ElMessageBox.confirm(`确定删除会话「${session.title}」及其全部消息吗？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
    await ragApi.removeSession(session.id)
    ElMessage.success('已删除')
    if (currentSessionId.value === session.id) newSession()
    loadSessions()
  } catch {
    /* 取消 */
  }
}

function scrollToBottom() {
  nextTick(() => {
    const el = scrollRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

/** 引用条目的相关度展示：同时给出融合得分与两条通道的排名 */
function rankText(citation) {
  const r = citation.ranks || {}
  const parts = []
  if (r.vectorRank) parts.push(`向量 #${r.vectorRank}`)
  if (r.keywordRank) parts.push(`关键字 #${r.keywordRank}`)
  return parts.length ? parts.join(' · ') : '单通道命中'
}

function openCitation(citation) {
  router.push(`/knowledge/${citation.itemId}`)
}

watch(asking, scrollToBottom)
</script>

<template>
  <div class="rag-page">
    <!-- ============ 左侧会话列表 ============ -->
    <aside class="session-panel">
      <div class="session-head">
        <span class="session-title">问答会话</span>
        <el-button size="small" type="primary" :icon="'Plus'" @click="newSession">新会话</el-button>
      </div>

      <div v-loading="loadingSessions" class="session-list">
        <div
          v-for="s in sessions"
          :key="s.id"
          class="session-item"
          :class="{ active: currentSessionId === s.id }"
          @click="openSession(s.id)"
        >
          <div class="si-main">
            <div class="si-title">{{ s.title }}</div>
            <div class="si-meta">
              <span>{{ s.messageCount }} 条消息</span>
              <span>· {{ fromNow(s.updatedAt) }}</span>
            </div>
          </div>
          <el-icon class="si-del" :size="14" @click.stop="removeSession(s)"><Delete /></el-icon>
        </div>

        <div v-if="!sessions.length && !loadingSessions" class="empty-hint">
          还没有问答记录<br />在右侧输入问题开始
        </div>
      </div>

      <div v-if="systemInfo.embeddingModel" class="session-foot">
        <el-tooltip :content="systemInfo.description" placement="top">
          <div class="model-line">
            <span class="model-dot" :class="{ online: systemInfo.onlineEmbedding }" />
            <span>{{ systemInfo.onlineEmbedding ? '在线语义向量' : '本地哈希向量' }}</span>
          </div>
        </el-tooltip>
        <el-tooltip :content="systemInfo.chatDescription" placement="top">
          <div class="model-line">
            <span class="model-dot" :class="{ online: systemInfo.chatAvailable }" />
            <span>{{ systemInfo.chatAvailable ? '在线大模型生成' : '离线抽取式回答' }}</span>
          </div>
        </el-tooltip>
      </div>
    </aside>

    <!-- ============ 主对话区 ============ -->
    <section class="chat-area">
      <header class="chat-head">
        <div>
          <h1 class="page-title">智能问答</h1>
          <p class="page-subtitle">
            基于知识库的检索增强生成 —— 回答附带引用来源，可逐条核对原文
            <span v-if="rounds"> · 当前会话 {{ rounds }} 轮</span>
          </p>
        </div>
        <div class="chat-options">
          <el-tree-select
            v-model="categoryId"
            :data="categoryTree"
            node-key="id"
            :props="{ label: 'name', children: 'children' }"
            check-strictly
            clearable
            placeholder="全库检索"
            size="small"
            class="scope-select"
          />
          <el-tooltip placement="bottom" content="开启后会把问题中的知识点沿本体关系扩展，召回相邻知识点的资料">
            <el-checkbox v-model="ontologyExpansion" size="small">本体扩展</el-checkbox>
          </el-tooltip>
        </div>
      </header>

      <!-- ---- 对话流 ---- -->
      <div ref="scrollRef" class="chat-scroll">
        <!-- 空状态：给出引导与示例问题 -->
        <div v-if="!messages.length" class="chat-empty">
          <div class="ce-icon">
            <el-icon :size="30"><ChatDotRound /></el-icon>
          </div>
          <h2>向知识库提问</h2>
          <p class="ce-desc">
            系统会先检索知识库中的相关资料，再基于资料生成回答。
            回答中的每个结论都标注了来源编号，可以直接跳转原文核对。
          </p>

          <div class="ce-pipeline">
            <span class="pipe-step">本体查询扩展</span>
            <el-icon :size="12"><Right /></el-icon>
            <span class="pipe-step">向量 + 全文混合检索</span>
            <el-icon :size="12"><Right /></el-icon>
            <span class="pipe-step">RRF 融合排序</span>
            <el-icon :size="12"><Right /></el-icon>
            <span class="pipe-step">基于资料生成</span>
          </div>

          <div class="ce-questions">
            <div
              v-for="q in SUGGESTED_QUESTIONS"
              :key="q"
              class="ce-question"
              @click="question = q; handleAsk()"
            >
              <el-icon :size="13"><Promotion /></el-icon>
              {{ q }}
            </div>
          </div>
        </div>

        <!-- 消息 -->
        <div
          v-for="(m, idx) in messages"
          :key="m.id || idx"
          class="message"
          :class="m.role"
        >
          <div class="msg-avatar">
            <div v-if="m.role === 'user'" class="avatar user">我</div>
            <div v-else class="avatar ai"><el-icon :size="16"><MagicStick /></el-icon></div>
          </div>

          <div class="msg-body">
            <!-- 用户消息 -->
            <div v-if="m.role === 'user'" class="bubble user-bubble">{{ m.content }}</div>

            <!-- 助手消息 -->
            <template v-else>
              <div class="bubble ai-bubble">
                <el-alert
                  v-if="m.degraded"
                  type="info"
                  :closable="false"
                  show-icon
                  class="degrade-note"
                  :title="`当前为离线抽取式回答（${m.notice || '未配置生成模型'}）`"
                  description="回答内容直接抽取自知识库原文，未经过大模型改写，因此每一句都可核对；配置 kms.rag.chat 的 API Key 后可获得归纳式回答。"
                />

                <div class="answer markdown-body" v-html="renderMarkdown(m.content)" />

                <div class="msg-foot">
                  <span v-if="m.model" class="foot-item">
                    <el-icon :size="12"><Cpu /></el-icon> {{ m.model }}
                  </span>
                  <span v-if="m.latencyMs" class="foot-item tabular">
                    {{ formatDuration(m.latencyMs) }}
                  </span>
                  <span v-if="m.createdAt" class="foot-item">{{ formatDateTime(m.createdAt) }}</span>

                  <span
                    v-if="m.citations && m.citations.length"
                    class="foot-action"
                    @click="m.showCitations = !m.showCitations"
                  >
                    <el-icon :size="12"><Document /></el-icon>
                    {{ m.citations.length }} 条引用 {{ m.showCitations ? '▲' : '▼' }}
                  </span>
                  <span v-if="m.meta" class="foot-action" @click="m.showTrace = !m.showTrace">
                    <el-icon :size="12"><DataAnalysis /></el-icon>
                    检索过程 {{ m.showTrace ? '▲' : '▼' }}
                  </span>
                </div>
              </div>

              <!-- 引用列表 -->
              <div v-if="m.showCitations && m.citations && m.citations.length" class="citations">
                <div
                  v-for="c in m.citations"
                  :key="c.chunkId"
                  class="citation-item"
                  @click="openCitation(c)"
                >
                  <div class="ci-head">
                    <span class="ci-index">来源 {{ c.index }}</span>
                    <span class="ci-title">{{ c.itemTitle }}</span>
                    <span class="ci-score tabular">相关度 {{ c.score }}</span>
                  </div>
                  <div class="ci-snippet">{{ c.snippet }}</div>
                  <div class="ci-ranks">
                    <el-tag size="small" type="info" effect="plain">{{ rankText(c) }}</el-tag>
                    <span class="ci-hint">点击查看原文</span>
                  </div>
                </div>
              </div>

              <!-- 检索过程：答辩演示时展开，说明系统「凭什么」给出这个答案 -->
              <div v-if="m.showTrace && m.meta" class="trace-panel">
                <div class="trace-grid">
                  <div class="trace-cell">
                    <div class="tc-label">向量通道</div>
                    <div class="tc-value tabular">{{ m.meta.vectorCandidates }} 条候选</div>
                  </div>
                  <div class="trace-cell">
                    <div class="tc-label">关键字通道</div>
                    <div class="tc-value tabular">{{ m.meta.keywordCandidates }} 条候选</div>
                  </div>
                  <div class="trace-cell">
                    <div class="tc-label">融合后</div>
                    <div class="tc-value tabular">{{ m.meta.fusedCandidates }} 条</div>
                  </div>
                  <div class="trace-cell">
                    <div class="tc-label">进入上下文</div>
                    <div class="tc-value tabular">{{ m.meta.usedChunks }} 个分块</div>
                  </div>
                  <div class="trace-cell">
                    <div class="tc-label">检索 / 生成耗时</div>
                    <div class="tc-value tabular">
                      {{ formatDuration(m.meta.retrievalCostMs) }} / {{ formatDuration(m.meta.generationCostMs) }}
                    </div>
                  </div>
                  <div class="trace-cell">
                    <div class="tc-label">上下文规模</div>
                    <div class="tc-value tabular">{{ m.meta.contextChars }} 字符</div>
                  </div>
                </div>

                <div v-if="m.meta.expandedConcepts && m.meta.expandedConcepts.length" class="trace-section">
                  <div class="ts-label">本体查询扩展出的知识点</div>
                  <div class="ts-tags">
                    <el-tag
                      v-for="name in m.meta.expandedConcepts"
                      :key="name"
                      size="small"
                      effect="plain"
                      type="primary"
                    >
                      {{ name }}
                    </el-tag>
                  </div>
                  <div class="ts-note">
                    原始问题先用实体链接识别知识点，再沿本体的先修/组成/相关关系补充相邻概念，
                    这些词会一并参与检索，使相邻知识点的资料也能被召回。
                  </div>
                </div>

                <div v-if="m.meta.chunkScores && m.meta.chunkScores.length" class="trace-section">
                  <div class="ts-label">分块得分明细</div>
                  <el-table :data="m.meta.chunkScores" size="small" stripe>
                    <el-table-column prop="index" label="#" width="46" align="center" />
                    <el-table-column prop="itemTitle" label="来源条目" show-overflow-tooltip />
                    <el-table-column prop="vectorRank" label="向量排名" width="90" align="center">
                      <template #default="{ row }">{{ row.vectorRank || '—' }}</template>
                    </el-table-column>
                    <el-table-column prop="keywordRank" label="关键字排名" width="100" align="center">
                      <template #default="{ row }">{{ row.keywordRank || '—' }}</template>
                    </el-table-column>
                    <el-table-column prop="fusedScore" label="融合得分" width="94" align="right">
                      <template #default="{ row }">
                        <span class="tabular">{{ row.fusedScore }}</span>
                      </template>
                    </el-table-column>
                  </el-table>
                </div>
              </div>
            </template>
          </div>
        </div>

        <!-- 生成中 -->
        <div v-if="asking" class="message assistant">
          <div class="msg-avatar"><div class="avatar ai"><el-icon :size="16"><MagicStick /></el-icon></div></div>
          <div class="msg-body">
            <div class="bubble ai-bubble typing">
              <span class="dot" /><span class="dot" /><span class="dot" />
              <span class="typing-text">正在检索知识库并生成回答……</span>
            </div>
          </div>
        </div>
      </div>

      <!-- ---- 输入区 ---- -->
      <footer class="chat-input">
        <el-input
          ref="inputRef"
          v-model="question"
          type="textarea"
          :rows="2"
          resize="none"
          :disabled="asking"
          placeholder="输入你的问题，Enter 发送，Shift + Enter 换行"
          @keydown.enter.exact.prevent="handleAsk"
        />
        <el-button
          type="primary"
          :icon="'Promotion'"
          :loading="asking"
          :disabled="!question.trim()"
          class="send-btn"
          @click="handleAsk"
        >
          发送
        </el-button>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.rag-page {
  display: flex;
  height: calc(100vh - var(--header-height));
  overflow: hidden;
}

/* ---------------- 会话列表 ---------------- */
.session-panel {
  width: 244px;
  flex-shrink: 0;
  background: #fff;
  border-right: 1px solid var(--hairline);
  display: flex;
  flex-direction: column;
}

.session-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 14px 10px;
  border-bottom: 1px solid var(--hairline);
}

.session-title {
  font-size: 13px;
  font-weight: 600;
}

.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.session-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 9px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.14s ease;

  &:hover {
    background: var(--page-plane);
  }

  &.active {
    background: var(--app-primary-soft);

    .si-title {
      color: var(--app-primary);
      font-weight: 600;
    }
  }
}

.si-main {
  min-width: 0;
  flex: 1;
}

.si-title {
  font-size: 13px;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.si-meta {
  font-size: 11px;
  color: var(--ink-muted);
  margin-top: 3px;
  display: flex;
  gap: 4px;
}

.si-del {
  color: var(--ink-muted);
  opacity: 0;
  transition: opacity 0.14s ease;

  &:hover {
    color: var(--status-critical);
  }
}

.session-item:hover .si-del {
  opacity: 1;
}

.session-foot {
  border-top: 1px solid var(--hairline);
  padding: 10px 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.model-line {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: var(--ink-muted);
  cursor: default;
}

.model-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ink-muted);

  &.online {
    background: var(--status-good);
  }
}

/* ---------------- 对话区 ---------------- */
.chat-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--page-plane);
}

.chat-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 24px;
  background: #fff;
  border-bottom: 1px solid var(--hairline);
  flex-wrap: wrap;
}

.chat-options {
  display: flex;
  align-items: center;
  gap: 14px;
}

.scope-select {
  width: 150px;
}

.chat-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 22px 24px;
  scroll-behavior: smooth;
}

/* ---------------- 空状态 ---------------- */
.chat-empty {
  max-width: 700px;
  margin: 5vh auto 0;
  text-align: center;
}

.ce-icon {
  width: 62px;
  height: 62px;
  margin: 0 auto 18px;
  border-radius: 18px;
  background: linear-gradient(135deg, #2a78d6, #4a3aa7);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}

.chat-empty h2 {
  font-size: 20px;
  font-weight: 650;
  margin: 0 0 10px;
}

.ce-desc {
  font-size: 13.5px;
  line-height: 1.85;
  color: var(--ink-secondary);
  margin: 0 0 22px;
}

.ce-pipeline {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 26px;
  color: var(--ink-muted);
}

.pipe-step {
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid var(--hairline);
  color: var(--ink-secondary);
}

.ce-questions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  text-align: left;
}

.ce-question {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 11px 14px;
  background: #fff;
  border: 1px solid var(--hairline);
  border-radius: 9px;
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.14s ease, background 0.14s ease;

  &:hover {
    border-color: var(--series-1);
    background: var(--app-primary-soft);
    color: var(--app-primary);
  }
}

/* ---------------- 消息 ---------------- */
.message {
  display: flex;
  gap: 12px;
  margin-bottom: 22px;
  max-width: 900px;

  &.user {
    flex-direction: row-reverse;
    margin-left: auto;
  }
}

.msg-avatar {
  flex-shrink: 0;
}

.avatar {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
  color: #fff;

  &.user {
    background: #52514e;
  }

  &.ai {
    background: linear-gradient(135deg, #2a78d6, #4a3aa7);
  }
}

.msg-body {
  min-width: 0;
  flex: 1;
}

.bubble {
  border-radius: 12px;
  padding: 14px 18px;
  font-size: 14px;
  line-height: 1.8;
  word-wrap: break-word;
}

.user-bubble {
  background: var(--series-1);
  color: #fff;
  border-radius: 12px 4px 12px 12px;
}

.ai-bubble {
  background: #fff;
  border: 1px solid var(--hairline);
  border-radius: 4px 12px 12px 12px;

  .answer {
    font-size: 14px;
  }
}

.degrade-note {
  margin-bottom: 12px;

  :deep(.el-alert__title) {
    font-size: 12.5px;
  }

  :deep(.el-alert__description) {
    font-size: 12px;
    line-height: 1.7;
  }
}

.msg-foot {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid #f2f3f5;
  font-size: 11.5px;
  color: var(--ink-muted);
}

.foot-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.foot-action {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  color: var(--app-primary);

  &:hover {
    text-decoration: underline;
  }
}

/* ---------------- 引用 ---------------- */
.citations {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.citation-item {
  background: #fff;
  border: 1px solid var(--hairline);
  border-left: 3px solid var(--seq-250);
  border-radius: 0 8px 8px 0;
  padding: 10px 14px;
  cursor: pointer;
  transition: border-color 0.14s ease, box-shadow 0.14s ease;

  &:hover {
    border-left-color: var(--series-1);
    box-shadow: var(--app-shadow);
  }
}

.ci-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.ci-index {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--app-primary);
  flex-shrink: 0;
}

.ci-title {
  font-size: 13px;
  font-weight: 550;
  flex: 1;
  min-width: 0;
}

.ci-score {
  font-size: 11.5px;
  color: var(--ink-muted);
}

.ci-snippet {
  font-size: 12.5px;
  line-height: 1.75;
  color: var(--ink-secondary);
  margin-top: 5px;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.ci-ranks {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 7px;
}

.ci-hint {
  font-size: 11px;
  color: var(--ink-muted);
}

/* ---------------- 检索过程 ---------------- */
.trace-panel {
  margin-top: 10px;
  background: #fff;
  border: 1px solid var(--hairline);
  border-radius: 10px;
  padding: 14px 16px;
}

.trace-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}

.trace-cell {
  padding: 8px 10px;
  background: var(--page-plane);
  border-radius: 7px;
}

.tc-label {
  font-size: 11.5px;
  color: var(--ink-muted);
  margin-bottom: 3px;
}

.tc-value {
  font-size: 13.5px;
  font-weight: 600;
}

.trace-section {
  margin-top: 14px;

  &:first-child {
    margin-top: 0;
  }
}

.ts-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-secondary);
  margin-bottom: 8px;
}

.ts-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.ts-note {
  font-size: 12px;
  line-height: 1.75;
  color: var(--ink-muted);
}

/* ---------------- 生成中动画 ---------------- */
.typing {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 16px 18px;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--seq-250);
  animation: bounce 1.4s infinite ease-in-out both;

  &:nth-child(2) {
    animation-delay: 0.16s;
  }

  &:nth-child(3) {
    animation-delay: 0.32s;
  }
}

.typing-text {
  font-size: 13px;
  color: var(--ink-muted);
  margin-left: 6px;
}

@keyframes bounce {
  0%,
  80%,
  100% {
    transform: scale(0.6);
    opacity: 0.5;
  }
  40% {
    transform: scale(1);
    opacity: 1;
  }
}

/* ---------------- 输入区 ---------------- */
.chat-input {
  flex-shrink: 0;
  display: flex;
  gap: 10px;
  align-items: flex-end;
  padding: 14px 24px 18px;
  background: #fff;
  border-top: 1px solid var(--hairline);

  :deep(.el-textarea__inner) {
    border-radius: 10px;
    font-size: 14px;
    line-height: 1.7;
    padding: 10px 14px;
    font-family: inherit;
  }
}

.send-btn {
  height: 54px;
  padding: 0 22px;
  flex-shrink: 0;
}

@media (max-width: 900px) {
  .session-panel {
    display: none;
  }

  .chat-scroll {
    padding: 16px;
  }
}
</style>
