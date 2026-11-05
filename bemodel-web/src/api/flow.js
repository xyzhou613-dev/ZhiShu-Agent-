import request from './request'

// ---------- 流程演示 ----------
export const listFlowPatients = (keyword, pageNum = 1, pageSize = 20) =>
  request.get('/flow/patients', {
    params: { ...(keyword ? { keyword } : {}), pageNum, pageSize }
  })

export const flowLoop = (inhosNo) => request.get(`/flow/loop/${inhosNo}`)

// ---------- 门诊闭环 ----------
export const listOpdPatients = (keyword, pageNum = 1, pageSize = 20) =>
  request.get('/flow/opd/patients', {
    params: { ...(keyword ? { keyword } : {}), pageNum, pageSize }
  })

export const opdLoop = (cardNo) => request.get(`/flow/opd/loop/${cardNo}`)

// ---------- 人事组织域下钻：流程页姓名 → 人员/科室主数据 ----------
export const staffDetail = (name) => request.get('/flow/staff', { params: { name } })
