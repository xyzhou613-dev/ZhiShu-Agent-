import request from './request'

// ---------- 数据源 ----------
export const listDatasources = () => request.get('/datasource/list')

export const scanDatasource = (dsCode) => request.post(`/datasource/scan/${dsCode}`)

export const listTables = (dsCode) => request.get(`/datasource/tables/${dsCode}`)

export const listColumns = (dsCode, tableName) =>
  request.get(`/datasource/columns/${dsCode}`, { params: { tableName } })

// ---------- 映射 ----------
export const listMappings = (dsCode, tableName) =>
  request.get('/mapping/list', { params: { dsCode, tableName } })

export const saveMappings = (mappings) => request.post('/mapping/batch', mappings)

// F1：后端自带 LLM 总时间预算（bemodel.mapping.llm-budget-seconds，默认 280s，
// 超预算批次就地规则兜底），300s 只是前端安全网；timeoutErrorMessage 给可行动的中文指引
export const aiSuggest = (dsCode, tableName) =>
  request.get('/mapping/ai-suggest', {
    params: { dsCode, tableName },
    timeout: 300000,
    timeoutErrorMessage: 'AI 推荐等待超时（5 分钟），请缩小表宽或稍后重试'
  })

export const deleteMapping = (id) => request.delete(`/mapping/${id}`)

// 映射生命周期（V30）：PROPOSED→ACTIVE→DEPRECATED
export const transitionMapping = (id, target) =>
  request.post(`/mapping/${id}/transition`, { target })

export const mappingLog = (id) => request.get(`/mapping/${id}/log`)
