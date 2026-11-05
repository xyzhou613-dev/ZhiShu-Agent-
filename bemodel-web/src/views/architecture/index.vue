<template>
  <div class="page">
    <!-- 顶部统计卡片 -->
    <el-card class="stats-card" v-loading="loading">
      <div class="stats-row">
        <div class="stat">
          <div class="stat-value">{{ stats.conceptCount }}</div>
          <div class="stat-label">概念数</div>
        </div>
        <div class="stat">
          <div class="stat-value">{{ stats.relationCount }}</div>
          <div class="stat-label">关系数</div>
        </div>
        <div class="stat">
          <div class="stat-value">{{ stats.datasourceCount }}</div>
          <div class="stat-label">数据源数</div>
        </div>
        <div class="stat">
          <div class="stat-value">{{ stats.tableCount }}</div>
          <div class="stat-label">物理表数</div>
        </div>
        <div class="stat">
          <div class="stat-value" :class="coverageClass">{{ stats.coverage }}%</div>
          <div class="stat-label">映射覆盖度</div>
        </div>
        <div class="stat">
          <div class="stat-value">{{ stats.ruleCount }}</div>
          <div class="stat-label">规则数</div>
        </div>
        <div class="stat">
          <div class="stat-value">{{ stats.metricCount }}</div>
          <div class="stat-label">指标数</div>
        </div>
      </div>
    </el-card>

    <!-- 工具栏 -->
    <div class="toolbar">
      <el-radio-group v-model="viewMode">
        <el-radio-button value="entities">实体全景</el-radio-button>
        <el-radio-button value="layers">分层架构</el-radio-button>
      </el-radio-group>
      <template v-if="viewMode === 'entities'">
        <el-input
          v-model="keyword"
          placeholder="按名称 / 编码搜索节点"
          clearable
          :prefix-icon="Search"
          style="width: 260px"
        />
        <el-switch v-model="onlyUnmapped" class="unmapped-switch" />
        <span class="switch-label">只看未映射概念</span>
      </template>
      <span class="toolbar-tip">{{
        viewMode === 'entities'
          ? '点击节点跳转到对应管理页面；图例可开关某类节点'
          : '四层语义架构：业务应用 → 推理引擎 → 数据映射 → 本体；点击节点进入对应模块'
      }}</span>
    </div>

    <!-- 分层架构图（真实实体，ECharts） -->
    <el-card v-if="viewMode === 'entities'">
      <el-empty v-if="!loading && !rawNodes.length" description="暂无架构数据" />
      <div v-else class="canvas-scroll">
        <GraphCanvas
          :nodes="chartNodes"
          :edges="chartEdges"
          :categories="categories"
          :graphic="bandGraphics"
          :height="`${contentHeight}px`"
          :width="`${CONTENT_W}px`"
          :loading="loading"
          :tooltip-formatter="nodeTooltip"
          show-legend
          @node-click="onNodeClick"
        />
      </div>
      <div class="edge-legend-tip">
        <span class="gl-item"><i class="gl-line gl-relation" />关系（绿实线）</span>
        <span class="gl-item"><i class="gl-line gl-transitive" />可传递（橙色虚线）</span>
        <span class="gl-item"><i class="gl-arrow">⇄</i>对称（双向箭头）</span>
        <span class="gl-item">· 尾缀＝约束公理，悬停关系边看含义</span>
      </div>
    </el-card>

    <!-- 四层语义架构全景（移植自 demo 架构全貌） -->
    <el-card v-else>
      <PanoramaGraph />
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import GraphCanvas from '../../components/GraphCanvas.vue'
import PanoramaGraph from './PanoramaGraph.vue'
import { getArchitectureOverview } from '../../api/architecture'
import { axiomSuffixOf, axiomStyleOf, axiomTooltipOf } from '../../utils/axiomEdge'

const router = useRouter()

// 视图切换：实体全景（ECharts 真实数据） / 分层架构（四层语义叙事图）
const viewMode = ref('entities')

// ---------- 节点类型样式 ----------
const TYPE_STYLE = {
  DOMAIN: { name: '业务域', color: '#409eff', size: 18 },
  CONCEPT: { name: '概念', color: '#67c23a', size: 13 },
  RULE: { name: '规则', color: '#e6a23c', size: 12 },
  METRIC: { name: '指标', color: '#722ed1', size: 12 },
  TABLE: { name: '物理表', color: '#13c2c2', size: 10 },
  DATASOURCE: { name: '数据源', color: '#f56c6c', size: 18 }
}
const categories = Object.entries(TYPE_STYLE).map(([key, t]) => ({
  name: t.name,
  itemStyle: { color: t.color }
}))

// ---------- 分层布局：左→右四层，层内按类型分列 ----------
const TOP = 70
const GAP_Y = 34
// 画布与内容坐标 1:1：canvas 宽高固定为内容宽高，并用角落锚点钉住 bbox
const CONTENT_W = 1120
const LAYERS = [
  {
    title: 'L1 本体层',
    x0: 0,
    width: 420,
    fill: 'rgba(64, 158, 255, 0.05)',
    subs: { DOMAIN: 50, CONCEPT: 240 }
  },
  {
    title: 'L2 规范层',
    x0: 420,
    width: 240,
    fill: 'rgba(230, 162, 60, 0.06)',
    subs: { RULE: 470, METRIC: 580 }
  },
  {
    title: 'L3 语义映射层',
    x0: 660,
    width: 240,
    fill: 'rgba(19, 194, 194, 0.06)',
    subs: { TABLE: 730 }
  },
  {
    title: 'L4 物理层',
    x0: 900,
    width: 220,
    fill: 'rgba(245, 108, 108, 0.05)',
    subs: { DATASOURCE: 990 }
  }
]
const EDGE_COLOR = {
  BELONG: '#b3d8ff',
  RELATION: '#95d475',
  MAPPING: '#8ae1e1',
  DS_TABLE: '#fab6b6',
  RULE_BIND: '#f5dab1',
  METRIC_BIND: '#d3adf7'
}

const rawNodes = ref([])
const rawEdges = ref([])
const loading = ref(false)
const keyword = ref('')
const onlyUnmapped = ref(false)

const mappedConceptCodes = computed(
  () =>
    new Set(
      rawNodes.value
        .filter((n) => n.type === 'CONCEPT' && (n.stats?.mappingCount ?? 0) > 0)
        .map((n) => n.code)
    )
)

const stats = computed(() => {
  const byType = (t) => rawNodes.value.filter((n) => n.type === t).length
  const conceptCount = byType('CONCEPT')
  const mapped = mappedConceptCodes.value.size
  return {
    conceptCount,
    relationCount: rawEdges.value.filter((e) => e.kind === 'RELATION').length,
    datasourceCount: byType('DATASOURCE'),
    tableCount: byType('TABLE'),
    coverage: conceptCount ? Math.round((mapped / conceptCount) * 100) : 0,
    ruleCount: byType('RULE'),
    metricCount: byType('METRIC')
  }
})
const coverageClass = computed(() =>
  stats.value.coverage >= 80 ? 'cov-good' : stats.value.coverage >= 50 ? 'cov-mid' : 'cov-bad'
)

// 各列纵向分布，列高取最大节点数
const positions = computed(() => {
  const grouped = {}
  for (const layer of LAYERS) {
    for (const type of Object.keys(layer.subs)) {
      grouped[type] = rawNodes.value.filter((n) => n.type === type)
    }
  }
  const pos = new Map()
  for (const layer of LAYERS) {
    for (const [type, x] of Object.entries(layer.subs)) {
      ;(grouped[type] || []).forEach((n, i) => pos.set(n.id, { x, y: TOP + i * GAP_Y }))
    }
  }
  return pos
})

const contentHeight = computed(() => {
  let maxCount = 0
  for (const layer of LAYERS) {
    for (const type of Object.keys(layer.subs)) {
      maxCount = Math.max(maxCount, rawNodes.value.filter((n) => n.type === type).length)
    }
  }
  return Math.max(TOP + maxCount * GAP_Y + 40, 400)
})

const bandGraphics = computed(() => {
  const els = []
  for (const layer of LAYERS) {
    els.push({
      type: 'rect',
      left: layer.x0 + 4,
      top: 8,
      shape: { width: layer.width - 8, height: contentHeight.value - 16, r: 6 },
      style: { fill: layer.fill },
      silent: true
    })
    els.push({
      type: 'text',
      left: layer.x0 + 18,
      top: 22,
      style: { text: layer.title, fontSize: 14, fontWeight: 600, fill: '#606266' },
      silent: true
    })
  }
  return els
})

// 节点明暗状态：搜索高亮 / 只看未映射
const nodeState = (n) => {
  const kw = keyword.value.trim().toLowerCase()
  const hit =
    kw && (n.name?.toLowerCase().includes(kw) || n.code?.toLowerCase().includes(kw))
  let opacity = 1
  let silent = false
  if (onlyUnmapped.value && n.type === 'CONCEPT' && mappedConceptCodes.value.has(n.code)) {
    opacity = 0.06
    silent = true
  } else if (kw) {
    opacity = hit ? 1 : 0.18
  }
  return { opacity, silent, hit }
}

const chartNodes = computed(() => {
  const list = rawNodes.value.map((n) => {
    const style = TYPE_STYLE[n.type] || {}
    const p = positions.value.get(n.id) || { x: 0, y: 0 }
    const { opacity, silent, hit } = nodeState(n)
    const unmapped = n.type === 'CONCEPT' && !mappedConceptCodes.value.has(n.code)
    const size =
      n.type === 'CONCEPT'
        ? style.size + Math.min(n.stats?.mappingCount ?? 0, 8)
        : style.size
    return {
      id: n.id,
      name: n.name,
      category: style.name,
      x: p.x,
      y: p.y,
      symbolSize: size || 12,
      silent,
      itemStyle: {
        color: unmapped ? '#d4d7de' : style.color,
        opacity,
        ...(unmapped
          ? { borderColor: '#a8abb2', borderWidth: 1, borderType: 'dashed' }
          : {}),
        ...(hit ? { borderColor: '#f56c6c', borderWidth: 2 } : {})
      },
      label: {
        color: unmapped ? '#a8abb2' : opacity < 0.5 ? '#c0c4cc' : '#303133',
        position: n.type === 'DATASOURCE' ? 'left' : 'right'
      },
      _raw: n
    }
  })
  // 锚点：把数据 bbox 钉在 [0,0,CONTENT_W,contentHeight]，保证与 graphic 色带像素对齐
  const anchor = (x, y) => ({
    id: `__anchor_${x}_${y}`,
    name: '',
    x,
    y,
    symbolSize: 0,
    silent: true,
    itemStyle: { opacity: 0 },
    label: { show: false },
    tooltip: { show: false },
    emphasis: { disabled: true }
  })
  list.push(anchor(0, 0), anchor(CONTENT_W, contentHeight.value))
  return list
})

const nodeOpacityById = computed(() => {
  const m = new Map()
  for (const n of chartNodes.value) m.set(n.id, n.itemStyle.opacity)
  return m
})

const chartEdges = computed(() =>
  rawEdges.value.map((e) => {
    const dim = Math.min(
      nodeOpacityById.value.get(e.source) ?? 1,
      nodeOpacityById.value.get(e.target) ?? 1
    )
    if (e.kind !== 'RELATION') {
      return {
        source: e.source,
        target: e.target,
        lineStyle: {
          color: EDGE_COLOR[e.kind] || '#c0c4cc',
          opacity: Math.max(dim * 0.9, 0.04),
          curveness: 0.05,
          width: e.kind === 'MAPPING' ? 0.8 : 1.2
        },
        _raw: e
      }
    }
    const style = axiomStyleOf(e, EDGE_COLOR.RELATION, { width: 1.2, curveness: 0.18 })
    return {
      source: e.source,
      target: e.target,
      label: { show: true, formatter: (e.label || '') + axiomSuffixOf(e), fontSize: 11, color: '#909399' },
      ...style,
      lineStyle: { ...style.lineStyle, opacity: Math.max(dim * 0.9, 0.04) },
      _raw: e
    }
  })
)

const nodeTooltip = (p) => {
  if (p.dataType === 'edge') {
    const e = p.data._raw
    const kindText =
      { BELONG: '归属', RELATION: '关系', MAPPING: '映射', DS_TABLE: '包含', RULE_BIND: '绑定规则', METRIC_BIND: '绑定指标' }[
        e.kind
      ] || e.kind
    let html = `<b>${kindText}</b>${e.label ? `：${e.label}` : ''}`
    if (e.kind === 'RELATION') {
      html += axiomSuffixOf(e)
      for (const l of axiomTooltipOf(e)) html += `<br/>${l}`
    }
    return html
  }
  const n = p.data._raw
  if (!n) return ''
  const lines = [`<b>${n.name}</b>（${n.code}）`, `类型：${TYPE_STYLE[n.type]?.name || n.type}`]
  if (n.type === 'CONCEPT') {
    const c = n.stats?.mappingCount ?? 0
    lines.push(`业务域：${n.domainCode || '-'}`)
    lines.push(c > 0 ? `已映射 ${c} 张物理表` : '<span style="color:#e6a23c">未映射任何物理表</span>')
  }
  if (n.type === 'DOMAIN') {
    const cnt = rawEdges.value.filter((e) => e.kind === 'BELONG' && e.source === n.id).length
    lines.push(`包含概念：${cnt} 个`)
  }
  if (n.type === 'TABLE') {
    const ds = rawEdges.value.find((e) => e.kind === 'DS_TABLE' && e.target === n.id)
    const dsNode = ds && rawNodes.value.find((x) => x.id === ds.source)
    if (dsNode) lines.push(`所属数据源：${dsNode.name}`)
    const mp = rawEdges.value.filter((e) => e.kind === 'MAPPING' && e.target === n.id)
    if (mp.length) {
      const names = mp
        .map((e) => rawNodes.value.find((x) => x.id === e.source)?.name)
        .filter(Boolean)
      lines.push(`映射概念：${names.join('、')}`)
    }
  }
  if (n.type === 'DATASOURCE') {
    const cnt = rawEdges.value.filter((e) => e.kind === 'DS_TABLE' && e.source === n.id).length
    lines.push(`物理表：${cnt} 张`)
  }
  return lines.join('<br/>')
}

// ---------- 点击跳转 ----------
const onNodeClick = (node) => {
  const n = node._raw
  if (!n) return
  switch (n.type) {
    case 'CONCEPT':
      router.push({ path: '/ontology', query: { concept: n.code } })
      break
    case 'DOMAIN':
      router.push({ path: '/ontology', query: { domain: n.code } })
      break
    case 'RULE':
      router.push({ path: '/ontology', query: { tab: 'rule' } })
      break
    case 'METRIC':
      router.push('/glossary')
      break
    case 'DATASOURCE':
      router.push('/datasource')
      break
    case 'TABLE': {
      const dsCode = n.id.startsWith('T:') ? n.id.slice(2).split('.')[0] : ''
      router.push({ path: '/datasource', query: { ds: dsCode, table: n.code } })
      break
    }
  }
}

onMounted(async () => {
  loading.value = true
  try {
    const data = await getArchitectureOverview()
    rawNodes.value = data.nodes || []
    rawEdges.value = data.edges || []
  } catch {
    rawNodes.value = []
    rawEdges.value = []
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.stats-card {
  margin-bottom: 12px;
}

.stats-row {
  display: flex;
  gap: 40px;
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: #303133;
}

.stat-value.cov-good {
  color: #67c23a;
}

.stat-value.cov-mid {
  color: #e6a23c;
}

.stat-value.cov-bad {
  color: #f56c6c;
}

.stat-label {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.toolbar {
  display: flex;
  align-items: center;
  margin-bottom: 12px;
}

.unmapped-switch {
  margin-left: 16px;
}

.switch-label {
  margin-left: 8px;
  font-size: 13px;
  color: #606266;
}

.toolbar-tip {
  margin-left: auto;
  font-size: 12px;
  color: #909399;
}

.canvas-scroll {
  overflow: auto;
  max-height: calc(100vh - 280px);
}

.edge-legend-tip {
  display: flex;
  gap: 20px;
  align-items: center;
  padding: 8px 4px 0;
  font-size: 12px;
  color: #909399;
}

.edge-legend-tip .gl-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.edge-legend-tip .gl-line {
  width: 20px;
  display: inline-block;
}

.edge-legend-tip .gl-line.gl-relation {
  border-top: 2px solid #95d475;
}

.edge-legend-tip .gl-line.gl-transitive {
  border-top: 2px dashed #e6a23c;
}

.edge-legend-tip .gl-arrow {
  font-style: normal;
  color: #606266;
  font-weight: 700;
}
</style>
