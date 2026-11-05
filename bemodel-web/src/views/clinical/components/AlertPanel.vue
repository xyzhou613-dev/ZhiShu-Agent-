<template>
  <div>
    <el-alert
      type="info"
      :closable="false"
      title="检验报告异常（危急值）且病案无对应诊断、无处置医嘱时自动生成预警。判定依据：规则 RULE-QC-003 + 公理 AX-004（检验报告支撑诊断）。预警进入链路追溯体系统一处置。"
      style="margin-bottom: 12px"
    />
    <div class="alert-toolbar">
      <el-button type="danger" :loading="detecting" @click="runDetect">执行预警检测</el-button>
    </div>
    <el-card>
      <el-table :data="alerts" v-loading="loading" highlight-current-row @row-click="openDetail">
        <el-table-column prop="refNo" label="单号" width="210" />
        <el-table-column prop="title" label="标题" min-width="280" show-overflow-tooltip />
        <el-table-column label="患者 / 科室" width="150">
          <template #default="{ row }">
            {{ payloadOf(row).patient || '-' }} / {{ payloadOf(row).dept || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === '待处置' ? 'danger' : 'info'">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="occurredAt" label="发生时间" width="170" />
      </el-table>
      <el-empty v-if="!alerts.length && !loading" description="暂无预警" />
    </el-card>

    <!-- 预警详情 -->
    <el-dialog v-model="detailVisible" :title="`预警详情：${currentAlert?.refNo || ''}`" width="560px">
      <template v-if="currentAlert">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="患者">{{ payloadOf(currentAlert).patient || '-' }}</el-descriptions-item>
          <el-descriptions-item label="住院号">{{ payloadOf(currentAlert).inhos_no || '-' }}</el-descriptions-item>
          <el-descriptions-item label="科室">{{ payloadOf(currentAlert).dept || '-' }}</el-descriptions-item>
          <el-descriptions-item label="异常项目">{{ payloadOf(currentAlert).item || '-' }}</el-descriptions-item>
          <el-descriptions-item label="报告单号">{{ payloadOf(currentAlert).report_id || '-' }}</el-descriptions-item>
          <el-descriptions-item label="报告时间">{{ payloadOf(currentAlert).report_time || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-divider content-position="left">这条预警是怎么产生的</el-divider>
        <div class="alert-source">
          检验报告（LIS·{{ payloadOf(currentAlert).report_id || '-' }}）结果异常
          → 病案（EMR）中无对应诊断 → HIS 中无处置医嘱 → 触发预警。三处数据经本体概念对齐后联动判定。
        </div>
        <el-alert
          v-if="payloadOf(currentAlert).suggestion"
          type="warning"
          :closable="false"
          style="margin-top: 12px"
          :title="`处置建议：${payloadOf(currentAlert).suggestion}`"
        />
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { detectAlerts } from '../../../api/clinical'
import { listLinks } from '../../../api/link'

const alerts = ref([])
const loading = ref(false)
const detecting = ref(false)

const loadAlerts = async () => {
  loading.value = true
  try {
    alerts.value = (await listLinks('ALERT', undefined, 1, 200)).list
  } finally {
    loading.value = false
  }
}

const runDetect = async () => {
  detecting.value = true
  try {
    const res = await detectAlerts()
    ElMessage.success(
      `异常报告${res.abnormalReports}份：已处置${res.handled}份，新增预警${res.createdCount}条`
    )
    loadAlerts()
  } finally {
    detecting.value = false
  }
}

const payloadOf = (row) => {
  try {
    return JSON.parse(row.payload) || {}
  } catch {
    return {}
  }
}

const detailVisible = ref(false)
const currentAlert = ref(null)

const openDetail = (row) => {
  currentAlert.value = row
  detailVisible.value = true
}

onMounted(loadAlerts)
</script>

<style scoped>
.alert-toolbar {
  margin-bottom: 12px;
}

.alert-source {
  font-size: 13px;
  color: #606266;
  line-height: 1.9;
}
</style>
