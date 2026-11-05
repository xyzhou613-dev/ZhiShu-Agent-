<template>
  <div class="page">
    <el-tabs v-model="activeTab" class="clinical-tabs">
      <el-tab-pane label="病案质控" name="qc">
        <QcPanel />
      </el-tab-pane>
      <el-tab-pane label="危重症预警" name="alerts" lazy>
        <AlertPanel />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import QcPanel from './components/QcPanel.vue'
import AlertPanel from './components/AlertPanel.vue'

const activeTab = ref('qc')
</script>

<style scoped>
.clinical-tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}
</style>
