<template>
  <div class="simulation-page">
    <el-alert type="info" :closable="false" show-icon class="sim-note"
      title="这里动的是演示数据,不是真实库。推演完成后点「恢复演示数据」即回到种子状态。" />

    <el-card class="sim-card">
      <template #header><b>动手改</b></template>
      <el-form inline>
        <el-form-item label="场景">
          <el-select v-model="scenarioKey" style="width: 220px" @change="applyDefaults">
            <el-option v-for="s in scenarios" :key="s.key" :value="s.key" :label="s.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="药品编码">
          <el-input v-model="form.drugCode" style="width: 130px" />
        </el-form-item>
        <el-form-item label="库存改为">
          <el-input-number v-model="form.quantity" :min="0" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="running || starting" @click="start">开始推演</el-button>
          <el-button :disabled="running" @click="restore">恢复演示数据</el-button>
        </el-form-item>
      </el-form>
      <div class="scen-desc" v-if="currentScenario">{{ currentScenario.description }}</div>
    </el-card>

    <el-card class="sim-card" v-if="running || status">
      <template #header>
        <b>波及链</b>
        <span class="head-status">{{ running ? '推演中…' : statusLabel }}</span>
      </template>
      <el-progress v-if="running" :percentage="100" :indeterminate="true" :duration="2" :show-text="false" />
      <el-alert v-if="errorMsg" type="error" :title="errorMsg" :closable="false" />
      <SimulationTimeline :steps="steps" :running="running" />
    </el-card>

    <el-card class="sim-card" v-if="diffItems.length">
      <template #header><b>前后对比</b></template>
      <div class="diff-item" v-for="(item, i) in diffItems" :key="i">{{ item }}</div>
    </el-card>

    <el-card class="sim-card" v-if="recent.length">
      <template #header><b>最近推演</b></template>
      <el-table :data="recent" size="small" @row-click="review" highlight-current-row>
        <el-table-column prop="runId" label="#" width="80" />
        <el-table-column label="场景" width="140">
          <template #default="{ row }">{{ scenarioName(row.scenario) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="STATUS_TAG[row.status] || 'info'">{{ STATUS_LABEL[row.status] || row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="起跑时间" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import SimulationTimeline from './SimulationTimeline.vue'
import { simReset, simRuns, simScenarios, simStart, simStatus } from '../../api/simulation'

const scenarios = ref([])
const scenarioKey = ref('')
const form = ref({ drugCode: 'D006', quantity: 0 })
const running = ref(false)
const starting = ref(false)
const status = ref(null)
const recent = ref([])
let timer = null

const STATUS_LABEL = { QUEUED: '排队中', RUNNING: '推演中', DONE: '已完成', FAILED: '已失败' }
const STATUS_TAG = { QUEUED: 'info', RUNNING: 'primary', DONE: 'success', FAILED: 'danger' }
const scenarioName = (key) => {
  const hit = scenarios.value.find(s => s.key === key)
  return (hit && hit.name) || key
}

const currentScenario = computed(() => scenarios.value.find(s => s.key === scenarioKey.value))
const payload = computed(() => (status.value && status.value.payload) || null)
const steps = computed(() => (payload.value && payload.value.steps) || [])
const diffItems = computed(() => (payload.value && payload.value.diff && payload.value.diff.items) || [])
const errorMsg = computed(() => (status.value && status.value.errorMsg) || '')
const statusLabel = computed(() => (status.value ? (STATUS_LABEL[status.value.status] || status.value.status) : ''))

const loadScenarios = async () => {
  const data = await simScenarios()
  scenarios.value = Array.isArray(data) ? data : []
  if (scenarios.value.length) {
    scenarioKey.value = scenarios.value[0].key
    applyDefaults()
  }
}
const applyDefaults = () => {
  const s = currentScenario.value
  if (s && s.paramDefaults) {
    form.value.drugCode = s.paramDefaults.drugCode || 'D006'
    form.value.quantity = Number(s.paramDefaults.quantity ?? 0)
  }
}
const stopPolling = () => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}
const poll = async () => {
  if (!status.value) return
  try {
    const data = await simStatus(status.value.runId)
    if (['DONE', 'FAILED'].includes(data.status)) {
      status.value = data
      running.value = false
      stopPolling()
      loadRecent()
    }
  } catch (e) {
    // 单次轮询失败忽略,下轮再试
  }
}
const start = async () => {
  if (running.value || starting.value) return
  starting.value = true
  try {
    // 起跑失败由 request 拦截器统一弹错,这里吞掉 rejection 只为消控制台噪音
    const data = await simStart(scenarioKey.value, { drugCode: form.value.drugCode, quantity: String(form.value.quantity) }).catch(() => {})
    if (!data) return
    status.value = data
    running.value = true
    stopPolling()
    timer = setInterval(poll, 2000)
  } finally {
    starting.value = false
  }
}
const restore = async () => {
  await simReset()
  ElMessage.success('演示数据已恢复')
}
const review = async (row) => {
  stopPolling()
  running.value = false
  status.value = await simStatus(row.runId)
}
const loadRecent = async () => {
  const data = await simRuns()
  recent.value = Array.isArray(data) ? data : []
}
onMounted(() => {
  loadScenarios()
  loadRecent()
})
onUnmounted(stopPolling)
</script>

<style scoped>
.simulation-page { display: flex; flex-direction: column; gap: 16px; }
.sim-note { border-radius: 8px; }
.sim-card { border-radius: 10px; }
.head-status { margin-left: 12px; color: #909399; font-size: 13px; }
.scen-desc { color: #909399; font-size: 13px; }
.diff-item { padding: 6px 0; border-bottom: 1px dashed var(--el-border-color-lighter); font-size: 14px; }
.diff-item:last-child { border-bottom: none; }
</style>
