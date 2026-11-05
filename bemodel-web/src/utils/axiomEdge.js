// 公理视觉编码共享纯函数（spec 2026-09-21 §4/§5）。
// 后端 ArchitectureService.addRelationEdge 是 overview 边的折叠与 axioms 并集源头；
// 本文件的 foldRelations 供发布快照（原始实体、未折叠）在前端复用同一套规则——两处规则改动必须同步。
// 已知的无害不对称（概念码格式+uk_rel 约束下不可达，勿"顺手修复"）：后端 inverseOf 有 isBlank() 守卫而前端 truthy 判断会放过空白串；后端 split("->",2) 保留目标码余部而前端 split 无 limit。
export const AXIOM_ORDER = ['symmetric', 'transitive', 'functional', 'inverseFunctional', 'asymmetric']

const SUFFIX_TEXT = { functional: '函数', inverseFunctional: '逆函数', asymmetric: '反对称' }

// 说人话解释（spec §4 逐字；本体页领域词可直用，解释句必须是人话）
const HELP_TEXT = {
  symmetric: '对称：有 a→b 就必有 b→a',
  transitive: '传递：A到B、B到C，则A直达C',
  functional: '函数：同一主语至多指向一个对象',
  inverseFunctional: '逆函数：由对象可反推唯一主语',
  asymmetric: '反对称：有 a→b 则不容 b→a'
}

// 边名尾缀：只挂低频约束公理与互逆（传递/对称有图形编码，不重复占字）
export function axiomSuffixOf(e) {
  const axioms = e.axioms || []
  const parts = []
  for (const a of axioms) if (SUFFIX_TEXT[a]) parts.push(SUFFIX_TEXT[a])
  if (e.inverseOf) parts.push('互逆')
  return parts.length ? ' ·' + parts.join('·') : ''
}

// 图形编码：传递=橙虚线（避开互斥红虚线），对称=双向箭头；自环边加大弯曲成可见弧
export function axiomStyleOf(e, baseColor, opts = {}) {
  const axioms = e.axioms || []
  const transitive = axioms.includes('transitive')
  const symmetric = axioms.includes('symmetric')
  const selfLoop = e.source === e.target
  return {
    lineStyle: {
      color: transitive ? '#e6a23c' : baseColor,
      type: transitive ? 'dashed' : 'solid',
      width: opts.width ?? 1.5,
      curveness: selfLoop ? 0.35 : (opts.curveness ?? 0.12)
    },
    symbol: symmetric ? ['arrow', 'arrow'] : ['none', 'arrow']
  }
}

// 悬停解释行（每条公理一行人话）
export function axiomTooltipOf(e) {
  const lines = []
  for (const a of e.axioms || []) if (HELP_TEXT[a]) lines.push(HELP_TEXT[a])
  if (e.inverseOf) lines.push(`互逆：与「${e.inverseOf}」互为反方向说法`)
  return lines
}

// 前端折叠（与后端 ArchitectureService.addRelationEdge 同规则）：同 (from,to) 一条边，
// label 按 Java String 同序（UTF-16 码元序）排序「/」连接、超 3 截断；axioms 并集 canonical 序
export function foldRelations(rels) {
  const byPair = new Map()
  for (const r of rels || []) {
    const key = `${r.fromConcept}->${r.toConcept}`
    if (!byPair.has(key)) byPair.set(key, [])
    byPair.get(key).push(r)
  }
  const out = []
  for (const [key, members] of byPair) {
    const sorted = [...members].sort((a, b) =>
      a.relationName < b.relationName ? -1 : a.relationName > b.relationName ? 1 : 0)
    const names = sorted.map((r) => r.relationName)
    const label = names.length <= 3 ? names.join('/') : names.slice(0, 3).join('/') + '+' + (names.length - 3)
    const flagOn = (v) => v === 1 || v === true
    const set = new Set()
    let inverseOf = null
    for (const r of sorted) {
      if (flagOn(r.isSymmetric)) set.add('symmetric')
      if (flagOn(r.isTransitive)) set.add('transitive')
      if (flagOn(r.isFunctional)) set.add('functional')
      if (flagOn(r.isInverseFunctional)) set.add('inverseFunctional')
      if (flagOn(r.isAsymmetric)) set.add('asymmetric')
      if (!inverseOf && r.inverseOf) inverseOf = r.inverseOf
    }
    const axioms = AXIOM_ORDER.filter((a) => set.has(a))
    const [fromConcept, toConcept] = key.split('->')
    const edge = { source: `C:${fromConcept}`, target: `C:${toConcept}`, label, axioms }
    if (inverseOf) edge.inverseOf = inverseOf
    out.push(edge)
  }
  return out
}
