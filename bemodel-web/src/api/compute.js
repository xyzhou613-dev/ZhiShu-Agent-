import request from './request'

export const listComputeRules = () => request.get('/compute/rules')
export const previewComputeRule = (code, ruleVersion, facts) =>
  request.post(`/compute/rules/${encodeURIComponent(code)}/preview`, { ruleVersion, facts })
