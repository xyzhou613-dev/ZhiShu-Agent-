<template>
  <div class="page">
    <el-alert
      type="info"
      :closable="false"
      title="治理规则挂在本体概念上，经映射翻译为对各业务库的真实查询——规则、字典、参照关系全部来自本体层；扫描命中的问题可一键转工单"
      style="margin-bottom: 12px"
    />

    <!-- 治理总览 -->
    <el-card>
      <template #header>
        <div class="card-header">
          <span>治理总览</span>
          <el-button type="primary" :loading="scanning" @click="runScan">执行扫描</el-button>
        </div>
      </template>
      <el-row :gutter="16" v-loading="loadingOverview">
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-value">{{ overview.tableCount ?? '-' }}</div>
            <div class="stat-label">物理表数</div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-value">{{ overview.coverage ?? '-' }}%</div>
            <div class="stat-label">
              映射覆盖率（{{ overview.mappedCount ?? '-' }}/{{ overview.columnCount ?? '-' }} 列）
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-value">{{ overview.qualityScore ?? '-' }}</div>
            <div class="stat-label">质量分（最近一次扫描）</div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-value" :class="{ danger: overview.openIssues > 0 }">
              {{ overview.openIssues ?? '-' }}
            </div>
            <div class="stat-label">未处理问题</div>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <!-- 表治理清单 -->
    <el-card style="margin-top: 16px">
      <template #header><span>表治理清单（{{ tables.length }} 张表）</span></template>
      <el-table :data="pagedTables" v-loading="loadingTables" size="small" max-height="420">
        <el-table-column label="库表" width="220">
          <template #default="{ row }">
            <span class="table-name">{{ row.dsCode }}.{{ row.tableName }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="tableComment" label="注释" min-width="170" show-overflow-tooltip />
        <el-table-column prop="rowCount" label="数据量" width="90" />
        <el-table-column label="映射覆盖" width="200">
          <template #default="{ row }">
            <el-progress :percentage="row.coverage" :stroke-width="8" />
          </template>
        </el-table-column>
        <el-table-column prop="domain" label="责任域" width="130" />
        <el-table-column label="健康度" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="healthTag(row.health)">{{ row.health }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="未处理问题" width="100">
          <template #default="{ row }">
            <span :class="{ 'issue-num': row.issueCount > 0 }">{{ row.issueCount }}</span>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-if="tables.length > 0"
        class="table-pager"
        small
        layout="total, prev, pager, next"
        v-model:current-page="tablePage"
        :page-size="pageSize"
        :total="tables.length"
      />
    </el-card>

    <!-- 问题清单 -->
    <el-card style="margin-top: 16px">
      <template #header>
        <span>问题清单{{ scanMeta }}</span>
      </template>
      <el-table :data="issues" v-loading="loadingIssues" size="small">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="sample-block">
              <div class="sample-title">命中样例（{{ sampleRows(row).length }} 行）</div>
              <el-table
                v-if="sampleRows(row).length"
                :data="sampleRows(row)"
                size="small"
                border
                class="sample-table"
              >
                <el-table-column
                  v-for="col in sampleCols(row)"
                  :key="col"
                  :prop="col"
                  :label="col"
                  min-width="150"
                  show-overflow-tooltip
                />
              </el-table>
              <pre v-else class="payload-block">{{ row.sampleJson }}</pre>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="严重度" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="severityTag(row.severity)">{{ row.severity }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="规则" min-width="200">
          <template #default="{ row }">
            <div>{{ row.ruleName }}</div>
            <div class="rule-code">{{ row.ruleCode }} · {{ row.conceptCode }}</div>
          </template>
        </el-table-column>
        <el-table-column label="库表" width="190">
          <template #default="{ row }">{{ row.dsCode }}.{{ row.tableName }}</template>
        </el-table-column>
        <el-table-column prop="ruleType" label="类型" width="160" />
        <el-table-column prop="hitCount" label="命中数" width="80" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="row.status === '已处理' ? 'success' : 'danger'">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button
              v-if="row.status === '未处理'"
              link
              type="primary"
              size="small"
              :loading="ticketing === row.id"
              @click="toTicket(row)"
            >转AI客服工单</el-button>
            <el-button
              v-if="row.status === '未处理'"
              link
              type="primary"
              size="small"
              :loading="resolving === row.id"
              @click="resolveIssue(row)"
            >标记已处理</el-button>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!issues.length && !loadingIssues" description="暂无治理问题" />
      <el-pagination
        v-if="issueTotal > 0"
        class="issue-pager"
        small
        layout="total, prev, pager, next"
        :current-page="issuePage"
        :page-size="pageSize"
        :total="issueTotal"
        @current-change="loadIssues"
      />
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { govOverview, govTables, listGovIssues, runGovScan, resolveGovIssue, govIssueToTicket } from '../../api/gov'

const router = useRouter()
const pageSize = 20

// ---------- 总览 ----------
const overview = ref({})
const loadingOverview = ref(false)

const loadOverview = async () => {
  loadingOverview.value = true
  try {
    overview.value = await govOverview()
  } finally {
    loadingOverview.value = false
  }
}

const scanMeta = computed(() => {
  const s = overview.value.lastScan
  return s ? `（最近扫描：${s.scanTime} · 耗时 ${s.durationMs}ms）` : ''
})

// ---------- 表治理清单 ----------
const tables = ref([])
const loadingTables = ref(false)
const tablePage = ref(1)

const pagedTables = computed(() => {
  const start = (tablePage.value - 1) * pageSize
  return tables.value.slice(start, start + pageSize)
})

const loadTables = async () => {
  loadingTables.value = true
  try {
    tables.value = await govTables()
    tablePage.value = 1
  } finally {
    loadingTables.value = false
  }
}

const healthTag = (h) => ({ 优: 'success', 良: 'warning', 差: 'danger' }[h] || 'info')

// ---------- 问题清单 ----------
const issues = ref([])
const issuePage = ref(1)
const issueTotal = ref(0)
const loadingIssues = ref(false)
const resolving = ref(null)
const ticketing = ref(null)

const loadIssues = async (page = issuePage.value) => {
  loadingIssues.value = true
  try {
    const res = await listGovIssues(page, pageSize)
    issues.value = res.list
    issueTotal.value = res.total
    issuePage.value = res.pageNum
  } finally {
    loadingIssues.value = false
  }
}

const severityTag = (s) => ({ 高: 'danger', 中: 'warning', 低: 'info' }[s] || 'info')

// sampleJson 是 JSON 字符串，解析为样例行数组
const sampleRows = (row) => {
  try {
    const v = JSON.parse(row.sampleJson)
    return Array.isArray(v) ? v : []
  } catch {
    return []
  }
}

const sampleCols = (row) => [...new Set(sampleRows(row).flatMap((r) => Object.keys(r || {})))]

const resolveIssue = async (row) => {
  resolving.value = row.id
  try {
    await resolveGovIssue(row.id)
    ElMessage.success('已标记为已处理')
    await Promise.all([loadOverview(), loadTables(), loadIssues(issuePage.value)])
  } finally {
    resolving.value = null
  }
}

// 治理问题一键转 AI 客服工单（治理→客诉→诊断→处置闭环），幂等复用后直跳 AI 客服页
const toTicket = async (row) => {
  ticketing.value = row.id
  try {
    const res = await govIssueToTicket(row.id)
    ElMessage.success(res.reused ? '已有对应工单，直接打开' : `工单已生成：${res.refNo}，正在打开 AI 客服诊断`)
    router.push({ path: '/cs', query: { ticketId: res.ticketId } })
  } finally {
    ticketing.value = null
  }
}

// ---------- 执行扫描 ----------
const scanning = ref(false)

const runScan = async () => {
  scanning.value = true
  try {
    const res = await runGovScan()
    ElMessage.success(
      `扫描完成：${res.ruleCount} 条规则 / 命中 ${res.issueCount} 个问题 / 耗时 ${res.durationMs}ms`
    )
    await Promise.all([loadOverview(), loadTables(), loadIssues(1)])
  } finally {
    scanning.value = false
  }
}

onMounted(() => {
  loadOverview()
  loadTables()
  loadIssues(1)
})
</script>

<style scoped>
.stat-card {
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 16px 12px;
  text-align: center;
  background: #fafafa;
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: #303133;
}

.stat-value.danger {
  color: #f56c6c;
}

.stat-label {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.table-name {
  font-family: 'SFMono-Regular', Consolas, Menlo, monospace;
  font-size: 12px;
}

.issue-num {
  color: #f56c6c;
  font-weight: 600;
}

.rule-code {
  font-size: 12px;
  color: #909399;
}

.sample-block {
  padding: 8px 12px;
}

.sample-title {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
}

.sample-table {
  max-width: 90%;
}

.issue-pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.table-pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
