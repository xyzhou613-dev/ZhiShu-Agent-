import request from './request'

// 证据链 trace：type=QA|CONCEPT|MAPPING，key=traceId/概念code/映射id
export const getTrace = (type, key) => request.get('/trace', { params: { type, key } })
