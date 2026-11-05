import request from './request'

// ---------- AI客服 ----------
export const listTickets = (pageNum = 1, pageSize = 20) =>
  request.get('/link/list', { params: { nodeType: 'TICKET', pageNum, pageSize } })

export const ticketDiagnosis = (ticketId) => request.get(`/cs/ticket/${ticketId}/diagnosis`)

export const refundTicket = (ticketId, operator = '客服小周') =>
  request.post(`/cs/ticket/${ticketId}/refund`, null, { params: { operator } })

// 问一问：自然语言提问（scene=CS 客服 / ANALYTICS 智能问数），平台真实能力作答
export const askCs = (question, scene = 'CS') => request.post('/cs/ask', { question, scene })

// 跨库核对差异登记：按批次号查（核对答案「查本批次差异登记」锚点 /cs?recon=<runId> 落地）
export const fetchReconDiffs = (runId) => request.get('/recon/diffs', { params: { runId } })

// 澄清续跑：补充约束后从原问题继续（q'=原问题+补充重入路由）
export const answerClarify = (taskId, supplement) =>
  request.post(`/cs/clarify/${taskId}/answer`, { supplement })

// 澄清任务列表（维护者视图，ADMIN/EDITOR）：A 型歧义证据，处置归扩展提案池
export const fetchClarifyTasks = (params) => request.get('/cs/clarify/list', { params })

// 回答反馈：correct 1=有帮助 0=归类有误；错例会回流进 LLM 路由提示词
export const submitCsFeedback = (data) => request.post('/cs/feedback', data)
