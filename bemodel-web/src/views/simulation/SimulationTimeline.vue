<template>
  <div class="sim-timeline" :class="{ breath: running }">
    <div v-for="(layer, i) in layers" :key="i" class="tl-layer">
      <div class="tl-level">第 {{ i + 1 }} 层</div>
      <div class="tl-nodes">
        <div v-for="step in layer" :key="step.concept"
             class="tl-node" :class="nodeClass(step)" @click="toggle(step)">
          <div class="node-dot"></div>
          <div class="node-body">
            <div class="node-title">
              {{ step.conceptName || step.concept }}
              <el-tag v-if="step.status === 'BLOCKED_GAP'" type="warning" size="small">到此为止</el-tag>
              <el-tag v-else-if="step.status === 'EMPTY'" type="info" size="small">查无实例</el-tag>
              <el-tag v-else-if="lighted" type="success" size="small">{{ step.hits }} 条</el-tag>
            </div>
            <div class="node-chain" v-if="step.evidence && step.evidence.relationChain">{{ step.evidence.relationChain }}</div>
            <div class="node-chain gap-note" v-if="step.status === 'BLOCKED_GAP' && step.evidence && step.evidence.note">{{ step.evidence.note }}</div>
            <div v-if="open[step.concept]" class="node-detail">
              <div v-for="(ref, j) in (step.evidence && step.evidence.mappingRefs) || []" :key="'m' + j" class="ev-line">依据: {{ ref }}</div>
              <div class="ev-line" v-if="step.evidence && step.evidence.keyUsed">追踪: {{ step.evidence.keyUsed }}</div>
              <div class="ev-line" v-if="step.evidence && step.evidence.statusNote">{{ step.evidence.statusNote }}</div>
              <code class="ev-sql" v-if="step.sqlSummary">{{ step.sqlSummary }}</code>
              <el-table v-if="step.sample && step.sample.length" :data="step.sample" size="small" class="ev-table">
                <el-table-column v-for="col in sampleCols(step.sample)" :key="col" :prop="col" :label="col"
                                 :width="colWidth(step.sample, col)" show-overflow-tooltip />
              </el-table>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  steps: { type: Array, default: () => [] },
  running: { type: Boolean, default: false }
})
const open = ref({})
const lighted = computed(() => !props.running && props.steps.length > 0)
const layers = computed(() => {
  const byLevel = new Map()
  props.steps.forEach(s => {
    if (!byLevel.has(s.level)) byLevel.set(s.level, [])
    byLevel.get(s.level).push(s)
  })
  return [...byLevel.keys()].sort((a, b) => a - b).map(k => byLevel.get(k))
})
watch(() => props.running, v => { if (v) open.value = {} })
const toggle = (step) => { open.value[step.concept] = !open.value[step.concept] }
const nodeClass = (step) => ({
  done: lighted.value && step.status !== 'BLOCKED_GAP',
  gap: lighted.value && step.status === 'BLOCKED_GAP',
  pending: !lighted.value,
  open: open.value[step.concept]
})
const sampleCols = (sample) => Object.keys(sample[0] || {}).slice(0, 6)
const colWidth = (sample, col) => {
  const max = Math.max(col.length, ...sample.map(r => String(r[col] ?? '').length))
  return Math.min(220, Math.max(90, max * 9))
}
</script>

<style scoped>
.sim-timeline { display: flex; flex-direction: column; gap: 14px; }
.tl-layer { display: flex; gap: 12px; align-items: flex-start; }
.tl-level { min-width: 58px; color: #909399; font-size: 12px; padding-top: 6px; }
.tl-nodes { display: flex; flex-direction: column; gap: 10px; flex: 1; }
.tl-node { display: flex; gap: 10px; padding: 10px 12px; border: 1px solid var(--el-border-color-light);
  border-radius: 8px; cursor: pointer; background: var(--el-fill-color-blank); }
.tl-node.pending { opacity: .55; }
.tl-node .node-dot { width: 12px; height: 12px; border-radius: 50%; margin-top: 4px;
  border: 2px solid #c0c4cc; flex-shrink: 0; }
.tl-node.done { border-color: #b3e19d; }
.tl-node.done .node-dot { border-color: #67c23a; background: #67c23a; }
.tl-node.gap { border-color: #f3d19e; }
.tl-node.gap .node-dot { border-color: #e6a23c; background: #fff; }
.tl-node.open { background: var(--el-fill-color-light); }
.node-title { font-weight: 600; display: flex; gap: 8px; align-items: center; }
.node-chain { color: #909399; font-size: 12px; margin-top: 2px; }
.gap-note { color: #e6a23c; }
.node-detail { margin-top: 8px; display: flex; flex-direction: column; gap: 4px; }
.ev-line { font-size: 12px; color: #606266; }
.ev-sql { font-size: 12px; background: var(--el-fill-color); padding: 4px 8px; border-radius: 4px;
  word-break: break-all; }
.ev-table { margin-top: 4px; }
.sim-timeline.breath .tl-node.pending .node-dot { animation: simBreath 1.6s ease-in-out infinite; }
@keyframes simBreath { 0%, 100% { opacity: .35; } 50% { opacity: 1; } }
</style>
