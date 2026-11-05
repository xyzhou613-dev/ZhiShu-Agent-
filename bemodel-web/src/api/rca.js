import request from './request'

// ---------- 根因分析 ----------
export const startRca = (ticketRef) =>
  request.post('/rca/start', null, { params: { ticketRef } })

export const listRcaCases = () => request.get('/rca/list')

export const rcaDetail = (caseId) => request.get(`/rca/${caseId}`)
