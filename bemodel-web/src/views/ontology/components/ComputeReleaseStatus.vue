<template>
  <el-card class="compute-release" v-loading="loading">
    <template #header><b>算力运行规则</b><el-button link @click="load">刷新</el-button></template>
    <el-alert :type="state.active?.id ? 'success' : 'warning'" :closable="false"
      :title="state.active?.id ? `运行中：${state.active.source_version_tag} · 集合 ${state.active.id}` : '尚未激活运行规则集合'"
      description="平台版本发布与运行规则生效分别记录。14 条规则全部同步后才切换，历史快照保留；重启不会自动发布草稿。" />
    <p>规则实现：{{ preflight.evaluatorVersion }}；计算口径：{{ preflight.formulaVersion }}</p>
    <el-collapse v-if="preflight.errors?.length"><el-collapse-item title="发布前需要处理的问题"><div v-for="error in preflight.errors" :key="error">{{ error }}</div></el-collapse-item></el-collapse>
    <el-table :data="state.history || []">
      <el-table-column prop="version_tag" label="平台发布" width="110" />
      <el-table-column prop="status" label="同步状态" width="120" />
      <el-table-column prop="active_set_id" label="运行集合编号" width="120" />
      <el-table-column prop="attempt_count" label="同步尝试次数" width="120" />
      <el-table-column prop="last_error" label="失败原因" min-width="240" />
      <el-table-column label="操作" width="120"><template #default="{ row }"><el-button v-if="user.canReview && row.status !== 'SYNCED'" link type="primary" @click="retry(row.release_id)">重试原快照</el-button></template></el-table-column>
    </el-table>
    <el-alert v-if="conflicts.some(c => c.status === 'OPEN')" title="存在未解决的规则冲突，发布会被阻断" type="error" :closable="false" />
    <el-table v-if="conflicts.length" :data="conflicts"><el-table-column prop="description" label="冲突点" /><el-table-column prop="priority" label="优先级" /><el-table-column prop="status" label="状态" /><el-table-column prop="resolution" label="处理结论" /><el-table-column prop="resolved_by" label="处理人" /></el-table>
  </el-card>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import { useUserStore } from '../../../store/user'
import { computeReleaseHistory, computeReleasePreflight, syncComputeRelease, listComputeRuleConflicts } from '../../../api/compute'
const user = useUserStore(), state = ref({}), preflight = ref({}), conflicts = ref([]), loading = ref(false)
async function load() {
  loading.value = true
  try { [state.value, preflight.value, conflicts.value] = await Promise.all([computeReleaseHistory(), computeReleasePreflight(), listComputeRuleConflicts()]) }
  finally { loading.value = false }
}
async function retry(id) { await syncComputeRelease(id); await load() }
onMounted(load)
</script>
<style scoped>.compute-release { margin-bottom: 20px; }</style>
