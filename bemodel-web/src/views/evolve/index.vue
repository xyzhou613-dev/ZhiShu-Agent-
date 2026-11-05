<template>
  <div class="page">
    <!-- 顶部统计卡片 -->
    <el-card class="stats-card" v-loading="loading">
      <div class="stats-row">
        <div class="stat">
          <div class="stat-value" :class="stats.pending ? 'stat-hot' : ''">{{ stats.pending }}</div>
          <div class="stat-label">待处理缺口</div>
        </div>
        <div class="stat">
          <div class="stat-value">{{ stats.adopted }}</div>
          <div class="stat-label">已采纳（概念/术语）</div>
        </div>
        <div class="stat">
          <div class="stat-value">{{ stats.dismissed }}</div>
          <div class="stat-label">已忽略</div>
        </div>
        <div class="stat">
          <div class="stat-value">{{ stats.topCount }}</div>
          <div class="stat-label">最高热度（被问次数）</div>
        </div>
      </div>
      <div class="board-tip">
        用户提问、搜索与 AI 映射中本体未覆盖的说法会自动记录到这里；同一说法被问得越多热度越高，
        热度高的缺口优先长成本体概念或术语——这是「本体随使用生长」的增长回路。
        高热缺口可由下方提案生成器批量做成候选卡（AI 提议、人裁决），
        深度处置（采纳为新概念 / 挂为术语 / AI 归类预填）仍在
        <router-link to="/ontology">本体管理</router-link> 的扩展提案抽屉中完成。
      </div>
    </el-card>

    <!-- 工具栏 -->
    <div class="toolbar">
      <el-radio-group v-model="filter">
        <el-radio-button value="all">全部（{{ items.length }}）</el-radio-button>
        <el-radio-button value="pending">待处理（{{ pendingList.length }}）</el-radio-button>
        <el-radio-button value="adopted">已采纳（{{ adoptedList.length }}）</el-radio-button>
        <el-radio-button value="dismissed">已忽略（{{ dismissedList.length }}）</el-radio-button>
      </el-radio-group>
      <el-input
        v-model="keyword"
        placeholder="搜索缺口说法"
        clearable
        :prefix-icon="Search"
        style="width: 220px"
      />
      <span class="toolbar-tip">按热度降序：被问得最多的说法排最前</span>
    </div>

    <!-- 缺口列表 -->
    <div v-loading="loading" class="miss-list">
      <div v-for="m in shownList" :key="m.id" class="miss-card" :class="{ dismissed: m.dismissed === 1 }">
        <div class="miss-main">
          <div class="miss-title-row">
            <span class="miss-term">{{ m.term }}</span>
            <el-tag size="small" :type="m.kind === 'QUESTION' ? 'warning' : 'info'" effect="plain">
              {{ kindText(m.kind) }}
            </el-tag>
            <el-tag size="small" effect="plain">{{ sourceText(m.source) }}</el-tag>
            <el-tag v-if="m.count >= 3" size="small" type="danger" effect="dark">高热</el-tag>
            <span v-if="m.adoptedAs" class="miss-adopted">
              已采纳为{{ m.adoptedAs === 'CONCEPT' ? '概念' : '术语' }}
              <template v-if="m.adoptedConceptCode">（{{ m.adoptedConceptCode }}）</template>
              <el-tag v-if="m.revoked === 1" size="small" type="info" effect="plain">已撤销</el-tag>
            </span>
          </div>
          <div class="miss-meta">
            首次出现 {{ fmt(m.firstSeen) }} ｜ 最近出现 {{ fmt(m.lastSeen) }}
          </div>
          <!-- AI 归类建议 -->
          <div v-if="suggestions[m.id]" class="miss-suggestion">
            <div class="sug-title">
              <el-icon><MagicStick /></el-icon> AI 归类建议{{ suggestions[m.id].degraded ? '（LLM 暂不可用，仅回显原说法）' : '' }}
            </div>
            <div class="sug-body">
              <template v-if="!suggestions[m.id].degraded">
                <el-tag size="small" type="success" effect="plain">{{ suggestions[m.id].kind }}</el-tag>
                <b>{{ suggestions[m.id].name }}</b>
                <span v-if="suggestions[m.id].domainCode" class="sug-dim">域：{{ suggestions[m.id].domainCode }}</span>
              </template>
              <span>{{ suggestions[m.id].definition || '去本体管理中完成采纳' }}</span>
            </div>
          </div>
        </div>
        <div class="miss-side">
          <div class="miss-count" :class="{ hot: m.count >= 3 }">× {{ m.count }}</div>
          <div class="miss-count-label">出现次数</div>
        </div>
        <div v-if="!userStore.isViewer" class="miss-actions">
          <el-button size="small" type="primary" link @click="classify(m)">
            <el-icon><MagicStick /></el-icon>&nbsp;AI 归类
          </el-button>
          <el-button size="small" type="primary" link @click="goAdopt">去本体处置</el-button>
          <template v-if="m.dismissed === 1">
            <el-button size="small" link @click="undismiss(m)">恢复</el-button>
          </template>
          <template v-else>
            <el-button size="small" link type="danger" @click="dismiss(m)">忽略</el-button>
          </template>
        </div>
      </div>
      <el-empty v-if="!loading && !shownList.length" description="没有匹配的缺口记录" />
    </div>

    <!-- AI 提案队列（L2：AI 提议人裁决） -->
    <el-card class="proposal-card" v-loading="proposalLoading">
      <template #header>
        <div class="clarify-head">
          <div class="clarify-head-left">
            <span class="clarify-title">AI 提案队列</span>
            <span class="clarify-tip">
              提案生成器把高热缺口批量做成候选卡（近义预审：新建概念 / 挂靠术语二选一）；
              采纳即按卡落 DRAFT 草稿或挂方言术语——AI 只提议，发布仍走评审门禁
            </span>
          </div>
          <div class="clarify-tools">
            <el-radio-group v-model="proposalFilter" size="small" @change="loadProposals">
              <el-radio-button value="PENDING">待裁决</el-radio-button>
              <el-radio-button value="ADOPTED">已采纳</el-radio-button>
              <el-radio-button value="REJECTED">已驳回</el-radio-button>
              <el-radio-button value="ALL">全部</el-radio-button>
            </el-radio-group>
            <el-button
              v-if="!userStore.isViewer"
              size="small"
              type="primary"
              :loading="generating"
              @click="generate"
            >
              生成提案
            </el-button>
            <el-button size="small" :icon="Refresh" @click="loadProposals">刷新</el-button>
          </div>
        </div>
      </template>
      <el-table :data="proposalItems" size="small">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="proposal-detail">
              <template v-if="proposalCard(row)">
                <div>候选编码：{{ proposalCard(row).code || '—' }}</div>
                <div>候选名称：{{ proposalCard(row).name || '—' }}</div>
                <div>建议域：{{ proposalCard(row).domainCode || '—' }}</div>
                <div>业务定义：{{ proposalCard(row).definition || '—' }}</div>
                <div>判定理由：{{ proposalCard(row).reason || '—' }}</div>
                <div>置信度：{{ proposalCard(row).confidence ?? '—' }}</div>
                <div v-if="proposalCard(row).warnings?.length" class="proposal-warn">
                  <div v-for="(w, i) in proposalCard(row).warnings" :key="i">⚠ {{ w }}</div>
                </div>
              </template>
              <div v-else>（候选卡数据缺失）</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="88">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="row.status === 'PENDING' ? 'warning' : row.status === 'ADOPTED' ? 'success' : 'info'"
              effect="plain"
            >
              {{ proposalStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="term" label="代表说法" min-width="160" show-overflow-tooltip />
        <el-table-column prop="signalCount" label="信号热度" width="84" />
        <el-table-column label="AI 判定" width="200">
          <template #default="{ row }">
            <el-tag size="small" :type="row.action === 'NEW_CONCEPT' ? 'success' : 'info'" effect="plain">
              {{ row.action === 'NEW_CONCEPT' ? '新建概念' : '挂靠术语' }}
            </el-tag>
            <span v-if="row.action === 'ATTACH_TERM'" class="proposal-target">→ {{ row.targetConceptCode }}</span>
          </template>
        </el-table-column>
        <el-table-column label="裁决" width="160">
          <template #default="{ row }">
            <span v-if="row.decidedBy">
              {{ row.decidedBy }}<template v-if="row.decidedAt"> · {{ fmt(row.decidedAt) }}</template>
            </span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING' && !userStore.isViewer">
              <el-button size="small" type="primary" link @click="adopt(row)">采纳</el-button>
              <el-button size="small" type="danger" link @click="reject(row)">驳回</el-button>
            </template>
            <span v-else-if="row.status === 'REJECTED'" class="proposal-reject-reason">
              {{ row.rejectReason || '（无理由）' }}
            </span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty
        v-if="!proposalLoading && !proposalItems.length"
        description="暂无提案——点「生成提案」把高热缺口批量做成候选卡"
        :image-size="60"
      />
    </el-card>

    <!-- 澄清任务（A 型歧义证据，维护者可见性） -->
    <el-card v-if="canSeeClarify" class="clarify-card" v-loading="clarifyLoading">
      <template #header>
        <div class="clarify-head">
          <div class="clarify-head-left">
            <span class="clarify-title">澄清任务（A 型歧义证据）</span>
            <span class="clarify-tip">
              两轮未收敛的口径歧义在此可见：证据 = 原问题 + AI 判断缺什么 + 用户补充原文；
              GAVE_UP 任务已自动回流扩展提案池（miss id 可对上），处置仍在本体管理完成
            </span>
          </div>
          <div class="clarify-tools">
            <el-radio-group v-model="clarifyFilter" size="small" @change="loadClarify">
              <el-radio-button value="PENDING">待补充</el-radio-button>
              <el-radio-button value="GAVE_UP">已回流</el-radio-button>
              <el-radio-button value="RESOLVED">已澄清</el-radio-button>
              <el-radio-button value="ALL">全部</el-radio-button>
            </el-radio-group>
            <el-button size="small" :icon="Refresh" @click="loadClarify">刷新</el-button>
          </div>
        </div>
      </template>
      <el-table :data="clarifyItems" size="small">
        <el-table-column prop="status" label="状态" width="88">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="row.status === 'PENDING' ? 'warning' : row.status === 'GAVE_UP' ? 'info' : 'success'"
              effect="plain"
            >
              {{ clarifyStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="originQuestion" label="原问题" min-width="220" show-overflow-tooltip />
        <el-table-column prop="reason" label="AI：缺什么" min-width="170" show-overflow-tooltip />
        <el-table-column prop="supplement" label="用户补充原文" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.supplement || '（未补充即回流）' }}</template>
        </el-table-column>
        <el-table-column prop="rounds" label="轮次" width="60" />
        <el-table-column prop="missId" label="回流 miss" width="88">
          <template #default="{ row }">{{ row.missId ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="时间" width="168">
          <template #default="{ row }">
            建 {{ fmt(row.createdAt) }}<template v-if="row.closedAt">｜闭 {{ fmt(row.closedAt) }}</template>
          </template>
        </el-table-column>
      </el-table>
      <el-empty
        v-if="!clarifyLoading && !clarifyItems.length"
        description="暂无澄清任务（A 型歧义在问一问页产生）"
        :image-size="60"
      />
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, MagicStick, Refresh } from '@element-plus/icons-vue'
import {
  listOntologyMisses,
  dismissMiss,
  undismissMiss,
  classifyMiss,
  fetchProposals,
  runProposalGeneration,
  adoptProposal,
  rejectProposal
} from '../../api/ontology'
import { fetchClarifyTasks } from '../../api/cs'
import { useUserStore } from '../../store/user'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const items = ref([])
const filter = ref('all')
const keyword = ref('')
const suggestions = ref({}) // missId -> suggestion（AI 归类结果）

// ---------- 澄清任务（A 型歧义证据） ----------
const canSeeClarify = computed(() => ['ADMIN', 'EDITOR'].includes(userStore.user?.role))
const clarifyLoading = ref(false)
const clarifyItems = ref([])
const clarifyFilter = ref('ALL') // 默认全部：特性主目标是 GAVE_UP 证据可见，只看 PENDING 会漏掉已回流项
const clarifyStatusText = (s) => ({ PENDING: '待补充', GAVE_UP: '已回流', RESOLVED: '已澄清' }[s] || s)

const loadClarify = async () => {
  clarifyLoading.value = true
  try {
    const data = await fetchClarifyTasks({ status: clarifyFilter.value, pageNum: 1, pageSize: 50 })
    clarifyItems.value = data.list || []
  } finally {
    clarifyLoading.value = false
  }
}

// ---------- AI 提案队列（L2：AI 提议人裁决） ----------
const proposalLoading = ref(false)
const proposalItems = ref([])
const proposalFilter = ref('PENDING') // 默认待裁决：队列的工作对象就是 PENDING 卡
const generating = ref(false)
const proposalStatusText = (s) => ({ PENDING: '待裁决', ADOPTED: '已采纳', REJECTED: '已驳回' }[s] || s)

// 候选卡存 suggestionJson 字符串（JSON 列），展开行现解析；坏数据如实显示缺失不静默
const proposalCard = (row) => {
  try {
    return row.suggestionJson ? JSON.parse(row.suggestionJson) : null
  } catch {
    return null
  }
}

const loadProposals = async () => {
  proposalLoading.value = true
  try {
    const data = await fetchProposals({ status: proposalFilter.value, pageNum: 1, pageSize: 50 })
    proposalItems.value = data.list || []
  } finally {
    proposalLoading.value = false
  }
}

const generate = async () => {
  await ElMessageBox.confirm(
    '将扫描待处理的概念类缺口（热度前 10），每条产生一次 LLM 调用并生成候选卡。继续？',
    '生成提案',
    { type: 'info', confirmButtonText: '生成', cancelButtonText: '取消' }
  )
  generating.value = true
  try {
    const res = await runProposalGeneration()
    ElMessage.success(
      `扫描 ${res.scanned} 条，生成 ${res.generated} 张提案` +
        (res.llmFailed ? `，${res.llmFailed} 条因 LLM 不可用跳过` : '')
    )
    loadProposals()
  } finally {
    generating.value = false
  }
}

const adopt = async (row) => {
  const tip =
    row.action === 'NEW_CONCEPT'
      ? '将按候选卡创建 DRAFT 草稿概念（发布仍需评审），并归并同组信号。'
      : `将把该说法挂为概念 ${row.targetConceptCode} 的方言术语。`
  await ElMessageBox.confirm(tip, '采纳提案', { type: 'warning', confirmButtonText: '采纳', cancelButtonText: '取消' })
  await adoptProposal(row.id)
  ElMessage.success('提案已采纳，去本体管理评审')
  loadProposals()
  load() // 同步刷新上方 miss 看板：组内信号已被采纳标记，看板不能还挂着「待处理」（对抗评审）
}

const reject = async (row) => {
  const { value } = await ElMessageBox.prompt(
    '驳回后该信号仍留在 miss 池，下轮生成可能再入队。',
    '驳回提案',
    { inputPlaceholder: '驳回理由（可选）', confirmButtonText: '驳回', cancelButtonText: '取消' }
  )
  await rejectProposal(row.id, value || '')
  ElMessage.success('已驳回')
  loadProposals()
  load()
}

const KIND_TEXT = { CONCEPT: '新概念说法', ATTRIBUTE: '新属性说法', QUESTION: '未答问题' }
const SOURCE_TEXT = { SEARCH: '概念搜索', MAPPING_AI: 'AI 映射', CS_ASK: 'AI 客服', QA_ASK: '智能问数' }
const kindText = (k) => KIND_TEXT[k] || k
const sourceText = (s) => SOURCE_TEXT[s] || s
const fmt = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '-')

const stats = computed(() => {
  const pending = items.value.filter(isPending)
  return {
    pending: pending.length,
    adopted: items.value.filter((m) => m.adoptedAs && m.revoked === 0).length,
    dismissed: items.value.filter((m) => m.dismissed === 1).length,
    topCount: pending.reduce((max, m) => Math.max(max, m.count || 0), 0)
  }
})

// 待处理口径与后端 isPending 一致：未忽略 且（从未采纳 或 采纳已撤销回池）；dismissed 可能是 null（从未忽略）
const isPending = (m) =>
  m.dismissed !== 1 && (!m.adoptedAs || m.revoked === 1)
const pendingList = computed(() => items.value.filter(isPending))
const adoptedList = computed(() => items.value.filter((m) => m.adoptedAs && m.revoked === 0))
const dismissedList = computed(() => items.value.filter((m) => m.dismissed === 1))

const shownList = computed(() => {
  let list =
    filter.value === 'pending'
      ? pendingList.value
      : filter.value === 'adopted'
        ? adoptedList.value
        : filter.value === 'dismissed'
          ? dismissedList.value
          : items.value
  const kw = keyword.value.trim().toLowerCase()
  if (kw) {
    list = list.filter(
      (m) => m.term?.toLowerCase().includes(kw) || m.adoptedConceptCode?.toLowerCase().includes(kw)
    )
  }
  // 热度降序，同热度按最近出现降序
  return [...list].sort(
    (a, b) => (b.count || 0) - (a.count || 0) || String(b.lastSeen || '').localeCompare(String(a.lastSeen || ''))
  )
})

const load = async () => {
  loading.value = true
  try {
    const data = await listOntologyMisses()
    items.value = data.items || []
  } finally {
    loading.value = false
  }
}

const classify = async (m) => {
  const res = await classifyMiss(m.id)
  suggestions.value = { ...suggestions.value, [m.id]: res.suggestion }
  ElMessage.success('已生成归类建议，请到本体管理确认采纳')
}

const dismiss = async (m) => {
  const { value } = await ElMessageBox.prompt('忽略后该说法不再出现在待处理中，可随时恢复。', '忽略缺口', {
    inputValue: m.term,
    inputPlaceholder: '忽略理由（可选）'
  })
  await dismissMiss(m.id, value || '')
  ElMessage.success('已忽略')
  load()
}

const undismiss = async (m) => {
  await undismissMiss(m.id)
  ElMessage.success('已恢复为待处理')
  load()
}

const goAdopt = () => router.push('/ontology')

onMounted(() => {
  load()
  loadProposals()
  if (canSeeClarify.value) {
    loadClarify()
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
  color: var(--text-primary);
}

.stat-value.stat-hot {
  color: var(--el-color-warning);
}

.stat-label {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 2px;
}

.board-tip {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--border-color);
  font-size: 12px;
  line-height: 1.8;
  color: var(--text-secondary);
}

.board-tip a {
  color: var(--primary);
  font-weight: 600;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.toolbar-tip {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-muted);
}

.miss-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.miss-card {
  display: flex;
  align-items: center;
  gap: 16px;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: var(--radius, 8px);
  padding: 14px 18px;
  transition: var(--transition);
}

.miss-card:hover {
  border-color: var(--primary);
  box-shadow: var(--shadow-sm);
}

.miss-card.dismissed {
  opacity: 0.55;
}

.miss-main {
  flex: 1;
  min-width: 0;
}

.miss-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.miss-term {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.miss-adopted {
  font-size: 12px;
  color: var(--el-color-success);
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.miss-meta {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-muted);
}

.miss-suggestion {
  margin-top: 10px;
  padding: 10px 12px;
  background: var(--el-color-success-light-9, #f0f9eb);
  border-radius: 6px;
  font-size: 12px;
}

.sug-title {
  display: flex;
  align-items: center;
  gap: 4px;
  font-weight: 600;
  color: var(--el-color-success);
  margin-bottom: 4px;
}

.sug-body {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  color: var(--text-secondary);
}

.sug-dim {
  color: var(--text-muted);
}

.miss-side {
  text-align: center;
  flex-shrink: 0;
  min-width: 64px;
}

.miss-count {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-secondary);
}

.miss-count.hot {
  color: var(--el-color-danger);
}

.miss-count-label {
  font-size: 11px;
  color: var(--text-muted);
}

.miss-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  flex-shrink: 0;
}

.miss-actions .el-button {
  margin-left: 0;
}

/* ---------- AI 提案队列卡 ---------- */
.proposal-card {
  margin-top: 12px;
}

.proposal-detail {
  padding: 4px 12px;
  font-size: 12px;
  line-height: 1.9;
  color: var(--text-secondary);
}

.proposal-warn {
  color: var(--el-color-danger);
}

.proposal-target {
  margin-left: 6px;
  font-size: 12px;
  color: var(--text-muted);
}

.proposal-reject-reason {
  font-size: 12px;
  color: var(--text-muted);
}

/* ---------- 澄清任务卡 ---------- */
.clarify-card {
  margin-top: 12px;
}

.clarify-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.clarify-title {
  font-weight: 600;
  color: var(--text-primary);
  margin-right: 10px;
}

.clarify-tip {
  font-size: 12px;
  color: var(--text-muted);
}

.clarify-tools {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
</style>
