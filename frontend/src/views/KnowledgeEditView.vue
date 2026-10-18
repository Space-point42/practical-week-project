<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { knowledgeApi, categoryApi, tagApi, ontologyApi, fileApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { ASSET_TYPES } from '@/utils/format'
import { markdownToPlain } from '@/utils/markdown'
import MarkdownView from '@/components/MarkdownView.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const isEdit = computed(() => Boolean(route.params.id))
const itemId = computed(() => Number(route.params.id))

const loading = ref(false)
const saving = ref(false)
const previewMode = ref(false)

const categoryTree = ref([])
const tagOptions = ref([])
const conceptOptions = ref([])
const conceptSearching = ref(false)
const attachments = ref([])
const uploadRef = ref()

const form = reactive({
  title: '',
  summary: '',
  content: '',
  contentFormat: 'MARKDOWN',
  assetType: 'DOCUMENT',
  categoryId: null,
  sourceUrl: '',
  sourceDesc: '',
  coverImage: '',
  tags: [],
  conceptIds: [],
  autoTagConcepts: true,
  changeLog: '',
  attachmentIds: []
})

const original = ref(null)
const formRef = ref()

// 放在脚本里而不是写在模板属性中：占位文本包含「>」字符，
// 直接写进模板会被解析器当作标签结束符。
const CONTENT_PLACEHOLDER = [
  '# 一级标题',
  '',
  '支持 Markdown 语法：标题、列表、表格、代码块、引用……',
  '',
  '用「> 」开头表示引用块。'
].join('\n')

const rules = {
  title: [
    { required: true, message: '请输入标题', trigger: 'blur' },
    { min: 2, max: 255, message: '标题长度 2~255 个字符', trigger: 'blur' }
  ],
  assetType: [{ required: true, message: '请选择资产类型', trigger: 'change' }]
}

/** 未保存改动提示：内容编辑是长耗时操作，误关页面损失很大 */
const dirty = computed(() => {
  if (!original.value) {
    return Boolean(form.title || form.content)
  }
  return (
    original.value.title !== form.title ||
    original.value.content !== form.content ||
    original.value.summary !== form.summary
  )
})

const plainLength = computed(() => markdownToPlain(form.content).length)

onMounted(async () => {
  loading.value = true
  try {
    const [tree, tags] = await Promise.all([categoryApi.tree(), tagApi.list(400)])
    categoryTree.value = tree || []
    tagOptions.value = tags || []

    if (isEdit.value) {
      const data = await knowledgeApi.detail(itemId.value)
      Object.assign(form, {
        title: data.title || '',
        summary: data.summary || '',
        content: data.content || '',
        contentFormat: data.contentFormat || 'MARKDOWN',
        assetType: data.assetType || 'DOCUMENT',
        categoryId: data.categoryId || null,
        sourceUrl: data.sourceUrl || '',
        sourceDesc: data.sourceDesc || '',
        coverImage: data.coverImage || '',
        tags: (data.tags || []).map((t) => t.name),
        conceptIds: data.conceptIds || [],
        attachmentIds: (data.attachments || []).map((a) => a.id)
      })
      attachments.value = data.attachments || []
      original.value = { title: form.title, content: form.content, summary: form.summary }
      // 回填已标注概念的选项，保证下拉框能显示名称
      conceptOptions.value = (data.conceptIds || []).map((id, i) => ({
        id,
        name: (data.conceptNames || [])[i] || `概念#${id}`
      }))
    }
  } finally {
    loading.value = false
  }
})

watch(
  () => form.content,
  () => {
    // 摘要留空时自动截取正文开头，减少手工填写负担
    if (!form.summary && form.content) {
      form.summary = markdownToPlain(form.content).slice(0, 180)
    }
  }
)

function searchConcepts(query) {
  if (!query) return
  conceptSearching.value = true
  ontologyApi
    .searchConcepts(query)
    .then((res) => {
      const merged = new Map(conceptOptions.value.map((c) => [c.id, c]))
      ;(res || []).forEach((c) => merged.set(c.id, c))
      conceptOptions.value = [...merged.values()]
    })
    .finally(() => {
      conceptSearching.value = false
    })
}

/** 用当前标题与正文做一次实体链接，给出建议标注 */
async function suggestConcepts() {
  const text = `${form.title}\n${markdownToPlain(form.content)}`
  if (text.trim().length < 10) {
    ElMessage.info('请先填写标题与正文')
    return
  }
  try {
    const linked = await ontologyApi.link(text, 12)
    const merged = new Map(conceptOptions.value.map((c) => [c.id, c]))
    ;(linked || []).forEach((c) => merged.set(c.id, c))
    conceptOptions.value = [...merged.values()]

    const added = (linked || []).filter((c) => !form.conceptIds.includes(c.id))
    form.conceptIds = [...form.conceptIds, ...added.map((c) => c.id)]
    ElMessage.success(added.length ? `识别到 ${added.length} 个知识点，已加入标注` : '没有识别到新的知识点')
  } catch {
    /* 拦截器已提示 */
  }
}

// ---------------- 附件 ----------------

function beforeUpload(file) {
  // 与后端白名单保持一致，提前拦截可以省去一次无用的上传
  const allowed = ['pdf', 'doc', 'docx', 'ppt', 'pptx', 'xls', 'xlsx', 'txt', 'md', 'csv', 'json', 'xml',
    'png', 'jpg', 'jpeg', 'gif', 'bmp', 'webp', 'svg', 'mp4', 'webm', 'mov', 'avi', 'mkv',
    'mp3', 'wav', 'm4a', 'aac', 'ogg']
  const ext = file.name.split('.').pop()?.toLowerCase() || ''
  if (!allowed.includes(ext)) {
    ElMessage.error(`不支持的文件类型：.${ext}`)
    return false
  }
  if (file.size > 300 * 1024 * 1024) {
    ElMessage.error('文件超过 300 MB 限制')
    return false
  }
  return true
}

function handleUploadSuccess(response) {
  if (response.code === 0) {
    const att = response.data
    attachments.value.push(att)
    form.attachmentIds.push(att.id)
    const hint =
      att.extractStatus === 'SUCCESS'
        ? `已抽取正文 ${att.textLength} 字，可被全文检索命中`
        : '该格式暂不支持抽取正文，仅供下载'
    ElMessage.success(`已上传：${att.fileName}（${hint}）`)
  } else {
    ElMessage.error(response.message || '上传失败')
  }
}

async function removeAttachment(att) {
  try {
    await ElMessageBox.confirm(`确定移除附件「${att.fileName}」吗？`, '移除附件', {
      type: 'warning',
      confirmButtonText: '移除',
      cancelButtonText: '取消'
    })
    await fileApi.remove(att.id)
    attachments.value = attachments.value.filter((a) => a.id !== att.id)
    form.attachmentIds = form.attachmentIds.filter((id) => id !== att.id)
    ElMessage.success('已移除')
  } catch {
    /* 取消 */
  }
}

function uploadHeaders() {
  return { Authorization: `Bearer ${localStorage.getItem('kms_token')}` }
}

// ---------------- 保存 ----------------

async function submitForm() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return null

  saving.value = true
  try {
    const payload = { ...form, changeLog: form.changeLog || undefined }
    const saved = isEdit.value
      ? await knowledgeApi.update(itemId.value, payload)
      : await knowledgeApi.create(payload)
    original.value = { title: form.title, content: form.content, summary: form.summary }
    return saved
  } finally {
    saving.value = false
  }
}

async function handleSave() {
  const saved = await submitForm()
  if (saved) {
    ElMessage.success('保存成功')
    if (!isEdit.value) {
      router.replace(`/editor/${saved.id}`)
    }
  }
}

async function handleSaveAndPublish() {
  const saved = await submitForm()
  if (!saved) return
  try {
    const { value } = await ElMessageBox.prompt('请填写本次发布说明（会记入版本历史）', '发布确认', {
      confirmButtonText: '发布',
      cancelButtonText: '取消',
      inputPlaceholder: '例如：补充索引优化章节，修正笔误',
      inputValidator: (v) => (v && v.trim().length >= 2 ? true : '请至少填写 2 个字符的发布说明')
    })
    await knowledgeApi.publish(saved.id, value)
    ElMessage.success('已发布')
    router.push(`/knowledge/${saved.id}`)
  } catch {
    /* 取消发布；内容已保存为草稿 */
  }
}

async function handleSaveAsDraftAndExit() {
  const saved = await submitForm()
  if (saved) {
    ElMessage.success('已保存为草稿')
    router.push(userStore.canEdit ? '/knowledge' : '/dashboard')
  }
}

function handleBack() {
  if (!dirty.value) {
    router.back()
    return
  }
  ElMessageBox.confirm('当前有未保存的修改，确定离开吗？', '未保存的修改', {
    type: 'warning',
    confirmButtonText: '放弃修改并离开',
    cancelButtonText: '继续编辑',
    confirmButtonClass: 'el-button--danger'
  })
    .then(() => router.back())
    .catch(() => {})
}

onMounted(() => {
  // 浏览器刷新/关闭时的原生提示，作为最后一道防线
  window.addEventListener('beforeunload', (e) => {
    if (dirty.value && !saving.value) {
      e.preventDefault()
      e.returnValue = ''
    }
  })
})
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ isEdit ? '编辑知识' : '新建知识' }}</h1>
        <p class="page-subtitle">
          正文使用 Markdown 编写；保存时会自动抽取纯文本用于检索，
          并按版本策略判断是否生成新的版本快照
        </p>
      </div>
      <div class="header-actions">
        <el-button :icon="'Back'" @click="handleBack">返回</el-button>
        <el-button
          :type="previewMode ? 'primary' : ''"
          :icon="previewMode ? 'EditPen' : 'View'"
          @click="previewMode = !previewMode"
        >
          {{ previewMode ? '继续编辑' : '预览' }}
        </el-button>
      </div>
    </div>

    <div v-loading="loading" class="editor-layout">
      <!-- ============ 主编辑区 ============ -->
      <div class="editor-main">
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
          <div class="card editor-card">
            <el-form-item prop="title" class="title-item">
              <el-input
                v-model="form.title"
                placeholder="请输入知识标题，例如：《需求分析实践总结》"
                size="large"
                maxlength="255"
                show-word-limit
                class="title-input"
              />
            </el-form-item>

            <el-form-item label="摘要">
              <el-input
                v-model="form.summary"
                type="textarea"
                :rows="2"
                maxlength="500"
                show-word-limit
                resize="none"
                placeholder="一句话说明这条知识解决了什么问题。留空时会自动从正文截取"
              />
            </el-form-item>

            <el-form-item label="正文（Markdown）" class="content-item">
              <div v-if="previewMode" class="preview-box">
                <MarkdownView v-if="form.content" :content="form.content" />
                <el-empty v-else description="还没有正文内容" :image-size="70" />
              </div>
              <el-input
                v-else
                v-model="form.content"
                type="textarea"
                :rows="22"
                resize="none"
                :placeholder="CONTENT_PLACEHOLDER"
                class="content-textarea"
              />
            </el-form-item>

            <div class="content-stats">
              <span>正文纯文本 {{ plainLength }} 字符</span>
              <span v-if="plainLength < 50" class="warn">内容过短，检索与问答的效果会受限</span>
            </div>
          </div>
        </el-form>

        <!-- 附件 -->
        <div class="card editor-card">
          <h3 class="section-title">
            附件
            <span class="section-hint">支持文档 / 图片 / 音视频，文档会自动抽取正文进入检索范围</span>
          </h3>

          <el-upload
            ref="uploadRef"
            :action="fileApi.uploadUrl"
            :headers="uploadHeaders()"
            :show-file-list="false"
            :before-upload="beforeUpload"
            :on-success="handleUploadSuccess"
            multiple
            drag
            class="uploader"
          >
            <el-icon class="upload-icon"><UploadFilled /></el-icon>
            <div class="upload-text">将文件拖到此处，或<em>点击选择文件</em></div>
            <div class="upload-hint">
              PDF / Word / PPT / Excel / 文本 / 图片 / 视频 / 音频，单个文件不超过 300 MB
            </div>
          </el-upload>

          <div v-if="attachments.length" class="attachment-list">
            <div v-for="att in attachments" :key="att.id" class="attachment-item">
              <el-icon :size="16" class="att-icon">
                <component
                  :is="att.mediaType === 'IMAGE' ? 'Picture'
                    : att.mediaType === 'VIDEO' ? 'VideoCamera'
                    : att.mediaType === 'AUDIO' ? 'Headset' : 'Document'"
                />
              </el-icon>
              <div class="att-main">
                <div class="att-name">{{ att.fileName }}</div>
                <div class="att-meta">
                  <span>{{ att.readableSize }}</span>
                  <el-tag
                    size="small"
                    :type="att.extractStatus === 'SUCCESS' ? 'success' : 'info'"
                    effect="plain"
                  >
                    {{
                      att.extractStatus === 'SUCCESS'
                        ? `已抽取正文 ${att.textLength} 字`
                        : att.extractStatus === 'UNSUPPORTED'
                          ? '该格式不支持抽取正文'
                          : '正文抽取失败'
                    }}
                  </el-tag>
                </div>
              </div>
              <el-button text type="danger" :icon="'Delete'" @click="removeAttachment(att)" />
            </div>
          </div>
        </div>
      </div>

      <!-- ============ 右侧属性栏 ============ -->
      <aside class="editor-side">
        <div class="card side-card">
          <h3 class="section-title">发布设置</h3>

          <div class="field">
            <label>资产类型</label>
            <el-select v-model="form.assetType" class="full-width">
              <el-option v-for="t in ASSET_TYPES" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
          </div>

          <div class="field">
            <label>所属分类</label>
            <el-tree-select
              v-model="form.categoryId"
              :data="categoryTree"
              node-key="id"
              :props="{ label: 'name', children: 'children' }"
              check-strictly
              clearable
              placeholder="选择分类"
              class="full-width"
            />
          </div>

          <div class="field">
            <label>
              标签
              <span class="field-hint">回车创建新标签</span>
            </label>
            <el-select
              v-model="form.tags"
              multiple
              filterable
              allow-create
              default-first-option
              collapse-tags
              collapse-tags-tooltip
              placeholder="选择或创建"
              class="full-width"
            >
              <el-option v-for="t in tagOptions" :key="t.id" :label="t.name" :value="t.name">
                <span>{{ t.name }}</span>
                <span class="opt-count"> {{ t.useCount }}</span>
              </el-option>
            </el-select>
          </div>

          <div class="field">
            <label>来源链接</label>
            <el-input v-model="form.sourceUrl" placeholder="选填，如论文或原始资料的 URL" />
          </div>

          <div class="field">
            <label>来源说明</label>
            <el-input v-model="form.sourceDesc" placeholder="选填，如：课程文献阅读作业" />
          </div>
        </div>

        <!-- 知识点标注 -->
        <div class="card side-card">
          <h3 class="section-title">
            知识点标注
            <span class="section-hint">把内容挂到本体上</span>
          </h3>

          <div class="concept-actions">
            <el-button size="small" :icon="'MagicStick'" @click="suggestConcepts">
              智能识别
            </el-button>
            <el-checkbox v-model="form.autoTagConcepts" size="small">保存时自动标注</el-checkbox>
          </div>

          <el-select
            v-model="form.conceptIds"
            multiple
            filterable
            remote
            collapse-tags
            collapse-tags-tooltip
            :remote-method="searchConcepts"
            :loading="conceptSearching"
            placeholder="搜索并选择本体知识点"
            class="full-width"
          >
            <el-option v-for="c in conceptOptions" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>

          <div class="concept-hint">
            标注后，该条目会出现在对应知识点的「关联知识」中，
            同时参与基于本体的检索扩展 —— 用户搜索相邻知识点时也能被召回。
          </div>
        </div>

        <!-- 版本说明 -->
        <div class="card side-card">
          <h3 class="section-title">
            版本说明
            <span class="section-hint">写入本次变更的快照</span>
          </h3>

          <el-input
            v-model="form.changeLog"
            type="textarea"
            :rows="3"
            resize="none"
            maxlength="300"
            show-word-limit
            placeholder="例如：补充索引优化章节，修正笔误"
          />

          <div class="version-hint">
            保存时会累加修改次数。当「距上次快照的天数」或「累计修改次数」
            任一达到策略阈值时，系统自动生成新的版本快照。
          </div>
        </div>

        <!-- 操作 -->
        <div class="card side-card action-card">
          <el-button
            type="primary"
            size="large"
            class="full-width"
            :loading="saving"
            @click="handleSave"
          >
            {{ isEdit ? '保存修改' : '创建条目' }}
          </el-button>
          <el-button
            size="large"
            class="full-width"
            :loading="saving"
            :icon="'Promotion'"
            @click="handleSaveAndPublish"
          >
            保存并发布
          </el-button>
          <el-button text class="full-width" :disabled="saving" @click="handleSaveAsDraftAndExit">
            保存为草稿并返回
          </el-button>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.header-actions {
  display: flex;
  gap: 8px;
}

.editor-layout {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}

.editor-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.editor-card {
  padding: 18px 20px;
}

.title-item {
  margin-bottom: 16px;
}

.title-input {
  :deep(.el-input__inner) {
    font-size: 18px;
    font-weight: 600;
    height: 44px;
  }
}

.content-item {
  margin-bottom: 6px;
}

.content-textarea {
  :deep(.el-textarea__inner) {
    font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
    font-size: 13.5px;
    line-height: 1.8;
  }
}

.preview-box {
  width: 100%;
  min-height: 420px;
  padding: 18px 22px;
  border: 1px solid var(--gridline);
  border-radius: 8px;
  background: #fff;
}

.content-stats {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: var(--ink-muted);
  padding-top: 4px;

  .warn {
    color: var(--status-serious);
  }
}

/* ---------------- 上传 ---------------- */
.uploader {
  :deep(.el-upload-dragger) {
    padding: 22px;
    border-radius: 10px;
    border-style: dashed;
    background: var(--page-plane);

    &:hover {
      border-color: var(--series-1);
      background: var(--app-primary-soft);
    }
  }
}

.upload-icon {
  font-size: 34px;
  color: var(--seq-400);
  margin-bottom: 8px;
}

.upload-text {
  font-size: 13.5px;
  color: var(--ink-secondary);

  em {
    color: var(--app-primary);
    font-style: normal;
  }
}

.upload-hint {
  font-size: 12px;
  color: var(--ink-muted);
  margin-top: 6px;
}

.attachment-list {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.attachment-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border: 1px solid var(--hairline);
  border-radius: 8px;
  background: #fff;
}

.att-icon {
  color: var(--seq-400);
  flex-shrink: 0;
}

.att-main {
  flex: 1;
  min-width: 0;
}

.att-name {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.att-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11.5px;
  color: var(--ink-muted);
  margin-top: 3px;
}

/* ---------------- 侧栏 ---------------- */
.editor-side {
  width: 320px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.side-card {
  padding: 16px 18px;
}

.field {
  margin-bottom: 14px;

  &:last-child {
    margin-bottom: 0;
  }

  label {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: 12px;
    font-weight: 600;
    color: var(--ink-secondary);
    margin-bottom: 6px;
  }
}

.field-hint {
  font-weight: 400;
  font-size: 11px;
  color: var(--ink-muted);
}

.full-width {
  width: 100%;
}

.opt-count {
  float: right;
  color: var(--ink-muted);
  font-size: 11px;
}

.concept-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}

.concept-hint,
.version-hint {
  font-size: 11.5px;
  line-height: 1.75;
  color: var(--ink-muted);
  margin-top: 10px;
}

.action-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

@media (max-width: 1100px) {
  .editor-layout {
    flex-direction: column;
  }

  .editor-side {
    width: 100%;
  }
}
</style>
