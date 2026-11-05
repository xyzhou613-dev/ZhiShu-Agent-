<template>
  <div class="page trace-page">
    <!-- 查询条：type + key（也承接 /trace?type=QA&key=… 深链） -->
    <div class="trace-lookup">
      <el-select v-model="type" style="width: 150px">
        <el-option label="问数答案 (QA)" value="QA" />
        <el-option label="概念 (CONCEPT)" value="CONCEPT" />
        <el-option label="映射 (MAPPING)" value="MAPPING" />
      </el-select>
      <el-input
        v-model="key"
        :placeholder="keyPlaceholder"
        style="flex: 1"
        @keydown.enter.exact.prevent="load"
      />
      <el-button type="primary" :loading="loading" :disabled="!key.trim()" @click="load">查证据链</el-button>
    </div>

    <div v-if="error" class="trace-empty">
      <el-icon :size="26"><Warning /></el-icon>
      <div>{{ error }}</div>
    </div>

    <!-- 五段式：结论 → 排查路径 → 证据锚点 → 建议动作 → 参考文件 -->
    <template v-else-if="trace">
      <div class="trace-summary">
        <div class="ts-head">
          <el-tag effect="dark" size="small">{{ trace.type }}</el-tag>
          <span class="ts-key">{{ trace.key }}</span>
        </div>
        <div class="ts-body">{{ trace.summary }}</div>
      </div>

      <div class="trace-grid">
        <div class="trace-card">
          <div class="tc-title">排查路径</div>
          <el-timeline class="tc-timeline">
            <el-timeline-item
              v-for="s in trace.spans"
              :key="s.step"
              :timestamp="s.at || ''"
              placement="top"
            >
              <div class="sp-title">{{ s.step }}. {{ s.title }}</div>
              <div class="sp-detail">{{ s.detail }}</div>
              <div v-if="s.actor" class="sp-actor">操作人：{{ s.actor }}</div>
            </el-timeline-item>
          </el-timeline>
        </div>

        <div class="trace-col">
          <div class="trace-card">
            <div class="tc-title">证据锚点（点击跳转，全部平台内可达）</div>
            <div class="tc-anchors">
              <el-button
                v-for="(a, i) in trace.anchors"
                :key="i"
                size="small"
                plain
                @click="$router.push(a.route)"
              >{{ a.label }} →</el-button>
            </div>
          </div>
          <div class="trace-card">
            <div class="tc-title">建议动作</div>
            <ul class="tc-actions">
              <li v-for="(a, i) in trace.actions" :key="i">{{ a }}</li>
            </ul>
          </div>
          <div class="trace-card">
            <div class="tc-title">参考文件</div>
            <div class="tc-anchors">
              <el-button
                v-for="(r, i) in trace.refs"
                :key="i"
                size="small"
                link
                type="primary"
                @click="$router.push(r.route)"
              >{{ r.label }} →</el-button>
            </div>
          </div>
        </div>
      </div>
    </template>

    <div v-else class="trace-empty">
      <el-icon :size="26"><DataLine /></el-icon>
      <div>输入类型与标识（如问数回答里的 traceId、概念 code），逐段还原该结论的证据链</div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getTrace } from '../../api/trace'
import { Warning, DataLine } from '@element-plus/icons-vue'

const route = useRoute()
const type = ref('QA')
const key = ref('')
const trace = ref(null)
const loading = ref(false)
const error = ref('')

const keyPlaceholder = computed(() =>
  ({ QA: 'traceId，形如 QA-20260916…（问数回答的「查证据链」入口可直达）',
     CONCEPT: '概念编码，如 MEDICAL_ORDER',
     MAPPING: '映射 ID（数据源绑定页）' })[type.value] || '标识')

const load = async () => {
  const k = key.value.trim()
  if (!k || loading.value) return
  loading.value = true
  error.value = ''
  trace.value = null
  try {
    trace.value = await getTrace(type.value, k)
  } catch (e) {
    error.value = e?.message || '证据链查询失败'
  } finally {
    loading.value = false
  }
}

// 承接深链：/trace?type=QA&key=…（问数回答「查证据链」/ 各页跳转入口）
onMounted(() => {
  if (route.query.type) type.value = String(route.query.type).toUpperCase()
  if (route.query.key) {
    key.value = String(route.query.key)
    load()
  }
})
</script>

<style scoped>
.trace-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.trace-lookup {
  display: flex;
  gap: 10px;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  padding: 12px 14px;
}

.trace-summary {
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  padding: 14px 16px;
}

.ts-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.ts-key {
  font-family: monospace;
  font-size: 13px;
  color: var(--text-secondary);
}

.ts-body {
  margin-top: 10px;
  font-size: 14px;
  line-height: 1.7;
}

.trace-grid {
  display: grid;
  grid-template-columns: 1.4fr 1fr;
  gap: 14px;
  align-items: start;
}

.trace-col {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.trace-card {
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  padding: 14px 16px;
}

.tc-title {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 12px;
}

.tc-timeline {
  padding-left: 4px;
}

.sp-title {
  font-weight: 600;
  font-size: 13px;
}

.sp-detail {
  margin-top: 4px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-secondary);
  word-break: break-all;
}

.sp-actor {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-secondary);
}

.tc-anchors {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tc-actions {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.9;
  color: var(--text-secondary);
}

.trace-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 60px 0;
  color: var(--text-secondary);
  background: var(--card-bg);
  border: 1px dashed var(--border-color);
  border-radius: var(--radius-lg);
}

@media (max-width: 960px) {
  .trace-grid {
    grid-template-columns: 1fr;
  }
}
</style>
