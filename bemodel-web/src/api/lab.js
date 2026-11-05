import request from './request'

// ---------- AI查询比对 ----------
export const labExperiments = () => request.get('/lab/experiments')
export const labStart = (question, forceExecute) => request.post('/lab/run', { question, forceExecute })
export const labStatus = (runId) => request.get(`/lab/run/${runId}`)
export const labRuns = () => request.get('/lab/runs')
export const labReset = () => request.post('/lab/reset')
export const labAudit = () => request.get('/lab/audit')
