<template>
  <div>
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="质控不是只看病案文本：每条规则都跨系统取数——诊断←病案(EMR)、医嘱←HIS、检验←LIS、检查←PACS、药品知识←药房字典，按本体概念对齐后互相印证。每条发现的「数据来源」标明了它跨了哪几个库。"
      style="margin-bottom: 12px"
    />
    <div class="qc-toolbar">
      <el-input
        v-model="keyword"
        placeholder="输入姓名 / 住院号搜索"
        clearable
        :prefix-icon="Search"
        style="width: 260px"
        @input="onKeywordInput"
        @clear="loadRecords(1)"
      />
      <el-button type="primary" :loading="checkingAll" @click="runCheckAll">全量内涵质控</el-button>
    </div>
    <el-alert
      v-if="allSummary"
      type="info"
      :closable="false"
      :title="`共${allSummary.total}份：通过${allSummary.pass} / 不通过${allSummary.fail}`"
      style="margin-bottom: 12px"
    />
    <el-row :gutter="16">
      <!-- 左：病案列表 -->
      <el-col :span="10">
        <el-card>
          <el-table
            :data="records"
            v-loading="loadingRecords"
            highlight-current-row
            size="small"
            height="600"
            @row-click="selectRecord"
          >
            <el-table-column prop="record_id" label="病案号" width="140" />
            <el-table-column prop="patient_name" label="患者" width="80" />
            <el-table-column prop="record_type" label="类型" width="90" />
            <el-table-column prop="diag_main" label="主要诊断" min-width="110" show-overflow-tooltip />
            <el-table-column label="质控状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="qcStatusTag(row.qc_status)">{{ row.qc_status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="doctor" label="医生" width="80" />
            <el-table-column prop="create_time" label="书写时间" width="150" />
          </el-table>
          <el-pagination
            v-if="recordTotal > 0"
            class="record-pager"
            small
            layout="total, prev, pager, next"
            :current-page="recordPage"
            :page-size="pageSize"
            :total="recordTotal"
            @current-change="loadRecords"
          />
        </el-card>
      </el-col>

      <!-- 右：详情与质控结果 -->
      <el-col :span="14">
        <template v-if="current">
          <el-card>
            <template #header>
              <div class="card-header">
                <span>病案信息</span>
                <div>
                  <el-tooltip
                    content="平台内置 SHACL 校验（Jena），对该患者导出的 ABox 执行 sh:validate 复核"
                    placement="top"
                  >
                    <span>
                      <el-button
                        type="success"
                        plain
                        :disabled="!current.inhos_no"
                        :loading="validating"
                        @click="runValidate"
                      >SHACL 复核</el-button>
                    </span>
                  </el-tooltip>
                  <el-button type="primary" :loading="checking" @click="runCheck">
                    发起内涵质控
                  </el-button>
                </div>
              </div>
            </template>
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="患者">{{ current.patient_name }}</el-descriptions-item>
              <el-descriptions-item label="住院号">{{ current.inhos_no }}</el-descriptions-item>
              <el-descriptions-item label="类型">{{ current.record_type }}</el-descriptions-item>
              <el-descriptions-item label="主要诊断">{{ current.diag_main }}</el-descriptions-item>
              <el-descriptions-item label="全部诊断" :span="2">{{ current.diag_list }}</el-descriptions-item>
              <el-descriptions-item label="摘要" :span="2">{{ current.content }}</el-descriptions-item>
              <el-descriptions-item label="医生">{{ current.doctor }}</el-descriptions-item>
              <el-descriptions-item label="书写时间">{{ current.create_time }}</el-descriptions-item>
            </el-descriptions>
          </el-card>

          <div v-loading="checking || loadingResult" style="margin-top: 16px">
            <template v-if="result">
              <div v-if="resultAt" class="result-meta">上次质控：{{ resultAt }}</div>
              <el-alert
                v-if="result.pass"
                type="success"
                :closable="false"
                show-icon
                title="内涵质控通过，未发现逻辑矛盾"
              />
              <el-alert v-else type="error" :closable="false" show-icon title="质控不通过" />

              <el-card style="margin-top: 12px">
                <template #header>
                  <span>质控发现（{{ result.findings.length }} 项）</span>
                </template>
                <el-table :data="result.findings" size="small">
                  <el-table-column label="规则" width="190">
                    <template #default="{ row }">
                      <div class="finding-rule">{{ row.ruleCode }}</div>
                      <div class="finding-rule-name">{{ row.ruleName }}</div>
                    </template>
                  </el-table-column>
                  <el-table-column label="严重度" width="80">
                    <template #default="{ row }">
                      <el-tag size="small" :type="severityTag(row.severity)">{{ row.severity }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="evidence" label="证据（查出了什么）" min-width="200" show-overflow-tooltip />
                  <el-table-column label="数据来源（跨了哪几个系统）" min-width="210">
                    <template #default="{ row }">
                      <template v-if="row.sources?.length">
                        <div v-for="(src, i) in row.sources" :key="i" class="source-chip">{{ src }}</div>
                      </template>
                      <span v-else>-</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="公理" width="90">
                    <template #default="{ row }">
                      <el-tag v-if="row.axiom" size="small" type="warning" effect="plain">
                        {{ row.axiom }}
                      </el-tag>
                      <span v-else>-</span>
                    </template>
                  </el-table-column>
                  <el-table-column width="170">
                    <template #header>
                      <el-tooltip
                        content="平台内置 SHACL 校验（Jena），可对任一患者一键复核"
                        placement="top"
                      >
                        <span>SHACL 已互证</span>
                      </el-tooltip>
                    </template>
                    <template #default="{ row }">
                      <template v-if="shaclShapeOf(row.ruleCode)">
                        <el-tag size="small" type="success" effect="plain">
                          {{ shaclShapeOf(row.ruleCode).shape }}
                        </el-tag>
                        <el-link
                          type="primary"
                          class="dual-link"
                          :href="`/api/rdf/patient/${current.inhos_no}`"
                          :download="`${current.inhos_no}.ttl`"
                        >导出ABox验证</el-link>
                      </template>
                      <span v-else>-</span>
                    </template>
                  </el-table-column>
                </el-table>
                <el-empty v-if="!result.findings.length" description="无发现项" :image-size="50" />
              </el-card>

              <el-card v-if="result.trace" style="margin-top: 12px">
                <template #header>结论溯源（满足医疗合规）</template>
                <div class="trace-row">
                  <span class="trace-label">本体版本</span>
                  <el-tag size="small" type="success">{{ result.trace.ontologyVersion }}</el-tag>
                </div>
                <div class="trace-row">
                  <span class="trace-label">引用规则</span>
                  <el-tag
                    v-for="r in result.trace.rulesCited || []"
                    :key="r"
                    size="small"
                    effect="plain"
                    class="trace-tag"
                  >{{ r }}</el-tag>
                  <span v-if="!(result.trace.rulesCited || []).length" class="trace-none">无</span>
                </div>
                <div class="trace-row">
                  <span class="trace-label">引用公理</span>
                  <el-tag
                    v-for="a in result.trace.axiomsCited || []"
                    :key="a"
                    size="small"
                    type="warning"
                    effect="plain"
                    class="trace-tag"
                  >{{ a }}</el-tag>
                  <span v-if="!(result.trace.axiomsCited || []).length" class="trace-none">无</span>
                </div>
                <div class="trace-row">
                  <span class="trace-label">涉及概念</span>
                  <span class="trace-concepts">{{ (result.trace.conceptsInvolved || []).join('、') || '-' }}</span>
                </div>
              </el-card>

              <el-card style="margin-top: 12px">
                <template #header>
                  <div class="card-header">
                    <span>LLM 质控意见</span>
                    <el-tag size="small" effect="plain" :type="result.llmUsed ? 'success' : 'info'">
                      {{ result.llmUsed ? 'AI 生成' : '规则模板生成' }}
                    </el-tag>
                  </div>
                </template>
                <div class="llm-summary">{{ result.llmSummary || '（无意见内容）' }}</div>
              </el-card>
            </template>
            <el-card v-else>
              <el-empty description="该病案尚未质控，点击右上角「发起内涵质控」" />
            </el-card>
          </div>
        </template>
        <el-card v-else>
          <el-empty description="请选择左侧病案查看详情" />
        </el-card>
      </el-col>
    </el-row>

    <!-- SHACL 复核结果 -->
    <el-dialog v-model="validateVisible" title="SHACL 复核" width="720px">
      <template v-if="validateResult">
        <div class="validate-summary">
          <el-tag
            :type="validateResult.conforms ? 'success' : 'danger'"
            effect="dark"
            size="large"
          >{{ validateResult.conforms ? '通过' : '不通过' }}</el-tag>
          <span class="validate-meta">
            {{ validateResult.engine }} ｜ {{ validateResult.shapes }}
          </span>
        </div>
        <el-table
          v-if="(validateResult.violations || []).length"
          :data="validateResult.violations"
          size="small"
          style="margin-top: 12px"
        >
          <el-table-column prop="focusNode" label="焦点节点" min-width="220" show-overflow-tooltip />
          <el-table-column label="路径" width="120">
            <template #default="{ row }">{{ row.path || '-' }}</template>
          </el-table-column>
          <el-table-column prop="message" label="违规说明" min-width="220" show-overflow-tooltip />
          <el-table-column label="级别" width="90">
            <template #default="{ row }">
              <el-tag size="small" type="danger" effect="plain">{{ row.severity }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-alert
          v-else
          type="success"
          :closable="false"
          title="未发现 SHACL 违规"
          style="margin-top: 12px"
        />
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElLoading } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { listQcRecords, checkQc, checkAllQc, qcResult, validateRdf } from '../../../api/clinical'

// ---------- 病案列表 ----------
const keyword = ref('')
const records = ref([])
const recordPage = ref(1)
const recordTotal = ref(0)
const pageSize = 20
const loadingRecords = ref(false)
const current = ref(null)
const allSummary = ref(null)
const checkingAll = ref(false)

let debounceTimer = null
// 搜索（防抖输入 / 清空）重置到第 1 页
const onKeywordInput = () => {
  clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => loadRecords(1), 300)
}

const qcStatusTag = (s) => ({ 未质控: 'info', 通过: 'success', 不通过: 'danger' }[s] || 'info')
const severityTag = (s) => ({ 高: 'danger', 中: 'warning', 低: 'info' }[s] || 'info')

// 平台规则对应的顶层模板 SHACL Shape（Shape 已在模板定义，导出 ABox 后可用 Jena/RDF4J 复核）
const SHACL_SHAPE_MAP = {
  'RULE-QC-005': { shape: 'SHACL Shape 4' },
  'RULE-QC-007': { shape: 'SHACL Shape 1' },
  'RULE-QC-008': { shape: 'SHACL Shape 2' }
}
const shaclShapeOf = (ruleCode) => SHACL_SHAPE_MAP[ruleCode] || null

const loadRecords = async (page = recordPage.value) => {
  loadingRecords.value = true
  try {
    const res = await listQcRecords(keyword.value.trim() || undefined, page, pageSize)
    records.value = res.list
    recordTotal.value = res.total
    recordPage.value = res.pageNum
    // 当前选中病案不在结果中时清空详情
    if (current.value && !records.value.some((r) => r.record_id === current.value.record_id)) {
      current.value = null
      result.value = null
    }
  } finally {
    loadingRecords.value = false
  }
}

const runCheckAll = async () => {
  checkingAll.value = true
  const loadingInstance = ElLoading.service({
    text: '正在按规则+公理校验全部病案（含LLM意见生成，约30秒）',
    background: 'rgba(255, 255, 255, 0.7)'
  })
  try {
    const res = await checkAllQc()
    allSummary.value = res
    ElMessage.success(`全量质控完成：共${res.total}份，通过${res.pass} / 不通过${res.fail}`)
    await loadRecords()
    if (current.value) selectRecord(current.value)
  } finally {
    loadingInstance.close()
    checkingAll.value = false
  }
}

// ---------- 详情与质控 ----------
const result = ref(null)
const resultAt = ref('')
const checking = ref(false)
const loadingResult = ref(false)

// ---------- SHACL 复核（平台内置 Jena 校验） ----------
const validating = ref(false)
const validateVisible = ref(false)
const validateResult = ref(null)

const runValidate = async () => {
  if (!current.value?.inhos_no) return
  validating.value = true
  try {
    validateResult.value = await validateRdf(current.value.inhos_no)
    validateVisible.value = true
  } finally {
    validating.value = false
  }
}

const selectRecord = async (row) => {
  current.value = row
  result.value = null
  resultAt.value = ''
  if (row.qc_status === '未质控') return
  loadingResult.value = true
  try {
    const res = await qcResult(row.record_id)
    if (res) {
      result.value = {
        pass: res.passFlag === 1,
        findings: parseJson(res.findingsJson, []),
        trace: parseJson(res.traceJson, null),
        llmUsed: res.llmUsed === 1,
        llmSummary: res.llmSummary
      }
      resultAt.value = res.createdAt
    }
  } finally {
    loadingResult.value = false
  }
}

const runCheck = async () => {
  checking.value = true
  const loadingInstance = ElLoading.service({
    text: '规则引擎+LLM分析中…',
    background: 'rgba(255, 255, 255, 0.7)'
  })
  try {
    const res = await checkQc(current.value.record_id)
    result.value = {
      pass: res.pass,
      findings: res.findings || [],
      trace: res.trace || null,
      llmUsed: res.llmUsed === true || res.llmUsed === 1,
      llmSummary: res.llmSummary
    }
    resultAt.value = ''
    ElMessage[res.pass ? 'success' : 'warning'](
      res.pass ? '内涵质控通过' : `质控不通过，发现 ${result.value.findings.length} 项问题`
    )
    loadRecords()
  } finally {
    loadingInstance.close()
    checking.value = false
  }
}

const parseJson = (str, fallback) => {
  try {
    return JSON.parse(str) ?? fallback
  } catch {
    return fallback
  }
}

onMounted(loadRecords)
</script>

<style scoped>
.qc-toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

.record-pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.result-meta {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
}

.finding-rule {
  font-family: 'SFMono-Regular', Consolas, Menlo, monospace;
  font-size: 12px;
}

.finding-rule-name {
  font-size: 12px;
  color: #606266;
}

.trace-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.trace-label {
  font-size: 13px;
  color: #909399;
  width: 70px;
  flex-shrink: 0;
}

.trace-tag {
  margin-right: 4px;
}

.trace-none {
  color: #c0c4cc;
  font-size: 13px;
}

.trace-concepts {
  font-size: 12px;
  color: #909399;
}

.llm-summary {
  white-space: pre-wrap;
  line-height: 1.8;
  font-size: 13px;
}

.dual-link {
  margin-left: 8px;
  font-size: 12px;
}

.source-chip {
  display: inline-block;
  font-size: 12px;
  color: #409eff;
  background: #ecf5ff;
  border-radius: 3px;
  padding: 1px 8px;
  margin: 1px 4px 1px 0;
  line-height: 1.6;
}

.validate-summary {
  display: flex;
  align-items: center;
  gap: 12px;
}

.validate-meta {
  font-size: 12px;
  color: #909399;
}
</style>
