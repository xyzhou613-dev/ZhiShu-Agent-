<template>
  <div class="page">
    <!-- 顶部统计 -->
    <el-card class="stats-card" v-loading="loading">
      <div class="stats-row">
        <div class="stat">
          <div class="stat-value" :class="stats.termConflictCount ? 'stat-bad' : 'stat-good'">
            {{ stats.termConflictCount ?? '-' }}
          </div>
          <div class="stat-label">术语分叉</div>
        </div>
        <div class="stat">
          <div class="stat-value" :class="stats.calibreChangeCount ? 'stat-warn' : 'stat-good'">
            {{ stats.calibreChangeCount ?? '-' }}
          </div>
          <div class="stat-label">口径演进变更</div>
        </div>
        <div class="stat">
          <div class="stat-value">{{ stats.scannedTerms ?? '-' }}</div>
          <div class="stat-label">扫描术语</div>
        </div>
        <div class="stat">
          <div class="stat-value">{{ stats.scannedReleases ?? '-' }}</div>
          <div class="stat-label">扫描发布版本</div>
        </div>
        <div class="stat stat-action">
          <el-button type="primary" plain :loading="loading" @click="load">重新扫描</el-button>
        </div>
      </div>
      <div class="scan-tip">
        检测口径为真实数据，不含任何推断占比：<b>术语分叉</b> = 同一说法在不同产品/编码体系下挂到了不同概念
        （同一句话在各系统里说的不是同一件事）；<b>口径演进</b> = 相邻发布版本间概念定义与指标口径的字段级变更。
        术语数据来自统一口径，版本数据来自发布快照。
      </div>
    </el-card>

    <!-- 术语分叉 -->
    <el-card class="section-card">
      <template #header>
        <div class="section-head">
          <span>术语分叉</span>
          <span class="section-sub">同一说法 → 多个概念，需要治理统一</span>
        </div>
      </template>
      <el-empty
        v-if="!(scan.termConflicts || []).length"
        description="当前所有术语均唯一映射到一个概念，无分叉"
        :image-size="70"
      />
      <div v-else class="conflict-list">
        <div v-for="c in scan.termConflicts" :key="c.term" class="conflict-card">
          <div class="conflict-term">
            「{{ c.term }}」
            <el-tag size="small" type="danger" effect="plain">挂到 {{ c.conceptCount }} 个概念</el-tag>
          </div>
          <div class="conflict-usages">
            <div v-for="(u, i) in c.usages" :key="i" class="usage-row">
              <el-tag size="small" effect="plain" type="success">{{ u.conceptCode }}</el-tag>
              <span class="usage-name">{{ u.conceptName }}</span>
              <el-tag size="small" effect="plain">来源：{{ u.sourceProduct }}</el-tag>
              <el-tag v-if="u.codeSystem" size="small" effect="plain">{{ u.codeSystem }}</el-tag>
              <el-tag size="small" effect="plain" :type="u.termType === 'STANDARD' ? 'warning' : 'info'">
                {{ u.termType === 'STANDARD' ? '标准' : '别名' }}
              </el-tag>
            </div>
          </div>
          <div class="conflict-actions">
            <el-button size="small" link type="primary" @click="$router.push('/glossary')">
              去统一口径处置
            </el-button>
          </div>
        </div>
      </div>
    </el-card>

    <!-- 口径演进 -->
    <el-card class="section-card">
      <template #header>
        <div class="section-head">
          <span>口径演进</span>
          <span class="section-sub">发布版本间的概念定义 / 指标口径变更记录</span>
        </div>
      </template>
      <el-empty
        v-if="!(scan.calibreChanges || []).length"
        description="发布版本间没有概念/指标口径变更"
        :image-size="70"
      />
      <div v-else class="calibre-list">
        <div v-for="(c, i) in scan.calibreChanges" :key="i" class="calibre-item">
          <div class="calibre-head">
            <el-tag size="small" type="success" effect="dark">{{ c.versionTag }}</el-tag>
            <el-tag size="small" effect="plain" :type="c.kind === '概念' ? 'primary' : 'warning'">
              {{ c.kind }}
            </el-tag>
            <b>{{ c.code }}</b>
            <span class="calibre-name">{{ c.name }}</span>
            <span class="calibre-field">{{ c.field }}变更</span>
            <span class="calibre-time">{{ (c.releasedAt || '').replace('T', ' ').slice(0, 16) }}</span>
          </div>
          <div class="calibre-diff">
            <div class="cd-old"><s>{{ c.oldVal }}</s></div>
            <div class="cd-new">{{ c.newVal }}</div>
          </div>
        </div>
      </div>
      <div class="calibre-foot" v-if="(scan.calibreChanges || []).length">
        变更前后的完整对比可在
        <router-link to="/ontology?tab=releases">本体管理 - 版本与 LLM - 变更图谱</router-link>
        中查看
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { scanDrift } from '../../api/drift'

const loading = ref(false)
const raw = ref({})

const scan = computed(() => ({
  termConflicts: raw.value.termConflicts || [],
  calibreChanges: raw.value.calibreChanges || []
}))
const stats = computed(() => raw.value.stats || {})

const load = async () => {
  loading.value = true
  try {
    raw.value = await scanDrift()
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.stats-card {
  margin-bottom: 12px;
}

.stats-row {
  display: flex;
  gap: 40px;
  align-items: center;
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
}

.stat-value.stat-bad {
  color: var(--el-color-danger);
}

.stat-value.stat-warn {
  color: var(--el-color-warning);
}

.stat-value.stat-good {
  color: var(--el-color-success);
}

.stat-label {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 2px;
}

.stat-action {
  margin-left: auto;
}

.scan-tip {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--border-color);
  font-size: 12px;
  line-height: 1.8;
  color: var(--text-secondary);
}

.section-card {
  margin-bottom: 12px;
}

.section-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.section-sub {
  font-size: 12px;
  color: var(--text-muted);
  font-weight: 400;
}

/* 术语分叉 */
.conflict-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.conflict-card {
  border: 1px solid var(--el-color-danger-light-7, #fde2e2);
  background: var(--el-color-danger-light-9, #fef0f0);
  border-radius: 8px;
  padding: 12px 16px;
}

.conflict-term {
  font-size: 14px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
}

.conflict-usages {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.usage-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.usage-name {
  color: var(--text-secondary);
}

.conflict-actions {
  margin-top: 8px;
  text-align: right;
}

/* 口径演进 */
.calibre-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.calibre-item {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 12px 16px;
}

.calibre-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 13px;
}

.calibre-name {
  color: var(--text-secondary);
}

.calibre-field {
  color: var(--el-color-warning);
  font-size: 12px;
}

.calibre-time {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-muted);
}

.calibre-diff {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
  line-height: 1.7;
}

.cd-old {
  color: var(--text-muted);
  padding: 6px 10px;
  background: var(--el-bg-color-page, #f7f8fa);
  border-radius: 6px;
}

.cd-old s {
  color: var(--el-color-danger);
  opacity: 0.75;
}

.cd-new {
  padding: 6px 10px;
  background: var(--el-color-success-light-9, #f0f9eb);
  border-radius: 6px;
  color: var(--text-primary);
}

.calibre-foot {
  margin-top: 12px;
  font-size: 12px;
  color: var(--text-muted);
}

.calibre-foot a {
  color: var(--primary);
  font-weight: 600;
}
</style>
