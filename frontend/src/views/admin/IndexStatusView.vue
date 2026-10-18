<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ragApi } from '@/api'
import { formatDuration } from '@/utils/format'
import StatTile from '@/components/StatTile.vue'

/**
 * 检索索引状态。
 *
 * 向量是「派生数据」：它由模型对文本计算而来，脱离原模型即失去意义。
 * 因此页面同时关注两件事 —— 索引规模，以及索引与当前配置模型是否一致。
 */
const loading = ref(true)
const rebuilding = ref(false)
const status = ref({})
const lastResult = ref(null)

const staleModels = computed(() => status.value.staleModels || [])
const onlineEmbedding = computed(() => Boolean(status.value.onlineEmbedding))
const chatAvailable = computed(() => Boolean(status.value.chatAvailable))
const embeddingCoverage = computed(() => {
  const total = Number(status.value.totalChunks || 0)
  if (!total) return 0
  return Math.round((Number(status.value.chunksWithEmbedding || 0) / total) * 100)
})

onMounted(load)

async function load() {
  loading.value = true
  try {
    status.value = (await ragApi.indexStatus()) || {}
  } finally {
    loading.value = false
  }
}

async function handleRebuild() {
  try {
    await ElMessageBox.confirm(
      '重建会遍历全部知识条目并重新计算向量，可能耗时数十秒。重建期间旧索引仍可继续提供检索，确定开始吗？',
      '重建全部索引',
      { type: 'warning', confirmButtonText: '开始重建', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  rebuilding.value = true
  try {
    // 不传 itemIds 表示重建全部
    const res = await ragApi.rebuildIndex()
    lastResult.value = res
    ElMessage.success(`重建完成：处理 ${res.itemsProcessed} 个条目，生成 ${res.chunksCreated} 个分块`)
    load()
  } catch {
    /* 错误提示由拦截器给出 */
  } finally {
    rebuilding.value = false
  }
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h1 class="page-title">检索索引</h1>
        <p class="page-subtitle">向量索引的规模、所依赖的模型，以及可触发的重建操作</p>
      </div>
      <div class="header-actions">
        <el-button :icon="'Refresh'" @click="load">刷新</el-button>
        <el-button type="primary" :icon="'RefreshRight'" :loading="rebuilding" @click="handleRebuild">
          重建全部索引
        </el-button>
      </div>
    </div>

    <!-- 模型不一致的告警放在最上方：它直接决定检索结果是否可信 -->
    <el-alert
      v-if="staleModels.length"
      type="warning"
      :closable="false"
      show-icon
      class="stale-alert"
      title="存在由其他模型生成的旧向量，需要重建后才能正常参与检索"
    >
      <div class="stale-body">
        <p>
          这些分块的向量是由与当前配置不同的模型计算出来的。不同模型产生的是坐标体系不同的向量，
          它们的余弦相似度没有可比性，混在同一索引里会让召回结果出现难以排查的偏差，因此需要执行一次重建。
        </p>
        <div class="stale-models">
          <span class="stale-label">涉及的旧模型：</span>
          <el-tag
            v-for="m in staleModels"
            :key="m"
            type="warning"
            effect="plain"
            size="small"
            class="stale-tag"
            disable-transitions
          >
            {{ m }}
          </el-tag>
        </div>
      </div>
    </el-alert>

    <!-- ============ 规模指标 ============ -->
    <el-row :gutter="14" class="stat-row">
      <el-col :xs="12" :sm="12" :md="6">
        <StatTile
          label="检索分块总数"
          :value="Number(status.totalChunks || 0)"
          icon="Connection"
          accent="var(--series-1)"
          hint="按条目切分出的检索单元"
        />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatTile
          label="已生成向量的分块"
          :value="Number(status.chunksWithEmbedding || 0)"
          icon="MagicStick"
          accent="var(--series-3)"
          :hint="`覆盖率 ${embeddingCoverage}%`"
        />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatTile
          label="已索引知识条目"
          :value="Number(status.indexedItems || 0)"
          icon="Collection"
          accent="var(--series-7)"
          hint="至少含一个分块的条目数"
        />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatTile
          label="向量维度"
          :value="Number(status.dimension || 0)"
          icon="Grid"
          accent="var(--series-4)"
          hint="每个向量的浮点分量个数"
        />
      </el-col>
    </el-row>

    <!-- ============ 当前模型 ============ -->
    <div class="card model-card">
      <h2 class="section-title">
        当前模型
        <span class="section-hint">检索质量取决于下方两个模型的配置</span>
      </h2>

      <div class="model-grid">
        <div class="model-block">
          <div class="mb-head">
            <span class="mb-role">语义向量模型</span>
            <el-tag :type="onlineEmbedding ? 'success' : 'info'" effect="plain" size="small" disable-transitions>
              {{ onlineEmbedding ? '在线语义向量' : '本地哈希向量' }}
            </el-tag>
          </div>
          <div class="mb-name">{{ status.embeddingModel || '未配置' }}</div>
          <p class="mb-desc">{{ status.description || '暂无描述' }}</p>
          <p v-if="!onlineEmbedding" class="mb-note">
            当前未启用在线向量服务，系统改用本地哈希向量兜底。它只能做字面近似的匹配，语义检索能力有限。
          </p>
        </div>

        <div class="model-block">
          <div class="mb-head">
            <span class="mb-role">生成模型</span>
            <el-tag :type="chatAvailable ? 'success' : 'warning'" effect="plain" size="small" disable-transitions>
              {{ chatAvailable ? '可用' : '不可用' }}
            </el-tag>
          </div>
          <div class="mb-name">{{ status.chatModel || '未配置' }}</div>
          <p class="mb-desc">
            智能问答在生成回答时使用该模型。当它不可用时，RAG 会自动降级为离线抽取式回答 ——
            直接从召回的分块中摘取最相关的片段返回，回答仍可溯源，但不再做语言组织。
          </p>
        </div>
      </div>
    </div>

    <!-- ============ 重建结果 ============ -->
    <div v-if="lastResult" class="card result-card">
      <h2 class="section-title">上次重建结果</h2>
      <div class="result-grid">
        <div class="result-item">
          <div class="ri-value tabular">{{ lastResult.itemsProcessed }}</div>
          <div class="ri-label">处理条目</div>
        </div>
        <div class="result-item">
          <div class="ri-value tabular">{{ lastResult.chunksCreated }}</div>
          <div class="ri-label">生成分块</div>
        </div>
        <div class="result-item">
          <div class="ri-value tabular">{{ formatDuration(lastResult.costMs) }}</div>
          <div class="ri-label">耗时</div>
        </div>
        <div class="result-item">
          <div class="ri-value tabular">{{ lastResult.degraded ? '是' : '否' }}</div>
          <div class="ri-label">是否降级</div>
        </div>
      </div>
      <p v-if="lastResult.degraded" class="result-note">
        本次重建运行在降级模式下（未使用在线向量模型），生成的向量为本地哈希向量。
      </p>
    </div>

    <!-- ============ 概念说明 ============ -->
    <div class="card explain-card">
      <h2 class="section-title">
        索引是怎么工作的
        <span class="section-hint">四个需要理解的概念</span>
      </h2>
      <ul class="explain-list">
        <li>
          <span class="ex-term">分块（chunk）</span>
          <span class="ex-text">
            一篇知识条目会先被切成若干较短的片段，每个片段就是一个分块。检索与向量计算都以分块为单位，
            这样回答才能定位到具体段落，而不是整篇文档。
          </span>
        </li>
        <li>
          <span class="ex-term">向量以 float32 存储</span>
          <span class="ex-text">
            每个分块的向量是一串 32 位浮点数（float32 字节序列）。用 float32 而非更高精度浮点，
            是在几乎不影响相似度排序的前提下，把存储开销减半。
          </span>
        </li>
        <li>
          <span class="ex-term">用余弦相似度召回</span>
          <span class="ex-text">
            检索时把查询也编码成向量，再与库中分块向量逐一计算余弦相似度，取最相近的若干块。
            余弦相似度只看向量的方向，因而不受文本长短影响。
          </span>
        </li>
        <li>
          <span class="ex-term">每个分块记录 embed_model</span>
          <span class="ex-text">
            向量是派生数据 —— 它由某个具体模型计算得到，脱离该模型就只是一串无意义的数字。
            所以每个分块都存下生成它的模型标识（embed_model）；一旦当前模型与记录不符，
            系统就能识别出这些向量需要重建，而不是放任它们污染检索结果。
          </span>
        </li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.header-actions {
  display: flex;
  gap: 10px;
}

.stale-alert {
  margin-bottom: 14px;
}

.stale-body p {
  margin: 4px 0 10px;
  line-height: 1.7;
}

.stale-models {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.stale-label {
  font-size: 12.5px;
  color: var(--ink-secondary);
}

.stale-tag {
  font-family: 'SFMono-Regular', Consolas, monospace;
}

.stat-row {
  margin-bottom: 0;

  :deep(.el-col) {
    margin-bottom: 14px;
  }
}

.model-card,
.result-card,
.explain-card {
  padding: 16px 18px;
  margin-bottom: 14px;
}

.model-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 16px;
}

.model-block {
  background: var(--page-plane);
  border: 1px solid var(--hairline);
  border-radius: var(--app-radius-sm);
  padding: 14px 16px;
}

.mb-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.mb-role {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-secondary);
}

.mb-name {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 15px;
  font-weight: 600;
  color: var(--ink-primary);
  word-break: break-all;
}

.mb-desc {
  font-size: 12.5px;
  color: var(--ink-secondary);
  line-height: 1.7;
  margin: 8px 0 0;
}

.mb-note {
  font-size: 12px;
  color: var(--ink-muted);
  line-height: 1.65;
  margin: 8px 0 0;
  padding-top: 8px;
  border-top: 1px dashed var(--hairline);
}

.result-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 14px;
}

.result-item {
  text-align: center;
  padding: 10px 0;
}

.ri-value {
  font-size: 22px;
  font-weight: 650;
  color: var(--ink-primary);
  line-height: 1.2;
}

.ri-label {
  font-size: 12px;
  color: var(--ink-muted);
  margin-top: 4px;
}

.result-note {
  margin: 12px 0 0;
  font-size: 12.5px;
  color: var(--status-warning);
}

.explain-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.explain-list li {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  line-height: 1.75;
}

.ex-term {
  flex-shrink: 0;
  width: 150px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-primary);
}

.ex-text {
  font-size: 13px;
  color: var(--ink-secondary);
}

@media (max-width: 720px) {
  .explain-list li {
    flex-direction: column;
    gap: 2px;
  }

  .ex-term {
    width: auto;
  }
}
</style>
