import request from './request'

// ---------- 本体版本发布 ----------
export const listReleases = () => request.get('/release/list')

export const currentRelease = () => request.get('/release/current')

export const publishRelease = (data) => request.post('/release/publish', data)

export const releaseDetail = (id) => request.get(`/release/${id}`)

// ---------- LLM 调用审计 ----------
export const listLlmLogs = (pageNum = 1, pageSize = 20) =>
  request.get('/llm/log/list', { params: { pageNum, pageSize } })

export const llmStats = () => request.get('/llm/stats')

// ---------- LLM 主备路由健康（借鉴 4） ----------
export const getLlmRoutes = () => request.get('/llm/routes')
