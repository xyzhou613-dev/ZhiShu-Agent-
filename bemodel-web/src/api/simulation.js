import request from './request'

// ---------- 推演沙盘 ----------
export const simScenarios = () => request.get('/simulation/scenarios')
export const simStart = (scenario, params) => request.post('/simulation/run', { scenario, params })
export const simStatus = (runId) => request.get(`/simulation/run/${runId}`)
export const simRuns = () => request.get('/simulation/runs')
export const simReset = () => request.post('/simulation/reset')
