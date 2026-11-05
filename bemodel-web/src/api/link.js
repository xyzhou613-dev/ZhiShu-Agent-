import request from './request'

// ---------- 链路节点 ----------
export const listLinks = (nodeType, conceptCode, pageNum = 1, pageSize = 20) =>
  request.get('/link/list', {
    params: {
      ...(nodeType ? { nodeType } : {}),
      ...(conceptCode ? { conceptCode } : {}),
      pageNum,
      pageSize
    }
  })

export const linkChain = (conceptCode) => request.get(`/link/chain/${conceptCode}`)

export const linkChainRels = (conceptCode) => request.get(`/link/chain/${conceptCode}/rels`)

export const createLink = (data) => request.post('/link', data)

// ---------- 节点级追溯边 ----------
export const traceLink = (refNo) => request.get(`/link/trace/${refNo}`)

export const createRel = (data) => request.post('/link/rel', data)

export const deleteRel = (id) => request.delete(`/link/rel/${id}`)

// ---------- 变更影响评估 ----------
export const analyzeImpact = (data) => request.post('/impact/analyze', data)

// ---------- 异常检测自动转工单 ----------
export const autoTicket = () => request.post('/link/auto-ticket')
