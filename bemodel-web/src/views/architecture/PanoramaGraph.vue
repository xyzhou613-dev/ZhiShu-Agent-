<template>
  <div class="panorama">
    <div class="pano-canvas">
      <div class="pano-badge">
        <strong>BeModel</strong> · Ontology-Driven AI Platform
        <div class="pano-badge-tip">点击节点进入对应模块</div>
      </div>

      <svg viewBox="0 0 1100 620" class="pano-svg">
        <defs>
          <marker
            v-for="m in markers"
            :key="m.id"
            :id="m.id"
            viewBox="0 0 10 10"
            refX="9"
            refY="5"
            markerWidth="5"
            markerHeight="5"
            orient="auto-start-reverse"
          >
            <path d="M 0 0 L 10 5 L 0 10 z" :fill="m.color" />
          </marker>
          <linearGradient
            v-for="g in gradients"
            :key="g.id"
            :id="g.id"
            x1="0%"
            y1="0%"
            x2="0%"
            y2="100%"
          >
            <stop offset="0%" :style="`stop-color:#ffffff`" />
            <stop offset="100%" :style="`stop-color:${g.to}`" />
          </linearGradient>
        </defs>

        <!-- 层标签与分隔线 -->
        <template v-for="layer in LAYERS" :key="layer.key">
          <text :y="layer.labelY" x="24" class="pano-layer-label">{{ layer.label }}</text>
          <line
            :y1="layer.dividerY"
            :y2="layer.dividerY"
            x1="16"
            x2="1084"
            class="pano-divider"
          />
        </template>

        <!-- 连线 -->
        <path
          v-for="(p, i) in LINKS"
          :key="i"
          :d="p.d"
          :stroke="p.stroke"
          :stroke-dasharray="p.dash"
          :marker-end="`url(#${p.marker})`"
          class="pano-link"
        />

        <!-- 节点 -->
        <g
          v-for="node in allNodes"
          :key="node.title"
          class="pano-node"
          @click="go(node.route)"
          @mousemove="onMove($event, node)"
          @mouseleave="hideTip"
        >
          <rect
            :x="node.x"
            :y="node.y"
            :width="node.w"
            height="48"
            rx="8"
            :fill="`url(#${node.grad})`"
            :stroke="node.stroke"
            stroke-width="1.5"
          />
          <text :x="node.x + 16" :y="node.y + 20" class="pano-node-title">{{ node.title }}</text>
          <text :x="node.x + 16" :y="node.y + 36" class="pano-node-en">{{ node.en }}</text>
        </g>
      </svg>

      <!-- hover 提示 -->
      <div v-show="tip.show" class="pano-tooltip" :style="{ left: `${tip.x}px`, top: `${tip.y}px` }">
        <div class="pano-tip-title">{{ tip.title }}</div>
        <div class="pano-tip-desc">{{ tip.desc }}</div>
        <div class="pano-tip-tag">{{ tip.tag }}</div>
      </div>

      <!-- 图例 -->
      <div class="pano-legend">
        <div v-for="item in legend" :key="item.label" class="pano-legend-item">
          <span class="pano-legend-swatch" :style="item.style"></span>{{ item.label }}
        </div>
      </div>
    </div>

    <!-- 语义层核心价值 -->
    <div class="value-block">
      <div class="value-title">语义层核心价值</div>
      <div class="value-subtitle">本体论 + AI 驱动企业转型 —— 从「数据治理」到「业务智能化」</div>
      <div class="value-grid">
        <div v-for="v in VALUES" :key="v.no" class="value-card">
          <div class="value-no">{{ v.no }}</div>
          <div class="value-name">{{ v.name }}</div>
          <div class="value-desc">{{ v.desc }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

// ---------- 四层静态结构（移植自 demo/bemodel-demo.html 架构全景） ----------
// route 已映射到本平台真实页面；tip 为「标题|描述|场景」
const LAYERS = [
  {
    key: 'app',
    label: 'Application Layer  |  业务应用层',
    labelY: 38,
    dividerY: 56,
    grad: 'appGrad',
    stroke: '#2d8a7e',
    nodes: [
      { x: 60, y: 72, w: 180, title: '医嘱闭环', en: 'Order Fulfillment', tip: '医嘱全流程闭环|门诊/住院从开立到结算的端到端语义贯通|场景：医疗业务', route: '/flow' },
      { x: 270, y: 72, w: 180, title: '临床决策支持', en: 'Clinical Decision Support', tip: '临床决策支持|基于本体推理的临床辅助决策|场景：AI辅助诊疗', route: '/clinical' },
      { x: 480, y: 72, w: 180, title: '处方审核', en: 'Prescription Review', tip: '处方审核|基于规则引擎的处方自动审核|场景：合理用药', route: '/ontology?tab=rules' },
      { x: 690, y: 72, w: 180, title: 'PACS接入', en: 'Imaging Integration', tip: 'PACS接入|DICOM SR结构化报告接入与语义关联|场景：影像语义化', route: '/datasource' },
      { x: 900, y: 72, w: 140, title: '质量管控', en: 'Quality Control', tip: '质量管控|质控规则可视化配置与执行|场景：质量管理', route: '/gov' }
    ]
  },
  {
    key: 'reason',
    label: 'Reasoning Layer  |  推理引擎层',
    labelY: 180,
    dividerY: 200,
    grad: 'reasonGrad',
    stroke: '#3b82f6',
    nodes: [
      { x: 120, y: 220, w: 210, title: '规则推理引擎', en: 'Rule Reasoning Engine', tip: '规则推理引擎|规则可视化配置与执行，新规则免开发|核心能力：规则计算', route: '/ontology?tab=rules' },
      { x: 440, y: 220, w: 220, title: '本体自检 · 发布门禁', en: 'Ontology Self-Check Gate', tip: '本体自检引擎|发布前 7 类公理缺陷检查，BLOCKER 拒绝发布|核心能力：一致性检查', route: '/ontology?tab=releases' },
      { x: 770, y: 220, w: 210, title: 'SHACL 校验引擎', en: 'Shape Constraint Validator', tip: 'SHACL校验引擎|形状约束语言校验，与规则引擎双引擎互证|核心能力：质量验证', route: '/gov' }
    ]
  },
  {
    key: 'data',
    label: 'Data Mapping Layer  |  数据映射层',
    labelY: 325,
    dividerY: 345,
    grad: 'dataGrad',
    stroke: '#f59e0b',
    nodes: [
      { x: 170, y: 365, w: 180, title: '语义映射器', en: 'Semantic Mapper', tip: '语义映射器|数据到本体实例的语义映射|核心能力：模式匹配', route: '/datasource' },
      { x: 460, y: 365, w: 180, title: '数据接入适配器', en: 'Data Source Adapters', tip: '数据接入适配器|HIS/LIS/PACS等多源异构系统接入|核心能力：多源接入', route: '/datasource' },
      { x: 750, y: 365, w: 180, title: '知识抽取流水线', en: 'Knowledge Pipeline', tip: '知识抽取流水线|ETL+语义标注，业务数据转知识实例|核心能力：知识生成', route: '/link' }
    ]
  },
  {
    key: 'onto',
    label: 'Ontology Layer  |  本体层',
    labelY: 470,
    dividerY: 490,
    grad: 'ontoGrad',
    stroke: '#10b981',
    nodes: [
      { x: 60, y: 508, w: 160, title: '顶层本体', en: 'Top-Level Ontology', tip: '顶层本体|患者/诊疗/药品/检查/疾病等类体系|语义基础：TBox', route: '/ontology' },
      { x: 250, y: 508, w: 160, title: '类与公理', en: 'Classes & Axioms', tip: '类与公理|OWL类公理、对象属性、数据属性形式化定义|语义基础：Axioms', route: '/ontology?tab=axioms' },
      { x: 440, y: 508, w: 160, title: '实例与断言', en: 'TBox + ABox', tip: '实例与断言|TBox术语层 + ABox断言层|语义基础：ABox', route: '/ontology?tab=instances' },
      { x: 630, y: 508, w: 160, title: '标准术语映射', en: 'Standard Terminology', tip: '标准术语映射|统一术语与指标口径，一处定义处处复用|语义基础：互操作', route: '/glossary' },
      { x: 820, y: 508, w: 130, title: '三元组存储', en: 'Triple Store', tip: '三元组存储|RDF图存储与导出，实例数据可回溯|语义基础：Storage', route: '/ontology?tab=instances' }
    ]
  }
].map((l) => ({ ...l, nodes: l.nodes.map((n) => ({ ...n, grad: l.grad, stroke: l.stroke })) }))

const allNodes = LAYERS.flatMap((l) => l.nodes)

// ---------- 连线（自上而下：应用→推理实线，推理→映射/映射→本体虚线） ----------
const LINKS = [
  { d: 'M150,120 L225,220', stroke: '#2d8a7e', marker: 'mkApp' },
  { d: 'M360,120 L550,220', stroke: '#2d8a7e', marker: 'mkApp' },
  { d: 'M570,120 L550,220', stroke: '#2d8a7e', marker: 'mkApp' },
  { d: 'M780,120 L875,220', stroke: '#2d8a7e', marker: 'mkApp' },
  { d: 'M970,120 L875,220', stroke: '#2d8a7e', marker: 'mkApp' },
  { d: 'M225,268 L260,365', stroke: '#94a3b8', marker: 'mkReason', dash: '4,3' },
  { d: 'M550,268 L550,365', stroke: '#94a3b8', marker: 'mkReason', dash: '4,3' },
  { d: 'M875,268 L840,365', stroke: '#94a3b8', marker: 'mkReason', dash: '4,3' },
  { d: 'M260,413 L140,508', stroke: '#94a3b8', marker: 'mkData', dash: '4,3' },
  { d: 'M550,413 L330,508', stroke: '#94a3b8', marker: 'mkData', dash: '4,3' },
  { d: 'M550,413 L520,508', stroke: '#94a3b8', marker: 'mkData', dash: '4,3' },
  { d: 'M840,413 L710,508', stroke: '#94a3b8', marker: 'mkData', dash: '4,3' },
  { d: 'M840,413 L885,508', stroke: '#94a3b8', marker: 'mkData', dash: '4,3' }
]

const markers = [
  { id: 'mkApp', color: '#2d8a7e' },
  { id: 'mkReason', color: '#3b82f6' },
  { id: 'mkData', color: '#f59e0b' }
]
const gradients = [
  { id: 'appGrad', to: '#f0fdf4' },
  { id: 'reasonGrad', to: '#eff6ff' },
  { id: 'dataGrad', to: '#fffbeb' },
  { id: 'ontoGrad', to: '#ecfdf5' }
]

// ---------- 图例 ----------
const legend = [
  { label: '业务应用层 · 语义落地', style: { background: '#ffffff', border: '1.5px solid #2d8a7e' } },
  { label: '推理引擎层 · 语义计算', style: { background: '#eff6ff', border: '1.5px solid #3b82f6' } },
  { label: '数据映射层 · 语义贯通', style: { background: '#fffbeb', border: '1.5px solid #f59e0b' } },
  { label: '本体层 · 语义核心', style: { background: '#ecfdf5', border: '1.5px solid #10b981' } }
]

// ---------- 语义层核心价值 ----------
const VALUES = [
  { no: '01 / 数据治理', name: '业务概念统一', desc: '全公司一套本体定义，核心业务概念在所有产品线中语义一致。' },
  { no: '02 / 业务流程', name: '跨系统语义贯通', desc: '通过对象属性建立语义关系，数据不再是孤立的表，而是可推理的知识网络。' },
  { no: '03 / 研发效率', name: '规则免开发', desc: '校验规则编译为探针模板，质控规则配置即用，新业务规则无需开发排期。' },
  { no: '04 / AI赋能', name: '推理增强理解', desc: '本体提供结构化领域知识骨架，大模型基于语义层推理，准确率和可解释性双提升。' }
]

// ---------- 交互 ----------
const tip = reactive({ show: false, x: 0, y: 0, title: '', desc: '', tag: '' })

const onMove = (e, node) => {
  const [title, desc, tag] = node.tip.split('|')
  const canvas = e.currentTarget.closest('.pano-canvas').getBoundingClientRect()
  tip.title = title
  tip.desc = desc
  tip.tag = tag
  // 相对画布定位 + 偏移，靠右时翻到鼠标左侧
  const x = e.clientX - canvas.left + 14
  tip.x = x + 260 > canvas.width ? x - 288 : x
  tip.y = e.clientY - canvas.top + 14
  tip.show = true
}

const hideTip = () => {
  tip.show = false
}

const go = (route) => {
  router.push(route)
}
</script>

<style scoped>
.pano-canvas {
  position: relative;
  width: 100%;
  background: linear-gradient(180deg, var(--main-bg) 0%, var(--el-bg-color-page) 100%);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.pano-svg {
  display: block;
  width: 100%;
  height: auto;
}

.pano-badge {
  position: absolute;
  top: 16px;
  left: 16px;
  z-index: 2;
  background: var(--card-bg);
  padding: 10px 16px;
  border-radius: 8px;
  border: 1px solid var(--border-color);
  box-shadow: var(--shadow-sm);
  font-size: 12px;
  color: var(--text-secondary);
}

.pano-badge strong {
  color: var(--primary-dark);
}

.pano-badge-tip {
  margin-top: 4px;
  font-size: 11px;
  color: var(--text-muted);
}

.pano-layer-label {
  font-size: 10px;
  font-weight: 700;
  fill: #475569;
  letter-spacing: 1.5px;
}

.pano-divider {
  stroke: var(--el-border-color-lighter);
  stroke-width: 1;
  stroke-dasharray: 5, 4;
}

.pano-link {
  stroke-width: 2;
  fill: none;
}

.pano-node {
  cursor: pointer;
}

.pano-node rect {
  transition: filter var(--transition), stroke-width 0.15s;
}

.pano-node:hover rect {
  stroke-width: 2.5;
  filter: drop-shadow(0 4px 10px rgba(45, 138, 126, 0.25));
}

.pano-node-title {
  font-size: 13px;
  font-weight: 600;
  fill: var(--text-primary);
}

.pano-node-en {
  font-size: 10px;
  fill: var(--text-secondary);
}

.pano-tooltip {
  position: absolute;
  z-index: 10;
  max-width: 260px;
  padding: 10px 14px;
  border-radius: 6px;
  background: rgba(30, 41, 59, 0.96);
  color: #f1f5f9;
  font-size: 12px;
  line-height: 1.6;
  pointer-events: none;
}

.pano-tip-title {
  font-weight: 600;
  margin-bottom: 2px;
}

.pano-tip-tag {
  margin-top: 4px;
  color: #5eead4;
  font-size: 11px;
}

.pano-legend {
  position: absolute;
  bottom: 16px;
  right: 16px;
  background: rgba(255, 255, 255, 0.95);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 12px 16px;
  font-size: 11px;
  box-shadow: var(--shadow-sm);
}

.pano-legend-item {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-secondary);
  margin-bottom: 5px;
}

.pano-legend-item:last-child {
  margin-bottom: 0;
}

.pano-legend-swatch {
  width: 12px;
  height: 12px;
  border-radius: 3px;
  display: inline-block;
}

.value-block {
  margin-top: 24px;
}

.value-title {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 4px;
}

.value-subtitle {
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 16px;
}

.value-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 14px;
}

.value-card {
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: var(--radius);
  padding: 18px;
}

.value-no {
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 1px;
  color: var(--primary);
  margin-bottom: 8px;
}

.value-name {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 6px;
}

.value-desc {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.6;
}
</style>
