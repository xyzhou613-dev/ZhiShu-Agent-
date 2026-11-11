<template>
  <el-card class="resource-overview" v-loading="loading">
    <template #header><b>算力卡组与容量预测</b><el-button link @click="load">刷新当前账本</el-button></template>
    <el-alert title="只读查询与预测，不批准申请，也不留卡" type="info" :closable="false" description="账本空闲仍须检查观测、健康和任务适配。使用过去观测时间也不等于历史账本回放。部署使用卡来自整组配置，物理占用另看每卡观测。" />
    <p>计算口径 {{ groups.formulaVersion }}；观测时点 {{ groups.evidenceAsOf }}；账本读取 {{ groups.ledgerReadAt }}</p>
    <el-table :data="groups.groups || []">
      <el-table-column type="expand"><template #default="{ row }">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="持有使用卡 / 保留卡">{{ row.holderUsage?.usedGpuUuids?.join('、') || '无' }} / {{ row.holderUsage?.retainedGpuUuids?.join('、') || '无' }}</el-descriptions-item>
          <el-descriptions-item label="预留使用卡 / 随组保留卡">{{ row.pendingUsage?.usedGpuUuids?.join('、') || '尚未选择' }} / {{ row.pendingUsage?.retainedGpuUuids?.join('、') || '无' }}</el-descriptions-item>
          <el-descriptions-item label="持有申请">{{ row.holderRequest || '无' }}</el-descriptions-item>
          <el-descriptions-item label="预留申请">{{ row.pendingRequest || '无' }}</el-descriptions-item>
          <el-descriptions-item label="固定恢复责任">{{ row.restoreResponsibility || '未登记' }}</el-descriptions-item>
          <el-descriptions-item label="每卡观测"><div v-for="card in row.cards" :key="card.uuid">{{ card.index }} · {{ card.uuid }} · {{ card.observation || '无有效观测' }}</div></el-descriptions-item>
        </el-descriptions>
      </template></el-table-column>
      <el-table-column prop="code" label="卡组" width="85" />
      <el-table-column label="成员卡" min-width="140"><template #default="{ row }">{{ row.cards.map(c => c.index).join('、') }}</template></el-table-column>
      <el-table-column label="实际持有" min-width="180"><template #default="{ row }">{{ row.holder ? `${row.holder.kind} #${row.holder.id}` : '无' }}</template></el-table-column>
      <el-table-column label="待生效预留" min-width="150"><template #default="{ row }">{{ row.reservation ? `分配 #${row.reservation.allocationId}` : '无' }}</template></el-table-column>
      <el-table-column label="账本空闲" width="100"><template #default="{ row }">{{ row.ledgerAvailable && row.ledgerConsistent ? '是' : '否' }}</template></el-table-column>
      <el-table-column label="不能直接分配的原因" min-width="240"><template #default="{ row }">{{ row.reasons.join('、') || '当前只读检查未发现阻断，仍需申请批准' }}</template></el-table-column>
    </el-table>
    <h4>基线与预留预测</h4>
    <el-form inline @submit.prevent="forecast">
      <el-form-item label="作业编号"><el-input-number v-model="jobId" :min="1" :precision="0" /></el-form-item>
      <el-form-item label="方案中暂停的部署"><el-input-number v-model="pausedDeploymentId" :min="1" :precision="0" /></el-form-item>
      <el-form-item label="准备恢复的修订"><el-input-number v-model="restoreRevisionId" :min="1" :precision="0" /></el-form-item>
      <el-form-item><el-button :loading="forecasting" @click="forecast">读取账本并预测</el-button></el-form-item>
    </el-form>
    <p>未选择作业时显示当前基线与有效预留。已预留作业只计一次；待生效和占用都计入，释放与撤销不计入。省略的临时/持久字节按提交规格的可选字段默认零，缺少内存不补零。</p>
    <el-descriptions v-if="capacity.projectedBytes" :column="1" border>
      <el-descriptions-item label="扣除系统保留后的容量">{{ format(capacity.capacityBytes) }}</el-descriptions-item>
      <el-descriptions-item label="方案后的投影">{{ format(capacity.projectedBytes) }} · {{ capacity.withinCapacity ? '容量内' : '超过容量' }}</el-descriptions-item>
      <el-descriptions-item v-if="capacity.requiresStopConfirmation" label="尚需确认">暂停部署仍可能实际运行；确认停服前不能把预计释放资源交给新任务</el-descriptions-item>
      <el-descriptions-item v-if="capacity.restore" label="恢复后的投影">{{ format(capacity.restore.projectedBytes) }} · {{ capacity.restore.withinCapacity ? '容量内' : '超过容量' }}；仍须整组健康检查</el-descriptions-item>
      <el-descriptions-item v-if="capacity.gpuDemand" label="GPU / 显存要求">{{ capacity.gpuDemand }}。显存逐卡判断，不将多卡显存相加满足单卡需求；UNKNOWN 表示未确认。</el-descriptions-item>
    </el-descriptions>
  </el-card>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import { queryComputeGroups, queryComputeCapacity } from '../../api/compute'
const groups = ref({}), capacity = ref({}), loading = ref(false), forecasting = ref(false)
const jobId = ref(), pausedDeploymentId = ref(), restoreRevisionId = ref()
function format(b) { return `内存 ${(b.memoryBytes / 1073741824).toFixed(2)} GiB，临时存储 ${(b.temporaryBytes / 1073741824).toFixed(2)} GiB，持久存储 ${(b.persistentBytes / 1073741824).toFixed(2)} GiB` }
async function forecast() {
  forecasting.value = true
  try { capacity.value = await queryComputeCapacity({ jobId: jobId.value || undefined, pausedDeploymentId: pausedDeploymentId.value || undefined, restoreRevisionId: restoreRevisionId.value || undefined }) }
  finally { forecasting.value = false }
}
async function load() { loading.value = true; try { groups.value = await queryComputeGroups(); await forecast() } finally { loading.value = false } }
onMounted(load)
</script>
<style scoped>.resource-overview { margin-bottom: 18px; } p { overflow-wrap: anywhere; }</style>
