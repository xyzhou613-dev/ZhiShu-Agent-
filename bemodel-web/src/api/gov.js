import request from './request'

// ---------- 数据治理 ----------
export const govOverview = () => request.get('/gov/overview')

export const govTables = () => request.get('/gov/tables')

export const listGovIssues = (pageNum = 1, pageSize = 20) =>
  request.get('/gov/issues', { params: { pageNum, pageSize } })

export const runGovScan = () => request.post('/gov/scan')

export const resolveGovIssue = (id) => request.post(`/gov/issues/${id}/resolve`)

export const govIssueToTicket = (id) => request.post(`/gov/issues/${id}/ticket`)
