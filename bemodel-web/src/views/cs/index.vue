<template>
  <div class="page">
    <!-- 问一问：不懂系统也能直接用 -->
    <el-card class="ask-card">
      <div class="ask-intro">
        <b>这里做什么：</b>患者来电投诉（如「检验取消了怎么还收费」）→ 点左侧工单，AI
        自动跨系统排查出结论、证据和影响面 → 确认后一键退费办结。也可以直接在下面提问：
      </div>
      <div class="ask-row">
        <el-input
          v-model="question"
          placeholder="向 AI 客服提问，例如：检验取消了怎么还收费？"
          clearable
          @keyup.enter="doAsk()"
        >
          <template #append>
            <el-button type="primary" :loading="asking" @click="doAsk()">提问</el-button>
          </template>
        </el-input>
      </div>
      <div class="ask-chips">
        <span class="ask-chips-label">试试：</span>
        <el-link
          v-for="q in quickQuestions"
          :key="q"
          type="primary"
          :underline="false"
          class="ask-chip"
          @click="doAsk(q)"
        >{{ q }}</el-link>
      </div>
      <div v-if="askAnswer" class="ask-answer">
        <div class="ask-answer-head">
          <el-tag size="small" type="success" effect="plain">{{ askAnswer.intent }}</el-tag>
          <el-tooltip
            v-if="askAnswer.router"
            :content="{ RULE: '关键词规则直接命中，答案来自业务库实时查询', LLM: '问题由 AI 归类到该能力，答案仍来自业务库实时查询（AI 只负责路由，不生成内容）', SEMANTIC: 'AI 理解问题后基于本体语义层（概念→映射→物理表）生成查询并实时执行，数字来自业务库', REFERRAL: '未命中本场景能力，已给出转到另一场景的入口（带原问题跳转）', NONE: '未命中任何能力，展示能力菜单' }[askAnswer.router]"
            placement="top"
          >
            <el-tag size="small" :type="{ LLM: 'warning', SEMANTIC: 'success', REFERRAL: 'warning' }[askAnswer.router] || 'info'" effect="plain">
              {{ { RULE: '规则路由', LLM: 'AI 归类', SEMANTIC: '语义查询', REFERRAL: '场景转介', NONE: '未命中' }[askAnswer.router] }}
            </el-tag>
          </el-tooltip>
          <span class="ask-question">问：{{ askAnswer.question }}</span>
        </div>
        <div class="ask-text">{{ askAnswer.answer }}</div>
        <div v-if="askAnswer.evidence?.length" class="ask-evidence">
          <span v-for="(e, i) in askAnswer.evidence" :key="i" class="ask-evidence-item">
            {{ e.label }} <b>{{ e.value }}</b>
          </span>
        </div>
        <div v-if="askAnswer.links?.length" class="ask-links">
          <el-button
            v-for="(l, i) in askAnswer.links"
            :key="i"
            size="small"
            type="primary"
            plain
            @click="goRoute(l.route)"
          >{{ l.label }} →</el-button>
        </div>
        <div class="ask-feedback">
          <template v-if="!feedbackDone">
            <span class="ask-feedback-label">这个回答有帮助吗？</span>
            <el-button
              size="small"
              text
              type="primary"
              :loading="sendingFeedback"
              @click="sendFeedback(1)"
            >👍 有用</el-button>
            <el-button
              size="small"
              text
              type="danger"
              :loading="sendingFeedback"
              @click="openFeedbackDialog"
            >👎 归类不对</el-button>
          </template>
          <span v-else class="ask-feedback-done">已反馈</span>
        </div>
      </div>
    </el-card>

    <!-- 👎 反馈对话框 -->
    <el-dialog v-model="feedbackDialogVisible" title="反馈归类问题" width="480px">
      <p class="feedback-question">问：{{ askAnswer?.question }}</p>
      <el-input
        v-model="feedbackComment"
        type="textarea"
        :rows="3"
        placeholder="正确意图应该是什么 / 哪里不对（可选）"
      />
      <template #footer>
        <el-button @click="feedbackDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="sendingFeedback" @click="submitNegative">提交反馈</el-button>
      </template>
    </el-dialog>

    <!-- 跨库核对差异登记（/cs?recon=<runId> 深链落地；数据来自 GET /api/recon/diffs） -->
    <el-dialog v-model="reconDialogVisible" title="本批次差异登记（跨库核对）" width="880px">
      <el-table :data="reconDiffs" size="small" max-height="480">
        <el-table-column prop="direction" label="方向" width="64" />
        <el-table-column prop="stateCode" label="语义档" width="216" />
        <el-table-column prop="severity" label="严重级" width="92" />
        <el-table-column prop="patientNo" label="患者号" width="130" />
        <el-table-column prop="orderId" label="业务单号" width="170" />
        <el-table-column prop="itemName" label="项目" min-width="120" />
        <el-table-column prop="suggestedAction" label="建议动作" min-width="160" />
      </el-table>
    </el-dialog>

    <el-row :gutter="16" style="margin-top: 16px">
      <!-- 左栏：客服工单 -->
      <el-col :span="7">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>AI客服工单</span>
              <span class="ticket-total">共 {{ total }} 张</span>
            </div>
          </template>
          <div v-loading="loadingTickets">
            <div
              v-for="t in sortedTickets"
              :key="t.id"
              class="ticket-item"
              :class="{ active: currentTicket?.id === t.id }"
              @click="selectTicket(t)"
            >
              <div class="ticket-head">
                <span class="ticket-ref">{{ t.refNo }}</span>
                <el-tag size="small" :type="statusTag(t.status)">{{ t.status }}</el-tag>
              </div>
              <div class="ticket-title">{{ t.title }}</div>
              <div class="ticket-meta">{{ t.occurredAt }} · {{ t.conceptCode }}</div>
            </div>
            <el-empty v-if="!tickets.length" description="暂无工单" :image-size="60" />
            <el-pagination
              v-if="total > pageSize"
              class="ticket-pager"
              layout="prev, pager, next"
              :total="total"
              :page-size="pageSize"
              :current-page="pageNum"
              @current-change="onPageChange"
            />
          </div>
        </el-card>
      </el-col>

      <!-- 右栏：工单详情 + AI 诊断工作区 -->
      <el-col :span="17">
        <template v-if="currentTicket">
          <!-- 工单详情头 -->
          <el-card>
            <template #header>
              <div class="card-header">
                <span>工单详情</span>
                <el-tag :type="statusTag(currentTicket.status)">{{ currentTicket.status }}</el-tag>
              </div>
            </template>
            <el-descriptions :column="2">
              <el-descriptions-item label="标题" :span="2">{{ currentTicket.title }}</el-descriptions-item>
              <el-descriptions-item label="工单号">{{ currentTicket.refNo }}</el-descriptions-item>
              <el-descriptions-item label="发生时间">{{ currentTicket.occurredAt }}</el-descriptions-item>
              <el-descriptions-item label="关联概念">{{ currentTicket.conceptCode }}</el-descriptions-item>
              <el-descriptions-item label="患者">{{ ticketPayload.patient ?? '-' }}</el-descriptions-item>
              <el-descriptions-item v-if="ticketPayload.content" label="投诉内容" :span="2">
                {{ ticketPayload.content }}
              </el-descriptions-item>
            </el-descriptions>
          </el-card>

          <!-- AI 诊断工作区 -->
          <div
            v-loading="loadingDiagnosis"
            element-loading-text="AI 正在排查，首次分析可能需要几秒…"
            class="diagnosis-zone"
          >
            <el-skeleton v-if="loadingDiagnosis" :rows="6" animated class="diagnosis-skeleton" />
            <template v-else-if="diagnosis">
              <!-- 结论 -->
              <el-alert
                type="success"
                :closable="false"
                show-icon
                class="conclusion-alert"
                :title="diagnosis.case?.conclusion || '诊断未产出结论'"
              >
                <template #default>
                  <span class="conclusion-sub">
                    AI 已完成自动诊断 · 案例号 {{ diagnosis.case?.caseNo }} · 完成于
                    {{ diagnosis.case?.finishedAt }}
                  </span>
                </template>
              </el-alert>

              <!-- AI 排查过程 -->
              <el-card style="margin-top: 16px">
                <template #header>
                  <div class="card-header">
                    <span>排查过程（AI 是怎么一步步查出来的）</span>
                    <span class="step-note">每一步都是对业务系统的真实查询，结果可复核</span>
                  </div>
                </template>
                <el-steps direction="vertical" :active="steps.length" process-status="finish">
                  <el-step
                    v-for="s in steps"
                    :key="s.stepNo"
                    :title="`第${s.stepNo}步：${friendlyStepName(s)}`"
                  >
                    <template #description>
                      <el-card class="step-card" shadow="never">
                        <div class="step-meta">
                          <el-tag size="small" :type="stepTypeTag(s.stepType)">
                            {{ stepTypeText(s.stepType) }}
                          </el-tag>
                          <span v-if="stepHit(s) != null" class="hit-count">查到 {{ stepHit(s) }} 条</span>
                          <el-tag
                            v-if="s.status"
                            size="small"
                            effect="plain"
                            :type="s.status === 'SUCCESS' ? 'success' : 'danger'"
                          >{{ s.status === 'SUCCESS' ? '完成' : s.status }}</el-tag>
                        </div>
                        <el-collapse v-if="s.sqlText || stepDetail(s)" class="step-collapse">
                          <el-collapse-item v-if="stepDetail(s)" title="查看查询结果明细">
                            <pre class="json-block">{{ formatJson(stepDetail(s)) }}</pre>
                          </el-collapse-item>
                          <el-collapse-item v-if="s.sqlText" title="技术细节（查询语句，供技术人员复核）">
                            <pre class="sql-block">{{ s.sqlText }}</pre>
                          </el-collapse-item>
                        </el-collapse>
                      </el-card>
                    </template>
                  </el-step>
                </el-steps>
              </el-card>

              <!-- 证据与影响面 -->
              <el-card style="margin-top: 16px">
                <template #header>证据与影响面</template>
                <el-row :gutter="12" class="impact-row">
                  <el-col :span="6">
                    <div class="stat-card">
                      <div class="stat-value danger">{{ impact.affectedPatients ?? '-' }}</div>
                      <div class="stat-label">影响患者数</div>
                    </div>
                  </el-col>
                  <el-col :span="6">
                    <div class="stat-card">
                      <div class="stat-value danger">{{ impact.affectedFees ?? '-' }}</div>
                      <div class="stat-label">影响费用笔数</div>
                    </div>
                  </el-col>
                  <el-col :span="6">
                    <div class="stat-card">
                      <div class="stat-value danger">¥{{ impact.totalAmount ?? '-' }}</div>
                      <div class="stat-label">涉及金额</div>
                    </div>
                  </el-col>
                  <el-col :span="6">
                    <div class="stat-card">
                      <div class="stat-value">{{ (impact.relatedLinks || []).length }}</div>
                      <div class="stat-label">关联链路</div>
                    </div>
                  </el-col>
                </el-row>
                <el-descriptions :column="2" border>
                  <el-descriptions-item label="首次发生">{{ impact.firstOccurrence ?? '-' }}</el-descriptions-item>
                  <el-descriptions-item label="最近发生">{{ impact.lastOccurrence ?? '-' }}</el-descriptions-item>
                  <el-descriptions-item label="关联链路" :span="2">
                    {{ (impact.relatedLinks || []).join('、') || '-' }}
                  </el-descriptions-item>
                </el-descriptions>

                <el-divider content-position="left">证据链（每条都来自对业务系统的真实查询）</el-divider>
                <el-table :data="evidence" size="small" border>
                  <el-table-column prop="probe" label="排查项" width="160" />
                  <el-table-column prop="finding" label="查出了什么" />
                </el-table>

                <el-collapse style="margin-top: 12px">
                  <el-collapse-item title="原始数据（供技术人员复核）">
                    <pre class="json-block">{{ rawEvidenceJson }}</pre>
                  </el-collapse-item>
                </el-collapse>
              </el-card>

              <!-- 处置建议 -->
              <el-card v-if="suggestions.length" style="margin-top: 16px">
                <template #header>处置建议</template>
                <ol class="suggestion-list">
                  <li v-for="(s, i) in suggestions" :key="i">
                    <template v-if="typeof s === 'string'">{{ s }}</template>
                    <template v-else>
                      <div v-if="s.title || s.action" class="suggestion-title">{{ s.title || s.action }}</div>
                      <div v-if="s.detail || s.desc || s.description" class="suggestion-desc">
                        {{ s.detail || s.desc || s.description }}
                      </div>
                      <div v-if="s.owner" class="suggestion-owner">责任方：{{ s.owner }}</div>
                    </template>
                  </li>
                </ol>
              </el-card>

              <!-- 处置操作区 -->
              <el-card style="margin-top: 16px">
                <template #header>处置</template>
                <template v-if="!isDisposed">
                  <el-alert
                    type="warning"
                    :closable="false"
                    show-icon
                    title="确认诊断结论无误后，可一键按影响面批量生成退费申请并办结工单"
                    style="margin-bottom: 12px"
                  />
                  <el-button type="primary" :loading="refunding" @click="doRefund">
                    一键处置（生成退费申请）
                  </el-button>
                </template>
                <el-alert
                  v-else
                  type="info"
                  :closable="false"
                  show-icon
                  title="该工单已处置办结"
                />
                <el-descriptions
                  v-if="refundResult"
                  :column="3"
                  border
                  style="margin-top: 12px"
                  title="处置结果"
                >
                  <el-descriptions-item label="退费笔数">{{ refundResult.refundCount }}</el-descriptions-item>
                  <el-descriptions-item label="退费总额">¥{{ refundResult.totalAmount }}</el-descriptions-item>
                  <el-descriptions-item label="处置单号">{{ refundResult.disposalRef }}</el-descriptions-item>
                </el-descriptions>
              </el-card>

              <!-- 给客户的回复 -->
              <el-card v-if="diagnosis.customerReply" style="margin-top: 16px">
                <template #header>
                  <div class="card-header">
                    <span>给客户的回复</span>
                    <el-button size="small" @click="copyReply">复制</el-button>
                  </div>
                </template>
                <div class="reply-text">{{ diagnosis.customerReply }}</div>
              </el-card>
            </template>
            <el-card v-else>
              <el-empty description="诊断结果为空" :image-size="60" />
            </el-card>
          </div>
        </template>
        <el-card v-else>
          <el-empty description="请从左侧选择工单，AI 将自动完成诊断并给出处置建议" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listTickets, ticketDiagnosis, refundTicket, askCs, submitCsFeedback, fetchReconDiffs } from '../../api/cs'

const route = useRoute()
const router = useRouter()

// ---------- 问一问 ----------
const question = ref('')
const asking = ref(false)
const askAnswer = ref(null)

const quickQuestions = [
  '检验取消了怎么还收费？',
  '没缴费可以发药吗？',
  '一个医嘱可以分开发药吗？',
  '一次性输液器还有多少库存？',
  '发药后可以部分退药吗？',
  '王芳是谁？'
]

const doAsk = async (q) => {
  const text = (q || question.value).trim()
  if (!text) return
  question.value = text
  asking.value = true
  try {
    askAnswer.value = await askCs(text, 'CS')
    feedbackDone.value = false
    feedbackComment.value = ''
  } catch {
    // 失败提示由拦截器统一展示（如只读角色 403）
  } finally {
    asking.value = false
  }
}

const goRoute = (r) => router.push(r)

// ---------- 回答反馈（错例回流进 LLM 路由提示词） ----------
const feedbackDone = ref(false)
const sendingFeedback = ref(false)
const feedbackDialogVisible = ref(false)
const feedbackComment = ref('')

const sendFeedback = async (correct, comment) => {
  if (!askAnswer.value || feedbackDone.value) return
  sendingFeedback.value = true
  try {
    await submitCsFeedback({
      question: askAnswer.value.question,
      intent: askAnswer.value.intent,
      router: askAnswer.value.router,
      correct,
      ...(comment?.trim() ? { comment: comment.trim() } : {})
    })
    feedbackDone.value = true
    ElMessage.success(correct === 1 ? '感谢反馈' : '反馈已记录，将用于修正后续归类')
  } finally {
    sendingFeedback.value = false
  }
}

const openFeedbackDialog = () => {
  feedbackComment.value = ''
  feedbackDialogVisible.value = true
}

const submitNegative = async () => {
  await sendFeedback(0, feedbackComment.value)
  feedbackDialogVisible.value = false
}

// ---------- 工单列表 ----------
const tickets = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const loadingTickets = ref(false)
const currentTicket = ref(null)

// 未处置的置顶（前端排序）
const sortedTickets = computed(() =>
  [...tickets.value].sort((a, b) => Number(a.status === '已处置') - Number(b.status === '已处置'))
)

const statusTag = (s) => ({ 待处理: 'warning', 处理中: 'primary', 已处置: 'info' }[s] || 'info')

const loadTickets = async () => {
  loadingTickets.value = true
  try {
    const res = await listTickets(pageNum.value, pageSize.value)
    tickets.value = res.list || []
    total.value = res.total || 0
  } finally {
    loadingTickets.value = false
  }
}

const onPageChange = (p) => {
  pageNum.value = p
  loadTickets()
}

// ---------- 选中工单 → 自动诊断 ----------
const diagnosis = ref(null)
const loadingDiagnosis = ref(false)
const refundResult = ref(null)

const selectTicket = (ticket) => {
  if (currentTicket.value?.id === ticket.id && diagnosis.value) return
  currentTicket.value = ticket
  refundResult.value = null
  loadDiagnosis(ticket.id)
}

const loadDiagnosis = async (ticketId) => {
  diagnosis.value = null
  loadingDiagnosis.value = true
  try {
    diagnosis.value = await ticketDiagnosis(ticketId)
    // 以诊断返回的工单状态为准（可能已在别处处置）
    if (diagnosis.value?.ticket) {
      currentTicket.value = { ...currentTicket.value, ...diagnosis.value.ticket }
    }
  } finally {
    loadingDiagnosis.value = false
  }
}

const isDisposed = computed(() => currentTicket.value?.status === '已处置')

const ticketPayload = computed(() => parseJson(currentTicket.value?.payload, {}))

const steps = computed(() => diagnosis.value?.steps || [])

const stepHit = (s) => s.resultCount ?? s.hitCount
const stepDetail = (s) => s.detailJson ?? s.resultJson

const evidence = computed(() =>
  parseJson(diagnosis.value?.report?.evidenceJson, Array.isArray(diagnosis.value?.evidence) ? diagnosis.value.evidence : [])
)

const impact = computed(() => {
  if (diagnosis.value?.impact && typeof diagnosis.value.impact === 'object') return diagnosis.value.impact
  return parseJson(diagnosis.value?.report?.impactJson, {})
})

const suggestions = computed(() => {
  if (Array.isArray(diagnosis.value?.suggestions)) return diagnosis.value.suggestions
  return parseJson(diagnosis.value?.report?.suggestionsJson, [])
})

const rawEvidenceJson = computed(() => {
  const raw = {
    evidence: parseJson(diagnosis.value?.report?.evidenceJson, []),
    impact: impact.value
  }
  return JSON.stringify(raw, null, 2)
})

// ---------- 一键处置 ----------
const refunding = ref(false)

const doRefund = async () => {
  try {
    await ElMessageBox.confirm(
      '将按诊断结论批量生成退费申请并办结工单',
      '一键处置确认',
      { type: 'warning', confirmButtonText: '确认处置', cancelButtonText: '再想想' }
    )
  } catch {
    return
  }
  refunding.value = true
  try {
    const res = await refundTicket(currentTicket.value.id)
    refundResult.value = res
    ElMessage.success(`处置完成：${res.refundCount} 笔退费，合计 ¥${res.totalAmount}`)
    currentTicket.value = { ...currentTicket.value, status: '已处置' }
    loadTickets()
  } finally {
    refunding.value = false
  }
}

// ---------- 复制客户回复 ----------
const copyReply = async () => {
  const text = diagnosis.value?.customerReply || ''
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    const ta = document.createElement('textarea')
    ta.value = text
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
  }
  ElMessage.success('已复制，可直接粘贴给客户')
}

// ---------- 工具 ----------
const formatJson = (str) => {
  try {
    return JSON.stringify(JSON.parse(str), null, 2)
  } catch {
    return str
  }
}

const parseJson = (str, fallback) => {
  if (typeof str !== 'string') return str ?? fallback
  try {
    return JSON.parse(str) ?? fallback
  } catch {
    return fallback
  }
}

// 老数据的步骤名带「探针Pn：」前缀，展示时剥掉
const friendlyStepName = (s) => (s.stepName || '').replace(/^探针P\d+：?/, '')

const stepTypeText = (t) =>
  ({ TRAVERSE: '沿本体关系找数据', PROBE: '跨库真实查询', LINK: '翻变更留痕', REPORT: '产出结论' }[t] || t)
const stepTypeTag = (t) =>
  ({ TRAVERSE: 'primary', PROBE: 'warning', LINK: 'success', REPORT: 'danger' }[t] || 'info')

// ---------- 差异登记深链（/cs?recon=<runId>，来自核对答案「查本批次差异登记」锚点） ----------
const reconDialogVisible = ref(false)
const reconDiffs = ref([])
const showRecon = async (runId) => {
  try {
    reconDiffs.value = await fetchReconDiffs(runId)
    reconDialogVisible.value = true
  } catch (e) {
    ElMessage.error('差异登记读取失败：' + (e?.message || '网络异常'))
  }
}

onMounted(async () => {
  // 承接智能问数页「业务咨询？去 AI 客服」转介深链：带原问题直接提问
  const q0 = route.query.q
  if (q0) doAsk(String(q0))
  // 承接核对答案「查本批次差异登记」锚点：按 runId 拉差异登记
  const recon0 = route.query.recon
  if (recon0) showRecon(String(recon0))
  await loadTickets()
  const ticketId = Number(route.query.ticketId)
  if (ticketId) {
    const hit = tickets.value.find((t) => t.id === ticketId)
    if (hit) {
      selectTicket(hit)
    } else {
      currentTicket.value = { id: ticketId }
      loadDiagnosis(ticketId)
    }
  }
})
</script>

<style scoped>
.ask-intro {
  font-size: 13px;
  color: #606266;
  line-height: 1.8;
  margin-bottom: 10px;
}

.ask-row {
  max-width: 720px;
}

.ask-chips {
  margin-top: 8px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
}

.ask-chips-label {
  font-size: 12px;
  color: #909399;
}

.ask-chip {
  font-size: 12px;
  margin-right: 12px;
}

.ask-answer {
  margin-top: 12px;
  border: 1px solid #c2e7b0;
  background: #f0f9eb;
  border-radius: 6px;
  padding: 12px 14px;
  max-width: 860px;
}

.ask-answer-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.ask-question {
  font-size: 12px;
  color: #909399;
}

.ask-text {
  font-size: 13px;
  line-height: 1.9;
  color: #303133;
}

.ask-evidence {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.ask-evidence-item {
  font-size: 12px;
  background: #fff;
  border: 1px solid #e1f3d8;
  border-radius: 4px;
  padding: 3px 10px;
  color: #606266;
}

.ask-evidence-item b {
  color: #67c23a;
}

.ask-links {
  margin-top: 10px;
}

.ask-feedback {
  margin-top: 10px;
  display: flex;
  align-items: center;
  gap: 4px;
}

.ask-feedback-label {
  font-size: 12px;
  color: #909399;
  margin-right: 4px;
}

.ask-feedback-done {
  font-size: 12px;
  color: #67c23a;
}

.feedback-question {
  margin: 0 0 10px;
  font-size: 13px;
  color: #606266;
}

.step-note {
  font-size: 12px;
  color: #909399;
  font-weight: normal;
}

.ticket-total {
  font-size: 12px;
  color: #909399;
  font-weight: normal;
}

.ticket-item {
  padding: 10px 12px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  margin-bottom: 10px;
  cursor: pointer;
}

.ticket-item:hover {
  border-color: #409eff;
}

.ticket-item.active {
  border-color: #409eff;
  background: #ecf5ff;
}

.ticket-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.ticket-ref {
  font-size: 13px;
  font-weight: 600;
}

.ticket-title {
  font-size: 13px;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.ticket-meta {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.ticket-pager {
  margin-top: 8px;
  justify-content: flex-end;
}

.diagnosis-zone {
  margin-top: 16px;
  min-height: 200px;
}

.diagnosis-skeleton {
  background: #fff;
  padding: 20px;
  border-radius: 4px;
}

.conclusion-sub {
  font-size: 12px;
  color: #67c23a;
}

.step-card {
  margin: 4px 0 12px;
  background: #fafafa;
}

.step-meta {
  display: flex;
  align-items: center;
  gap: 10px;
}

.hit-count {
  font-size: 12px;
  color: #909399;
}

.step-collapse {
  margin-top: 8px;
}

.impact-row {
  margin-bottom: 12px;
}

.stat-card {
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 10px 12px;
  text-align: center;
  background: #fafafa;
}

.stat-value {
  font-size: 20px;
  font-weight: 700;
  color: #303133;
}

.stat-value.danger {
  color: #f56c6c;
}

.stat-label {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.suggestion-list {
  margin: 0;
  padding-left: 20px;
  line-height: 2;
}

.suggestion-title {
  font-weight: 600;
}

.suggestion-desc {
  color: #606266;
  font-size: 13px;
}

.suggestion-owner {
  color: #909399;
  font-size: 12px;
}

.reply-text {
  white-space: pre-wrap;
  line-height: 1.8;
  font-size: 14px;
}
</style>
