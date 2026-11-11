import request from './request'

export const listComputeRules = () => request.get('/compute/rules')
export const previewComputeRule = (code, ruleVersion, facts) =>
  request.post(`/compute/rules/${encodeURIComponent(code)}/preview`, { ruleVersion, facts })
export const listComputeActionContracts = () => request.get('/compute/action-contracts')
export const listComputeConstraints = () => request.get('/compute/constraints')
export const computeActionResults = (operationKey) => request.get('/compute/actions/results', { params: { operationKey } })
