import request from './request'

export const listComputeRules = () => request.get('/compute/rules')
export const previewComputeRule = (code, ruleVersion, facts) =>
  request.post(`/compute/rules/${encodeURIComponent(code)}/preview`, { ruleVersion, facts })
export const listComputeActionContracts = () => request.get('/compute/action-contracts')
export const listComputeConstraints = () => request.get('/compute/constraints')
export const computeActionResults = (operationKey) => request.get('/compute/actions/results', { params: { operationKey } })
export const computeReleaseHistory = () => request.get('/compute/releases')
export const computeReleasePreflight = () => request.get('/compute/releases/preflight')
export const syncComputeRelease = (id) => request.post(`/compute/releases/${id}/sync`)
export const listComputeRuleConflicts = () => request.get('/compute/rule-conflicts')
export const listComputeDataAssets = () => request.get('/compute/data-assets')
export const updateComputeDataAsset = (code, body) => request.put(`/compute/data-assets/${encodeURIComponent(code)}`, body)
