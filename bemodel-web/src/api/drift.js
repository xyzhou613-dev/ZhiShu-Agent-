import request from './request'

// ---------- 语义漂移检测 ----------
// 按需扫描：术语分叉（同一说法挂多个概念）+ 口径演进（版本间概念/指标定义变更）
export const scanDrift = () => request.get('/drift/scan')
