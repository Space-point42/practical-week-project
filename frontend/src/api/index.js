import request from './request'

/**
 * 后端接口封装。
 *
 * 按模块分组，与后端 Controller 一一对应。
 * 所有方法返回 Promise，成功时 resolve 的是响应体中的 data 字段。
 */

/** 把后端统一响应解包为 data */
const unwrap = (promise) => promise.then((res) => res.data)

// ======================================================================
// 认证
// ======================================================================
export const authApi = {
  login: (data) => unwrap(request.post('/auth/login', data)),
  register: (data) => unwrap(request.post('/auth/register', data)),
  me: () => unwrap(request.get('/auth/me')),
  changePassword: (data) => unwrap(request.post('/auth/password', data)),
  updateProfile: (data) => unwrap(request.put('/auth/profile', data))
}

// ======================================================================
// 用户管理
// ======================================================================
export const userApi = {
  page: (params) => unwrap(request.get('/users', { params })),
  get: (id) => unwrap(request.get(`/users/${id}`)),
  create: (data) => unwrap(request.post('/users', data)),
  update: (id, data) => unwrap(request.put(`/users/${id}`, data)),
  remove: (id) => unwrap(request.delete(`/users/${id}`)),
  roles: () => unwrap(request.get('/users/roles'))
}

// ======================================================================
// 知识库
// ======================================================================
export const knowledgeApi = {
  page: (params) => unwrap(request.get('/knowledge', { params })),
  manage: (params) => unwrap(request.get('/knowledge/manage', { params })),
  mine: (params) => unwrap(request.get('/knowledge/mine', { params })),
  detail: (id) => unwrap(request.get(`/knowledge/${id}`)),
  hot: (limit = 8) => unwrap(request.get('/knowledge/hot', { params: { limit } })),
  recent: (limit = 8) => unwrap(request.get('/knowledge/recent', { params: { limit } })),
  byConcepts: (conceptIds, limit = 20) =>
    unwrap(request.get('/knowledge/by-concepts', { params: { conceptIds: conceptIds.join(','), limit } })),
  create: (data) => unwrap(request.post('/knowledge', data)),
  update: (id, data) => unwrap(request.put(`/knowledge/${id}`, data)),
  remove: (id) => unwrap(request.delete(`/knowledge/${id}`)),
  submit: (id) => unwrap(request.post(`/knowledge/${id}/submit`)),
  publish: (id, changeLog) =>
    unwrap(request.post(`/knowledge/${id}/publish`, null, { params: { changeLog } })),
  unpublish: (id) => unwrap(request.post(`/knowledge/${id}/unpublish`)),
  archive: (id, reason) =>
    unwrap(request.post(`/knowledge/${id}/archive`, null, { params: { reason } })),
  unarchive: (id) => unwrap(request.post(`/knowledge/${id}/unarchive`)),
  like: (id, liked = true) => unwrap(request.post(`/knowledge/${id}/like`, null, { params: { liked } })),
  myPermission: () => unwrap(request.get('/knowledge/my-permission'))
}

// ======================================================================
// 分类与标签
// ======================================================================
export const categoryApi = {
  list: () => unwrap(request.get('/categories')),
  tree: () => unwrap(request.get('/categories/tree')),
  get: (id) => unwrap(request.get(`/categories/${id}`)),
  create: (data) => unwrap(request.post('/categories', data)),
  update: (id, data) => unwrap(request.put(`/categories/${id}`, data)),
  remove: (id) => unwrap(request.delete(`/categories/${id}`))
}

export const tagApi = {
  list: (limit = 200) => unwrap(request.get('/tags', { params: { limit } })),
  hot: (limit = 20) => unwrap(request.get('/tags/hot', { params: { limit } })),
  suggest: (keyword) => unwrap(request.get('/tags/suggest', { params: { keyword } }))
}

// ======================================================================
// 知识本体
// ======================================================================
export const ontologyApi = {
  concepts: (domain) => unwrap(request.get('/ontology/concepts', { params: { domain } })),
  tree: () => unwrap(request.get('/ontology/concepts/tree')),
  concept: (id) => unwrap(request.get(`/ontology/concepts/${id}`)),
  searchConcepts: (keyword) => unwrap(request.get('/ontology/concepts/search', { params: { keyword } })),
  domains: () => unwrap(request.get('/ontology/domains')),
  createConcept: (data) => unwrap(request.post('/ontology/concepts', data)),
  updateConcept: (id, data) => unwrap(request.put(`/ontology/concepts/${id}`, data)),
  removeConcept: (id) => unwrap(request.delete(`/ontology/concepts/${id}`)),

  relationTypes: () => unwrap(request.get('/ontology/relation-types')),
  relations: (params) => unwrap(request.get('/ontology/relations', { params })),
  createRelation: (data) => unwrap(request.post('/ontology/relations', data)),
  removeRelation: (id) => unwrap(request.delete(`/ontology/relations/${id}`)),

  graph: (params) => unwrap(request.get('/ontology/graph', { params })),
  neighborhood: (conceptId, depth = 1) =>
    unwrap(request.get(`/ontology/graph/neighborhood/${conceptId}`, { params: { depth } })),
  stats: () => unwrap(request.get('/ontology/stats')),
  infer: (conceptId, relationType, depth = 3) =>
    unwrap(request.get('/ontology/infer', { params: { conceptId, relationType, depth } })),
  link: (text, limit = 10) => unwrap(request.get('/ontology/link', { params: { text, limit } }))
}

// ======================================================================
// 搜索引擎
// ======================================================================
export const searchApi = {
  search: (data) => unwrap(request.post('/search', data)),
  advanced: (data) => unwrap(request.post('/search/advanced', data)),
  suggest: (keyword, limit = 10) => unwrap(request.get('/search/suggest', { params: { keyword, limit } })),
  hotKeywords: (limit = 10) => unwrap(request.get('/search/hot-keywords', { params: { limit } })),
  stats: (limit = 10) => unwrap(request.get('/search/stats', { params: { limit } }))
}

// ======================================================================
// RAG 问答
// ======================================================================
export const ragApi = {
  ask: (data) => unwrap(request.post('/rag/ask', data)),
  sessions: () => unwrap(request.get('/rag/sessions')),
  messages: (sessionId) => unwrap(request.get(`/rag/sessions/${sessionId}/messages`)),
  removeSession: (sessionId) => unwrap(request.delete(`/rag/sessions/${sessionId}`)),
  indexStatus: () => unwrap(request.get('/rag/index/status')),
  rebuildIndex: (itemIds) => unwrap(request.post('/rag/index/rebuild', itemIds ? { itemIds } : {}))
}

// ======================================================================
// 版本管理
// ======================================================================
export const versionApi = {
  list: (itemId, params) => unwrap(request.get(`/items/${itemId}/versions`, { params })),
  detail: (itemId, versionNo) => unwrap(request.get(`/items/${itemId}/versions/${versionNo}`)),
  diff: (itemId, from, to) => unwrap(request.get(`/items/${itemId}/versions/diff`, { params: { from, to } })),
  create: (itemId, data) => unwrap(request.post(`/items/${itemId}/versions`, data)),
  rollback: (itemId, versionNo, changeLog) =>
    unwrap(request.post(`/items/${itemId}/versions/${versionNo}/rollback`, null, { params: { changeLog } })),
  decision: (itemId) => unwrap(request.get(`/items/${itemId}/versions/snapshot-decision`)),
  recent: (limit = 10) => unwrap(request.get('/versions/recent', { params: { limit } })),

  policies: () => unwrap(request.get('/versions/policies')),
  createPolicy: (data) => unwrap(request.post('/versions/policies', data)),
  updatePolicy: (id, data) => unwrap(request.put(`/versions/policies/${id}`, data)),
  removePolicy: (id) => unwrap(request.delete(`/versions/policies/${id}`)),
  prune: () => unwrap(request.post('/versions/prune'))
}

// ======================================================================
// 统计与审计
// ======================================================================
export const statsApi = {
  overview: () => unwrap(request.get('/stats/overview')),
  distribution: () => unwrap(request.get('/stats/distribution')),
  trend: (days = 14) => unwrap(request.get('/stats/trend', { params: { days } })),
  activity: (limit = 10) => unwrap(request.get('/stats/activity', { params: { limit } })),
  conceptHeat: (limit = 30) => unwrap(request.get('/stats/concept-heat', { params: { limit } })),
  systemInfo: () => unwrap(request.get('/stats/system-info'))
}

export const auditApi = {
  page: (params) => unwrap(request.get('/audit', { params })),
  recent: () => unwrap(request.get('/audit/recent')),
  ofTarget: (targetType, targetId) =>
    unwrap(request.get('/audit/target', { params: { targetType, targetId } }))
}

// ======================================================================
// 文件
// ======================================================================
export const fileApi = {
  /** 上传地址（供 el-upload 使用） */
  uploadUrl: '/api/files/upload',
  batchUploadUrl: '/api/files/upload-batch',
  previewUrl: (filePath) => `/api/files/preview/${filePath}`,
  allowedExtensions: () => unwrap(request.get('/files/allowed-extensions')),
  remove: (id) => unwrap(request.delete(`/files/${id}`))
}
