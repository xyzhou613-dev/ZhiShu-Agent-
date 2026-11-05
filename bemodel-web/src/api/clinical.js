import request from './request'

// ---------- 病案质控 ----------
export const listQcRecords = (keyword, pageNum = 1, pageSize = 20) =>
  request.get('/qc/records', {
    params: { ...(keyword ? { keyword } : {}), pageNum, pageSize }
  })

export const checkQc = (recordId) => request.post(`/qc/check/${recordId}`)

export const checkAllQc = () => request.post('/qc/check-all')

export const qcResult = (recordId) => request.get(`/qc/result/${recordId}`)

// ---------- SHACL 复核（平台内置 Jena 校验） ----------
export const validateRdf = (inhosNo) => request.post(`/rdf/validate/${inhosNo}`)

// ---------- 危重症预警 ----------
export const detectAlerts = () => request.post('/alert/detect')
