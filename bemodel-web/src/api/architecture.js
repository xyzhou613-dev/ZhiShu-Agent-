import request from './request'

// ---------- 架构全貌 ----------
export const getArchitectureOverview = () => request.get('/architecture/overview')
