import request from './request'

// ---------- 认证 ----------
export const login = (data) => request.post('/auth/login', data)

export const currentUser = () => request.get('/auth/me')
