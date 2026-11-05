// ---------- RDF 导出 ----------
// 患者 ABox 三元组（text/turtle 附件），直接拼 /api URL 经 vite 代理下载，
// 不经 axios 实例（响应拦截器按 JSON 包处理，不适合文件流）
export const patientRdfUrl = (inhosNo) => `/api/rdf/patient/${encodeURIComponent(inhosNo)}`

export const exportPatientRdf = (inhosNo) => {
  const a = document.createElement('a')
  a.href = patientRdfUrl(inhosNo)
  a.download = `${inhosNo}.ttl`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}
