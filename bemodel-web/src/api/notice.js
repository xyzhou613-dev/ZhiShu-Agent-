import request from './request'

// ---------- 巡检告警 ----------
export const unreadNoticeCount = () => request.get('/notice/unread-count')

export const listNotices = (pageNum = 1, pageSize = 20) =>
  request.get('/notice/list', { params: { pageNum, pageSize } })

export const readNotice = (id) => request.post(`/notice/${id}/read`)

export const readAllNotices = () => request.post('/notice/read-all')

export const runInspect = () => request.post('/inspect/run')
