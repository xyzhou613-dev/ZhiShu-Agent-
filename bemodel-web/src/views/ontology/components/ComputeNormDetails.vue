<template>
  <el-collapse v-if="contracts.length" class="norm-details">
    <el-collapse-item title="算力动作条件、执行结果与 26 条硬约束" name="compute">
      <el-alert title="动作定义与执行结果分别记录" description="这里登记固定动作及进入条件；只有流程实际执行后才会产生回执。规则通过不代表动作已经成功。" type="info" :closable="false" />
      <el-table :data="contracts">
        <el-table-column prop="name" label="动作" width="150" />
        <el-table-column label="执行前条件" min-width="240"><template #default="{ row }">{{ row.contract.preconditions }}</template></el-table-column>
        <el-table-column label="规则" width="170"><template #default="{ row }">{{ row.contract.guardRules?.join('、') }}</template></el-table-column>
        <el-table-column label="失败后处理" min-width="220"><template #default="{ row }">{{ row.contract.failureRoute }}</template></el-table-column>
      </el-table>
      <el-form inline class="result-query" @submit.prevent="lookup">
        <el-form-item label="固定操作编号"><el-input v-model="operationKey" placeholder="流程记录中的 idempotency_key" style="width:340px" /></el-form-item>
        <el-form-item><el-button :loading="loading" :disabled="!operationKey.trim()" @click="lookup">查询实际回执</el-button></el-form-item>
      </el-form>
      <el-empty v-if="queried && !results.length" description="没有该操作的执行记录，不能据此认定已执行" />
      <pre v-if="results.length">{{ JSON.stringify(results, null, 2) }}</pre>
      <el-table :data="constraints">
        <el-table-column prop="contract.id" label="编号" width="75" />
        <el-table-column prop="description" label="必须保证" min-width="260" />
        <el-table-column prop="contract.implementation" label="检查位置" min-width="280" />
        <el-table-column prop="contract.verification" label="验证用例" min-width="200" />
        <el-table-column prop="contract.integration" label="业务接入要求" min-width="260" />
      </el-table>
    </el-collapse-item>
  </el-collapse>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import { listComputeActionContracts, listComputeConstraints, computeActionResults } from '../../../api/compute'
const contracts = ref([]), constraints = ref([]), results = ref([])
const operationKey = ref(''), queried = ref(false), loading = ref(false)
const parse = (row, field) => ({ ...row, contract: typeof row[field] === 'string' ? JSON.parse(row[field]) : row[field] || {} })
onMounted(async () => {
  const [a, c] = await Promise.all([listComputeActionContracts(), listComputeConstraints()])
  contracts.value = a.map(row => parse(row, 'contract_json'))
  constraints.value = c.map(row => parse(row, 'enforcement_json'))
})
async function lookup() {
  loading.value = true
  try { results.value = await computeActionResults(operationKey.value.trim()); queried.value = true }
  finally { loading.value = false }
}
</script>
<style scoped>
.norm-details { margin-bottom: 20px; }
.result-query { margin-top: 18px; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
