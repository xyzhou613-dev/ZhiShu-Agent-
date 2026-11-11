<template>
  <el-card class="assets">
    <template #header><b>算力数据来源与使用边界</b><el-button link @click="load">刷新</el-button></template>
    <el-alert type="info" :closable="false" title="模拟、平台账本和外部待接入来源分别登记" description="来源登记不代表采集已经连通。采集频率、观测有效截止时间和最大允许年龄分别管理；账号原始日志和敏感明细不进入通用知识库。" />
    <el-table :data="assets" v-loading="loading" highlight-current-row @row-click="row => selected = row">
      <el-table-column prop="name" label="数据" min-width="190" />
      <el-table-column prop="owner" label="负责人 / 对接人" min-width="180" />
      <el-table-column label="来源阶段" width="140"><template #default="{ row }">{{ sourceText[row.sourceKind] }}</template></el-table-column>
      <el-table-column prop="authoritativeSource" label="权威来源" min-width="190" />
      <el-table-column prop="sensitivity" label="敏感等级" width="120" />
      <el-table-column label="操作" width="120"><template #default="{ row }"><el-button link @click="selected = row">字段</el-button><el-button v-if="['ADMIN','EDITOR'].includes(user.user?.role)" link type="primary" @click.stop="edit(row)">编辑</el-button></template></el-table-column>
    </el-table>
    <template v-if="selected">
      <h4>{{ selected.name }} · 登记版本 {{ selected.version }}</h4>
      <p>更新要求：{{ selected.updateRequirement }}</p><p>质量与冲突处理：{{ selected.qualityRequirement }}</p>
      <p>最大允许年龄：{{ selected.maxAgeSeconds == null ? '尚未设置，须随运行规则确认' : `${selected.maxAgeSeconds} 秒` }}；通用知识库：{{ selected.knowledgeAllowed ? '仅限经过处理的允许内容' : '禁止导入原始明细' }}</p>
      <el-table :data="selected.fields" max-height="380">
        <el-table-column prop="table" label="表" min-width="180" /><el-table-column prop="column" label="字段" min-width="180" /><el-table-column prop="meaning" label="含义" min-width="300" /><el-table-column prop="sensitivity" label="敏感等级" width="120" />
        <el-table-column label="状态含义" min-width="300"><template #default="{ row }">{{ Object.entries(row.states || {}).map(([k,v]) => `${k}：${v}`).join('；') }}</template></el-table-column>
      </el-table>
    </template>
    <el-dialog v-model="editing" title="修改数据来源登记" width="650px">
      <el-form label-width="120px">
        <el-form-item label="负责人"><el-input v-model="form.owner" /></el-form-item>
        <el-form-item label="来源类型"><el-select v-model="form.sourceKind"><el-option v-for="(label,key) in sourceText" :key="key" :value="key" :label="label" /></el-select></el-form-item>
        <el-form-item label="权威来源标识"><el-input v-model="form.authoritativeSource" /></el-form-item>
        <el-form-item label="更新要求"><el-input v-model="form.updateRequirement" type="textarea" /></el-form-item>
        <el-form-item label="最大年龄（秒）"><el-input-number v-model="form.maxAgeSeconds" :min="1" :precision="0" /><el-button link @click="form.maxAgeSeconds = null">待确认</el-button></el-form-item>
        <el-form-item label="敏感等级"><el-select v-model="form.sensitivity"><el-option v-for="s in ['PUBLIC','INTERNAL','SENSITIVE']" :key="s" :value="s" :label="s" /></el-select></el-form-item>
        <el-form-item label="质量与权威口径"><el-input v-model="form.qualityRequirement" type="textarea" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="editing = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存新登记版本</el-button></template>
    </el-dialog>
  </el-card>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../../store/user'
import { listComputeDataAssets, updateComputeDataAsset } from '../../api/compute'
const user = useUserStore(), assets = ref([]), selected = ref(null), loading = ref(false), editing = ref(false), saving = ref(false), form = ref({})
const sourceText = { SIMULATED: '模拟来源', PLATFORM: '平台登记 / 账本', EXTERNAL_PENDING: '外部待接入', CONNECTED: '登记为已接入' }
async function load() { loading.value = true; try { assets.value = await listComputeDataAssets(); if (selected.value) selected.value = assets.value.find(a => a.assetCode === selected.value.assetCode) } finally { loading.value = false } }
function edit(row) { form.value = JSON.parse(JSON.stringify(row)); editing.value = true }
async function save() { saving.value = true; try { await updateComputeDataAsset(form.value.assetCode, form.value); editing.value = false; await load(); ElMessage.success('来源登记已更新；运行时效仍按发布规则检查') } finally { saving.value = false } }
onMounted(load)
</script>
<style scoped>.assets { margin-bottom: 18px; } p { overflow-wrap: anywhere; }</style>
