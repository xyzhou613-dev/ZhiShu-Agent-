<template>
  <section class="compute-rule-details">
    <el-alert title="规则检查演练" type="info" :closable="false"
      description="本页只检查输入和分支，不批准申请、不预留资源、不发送通知或执行节点操作。正式运行需接入服务端证据、发布快照和流程推进器。" />
    <el-descriptions :column="1" border size="small">
      <el-descriptions-item label="业务基线">{{ definition.policyVersion }}</el-descriptions-item>
      <el-descriptions-item label="阶段">{{ stageName }}</el-descriptions-item>
      <el-descriptions-item label="处理函数">{{ binding?.implementation || definition.handler }}</el-descriptions-item>
      <el-descriptions-item label="实现版本">{{ definition.evaluatorVersion }}</el-descriptions-item>
      <el-descriptions-item label="流程位置">{{ definition.workflowSteps?.join(' → ') }}</el-descriptions-item>
      <el-descriptions-item label="失败去向">{{ definition.failureRoute }}</el-descriptions-item>
      <el-descriptions-item label="失败原因码">{{ definition.failureReasons?.join('、') }}</el-descriptions-item>
      <el-descriptions-item label="输出">{{ definition.outputs?.join('、') }}</el-descriptions-item>
      <el-descriptions-item label="参数"><pre>{{ JSON.stringify(definition.parameters, null, 2) }}</pre></el-descriptions-item>
    </el-descriptions>
    <h4>需要哪些输入</h4>
    <dl v-for="key in definition.inputs || []" :key="key">
      <dt>{{ key }}</dt><dd>{{ definition.inputNotes?.[key] || '见输入契约' }}</dd>
    </dl>
    <h4>只读演练</h4>
    <p>样例为教学快照，不是当前 A800 观测。输入缺失或类型错误会返回 ERROR，不会按零或空闲处理。R-06 的 PASS 表示收尾分支判定完成，应同时查看 outcome。</p>
    <el-input v-model="factsText" type="textarea" :rows="12" aria-label="规则演练输入 JSON" />
    <div class="preview-actions">
      <el-button size="small" @click="resetSample">恢复样例</el-button>
      <el-button v-if="canPreview" size="small" type="primary" :loading="checking"
        :disabled="!binding?.checkAvailable" @click="runPreview">检查这些条件</el-button>
      <span v-else>演练需编辑员或管理员权限</span>
    </div>
    <el-alert v-if="binding && !binding.checkAvailable" type="warning" :closable="false" :title="binding.message" />
    <template v-if="result">
      <el-tag :type="result.result === 'PASS' ? 'success' : result.result === 'ERROR' ? 'danger' : 'warning'">
        {{ result.result }} · {{ result.reasonCode }}
      </el-tag>
      <p>本次规则版本 v{{ result.ruleVersion }}；不会触发业务操作。</p>
      <pre class="result">{{ JSON.stringify(result.output, null, 2) }}</pre>
    </template>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../../../store/user'
import { listComputeRules, previewComputeRule } from '../../../api/compute'

const props = defineProps({ rule: { type: Object, required: true } })
const user = useUserStore()
const definition = computed(() => {
  try { return JSON.parse(props.rule.exprJson || '{}') } catch { return {} }
})
const stageName = computed(() => ({ ADMISSION: '准入', PLANNING: '方案', SUBMISSION: '提交', EXECUTION: '执行', CLOSURE: '收尾', GLOBAL: '全程' }[definition.value.stage] || definition.value.stage))
const canPreview = computed(() => ['ADMIN', 'EDITOR'].includes(user.user?.role))
const factsText = ref('')
const binding = ref(null)
const checking = ref(false)
const result = ref(null)
const resetSample = () => {
  factsText.value = JSON.stringify(definition.value.sampleFacts || {}, null, 2)
  result.value = null
}
watch(() => [props.rule.ruleCode, props.rule.version, props.rule.exprJson], async (_, __, onCleanup) => {
  let cancelled = false
  onCleanup(() => { cancelled = true })
  binding.value = null
  resetSample()
  try {
    const catalog = await listComputeRules()
    if (!cancelled) binding.value = catalog.find(item => item.ruleCode === props.rule.ruleCode) || null
  } catch { /* API wrapper displays errors. */ }
}, { immediate: true })

const runPreview = async () => {
  let facts
  try { facts = JSON.parse(factsText.value) } catch { ElMessage.warning('请输入合法 JSON'); return }
  result.value = null
  checking.value = true
  try { result.value = await previewComputeRule(props.rule.ruleCode, props.rule.version, facts) }
  finally { checking.value = false }
}
</script>

<style scoped>
.compute-rule-details { display: grid; gap: 12px; margin: 16px 0; }
h4, p, dl { margin: 0; }
p, dd { color: #606266; font-size: 13px; line-height: 1.6; }
dt { font-family: monospace; font-size: 13px; overflow-wrap: anywhere; }
dd { margin-left: 12px; }
pre { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; }
.result { background: #f5f7fa; padding: 12px; max-height: 360px; overflow: auto; }
.preview-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
</style>
