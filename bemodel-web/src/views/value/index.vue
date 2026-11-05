<template>
  <div
    class="page"
    v-loading="loading"
    element-loading-text="正在同场执行四组实验（A 组为平台真实调用）…"
  >
    <!-- 顶部说明 -->
    <el-card>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="判断标准不是「谁更快」，而是「能不能、靠什么」：传统方式或纯 AI 解决不了的问题，本体论能解决"
      />
    </el-card>

    <!-- MCP 开放:语义层对外只读两件(借鉴 1「查询即应用」) -->
    <el-card style="margin-top: 16px">
      <template #header>
        <div class="exp-header">
          <span class="exp-title">MCP 开放：语义层接入任意 AI 客户端</span>
          <span class="exp-question">问数和口径卡不只长在页面里，也开放成了标准工具</span>
        </div>
      </template>
      <div class="mcp-text">
        <p>本平台已把两项能力按 MCP 标准协议开放为只读工具，Claude 等支持 MCP 的 AI 助手可以直接调用：</p>
        <ul>
          <li><b>ask_data_question（语义问数）</b>——提一个数据问题，返回答案和证据：执行了什么 SQL、查的哪个库、多少行、追溯编号。</li>
          <li><b>get_metric_card（指标口径卡）</b>——按名称或编码查指标的统一定义、计算公式、探针 SQL 和最近实测值。</li>
        </ul>
        <p>本机接入命令（一条即可连上）：</p>
        <pre class="mcp-cmd">claude mcp add --transport http bemodel http://localhost:18080/mcp</pre>
        <el-alert type="warning" :closable="false"
          title="演示期开放：/mcp 端点暂未加鉴权（问数只读，走白名单与脱敏；口径卡实测值来自管理端巡检探针）。公网部署前必须补鉴权。" />
      </div>
    </el-card>

    <!-- 推演沙盘:事前推演(第二幕入口) -->
    <el-card shadow="hover" style="margin-top: 16px; cursor: pointer" @click="goSimulation">
      <template #header>
        <div class="exp-header">
          <span class="exp-title">推演沙盘 · 事前推演</span>
          <span class="exp-question">动手改一个数，先看清会牵连谁，再动真格</span>
        </div>
      </template>
      <div class="sim-entry">
        <p>动手把演示库存清零，沿本体看波及链：哪些医嘱、患者、发药、费用被牵连，巡检项与账实规则怎么翻转。全程演示数据，不碰真实库。</p>
        <el-link type="primary" :underline="false">进入推演 →</el-link>
      </div>
    </el-card>

    <template v-if="data">
      <!-- 实验卡 -->
      <el-card
        v-for="exp in data.experiments"
        :key="exp.key"
        class="exp-card"
        style="margin-top: 16px"
      >
        <template #header>
          <div class="exp-header">
            <span class="exp-title">{{ exp.title }}</span>
            <span class="exp-question">{{ exp.question }}</span>
          </div>
          <div class="exp-actions">
            <el-button
              size="small"
              type="primary"
              plain
              @click="goLab(exp.key)"
            >去AI查询比对真跑</el-button>
          </div>
        </template>
      </el-card>

      <!-- LLM 价值总结 -->
      <el-card v-if="data.llmSummary" style="margin-top: 16px">
        <template #header>
          <div class="card-header">
            <span>价值总结</span>
            <span class="llm-note">由 AI 生成</span>
          </div>
        </template>
        <div class="summary-text">{{ data.llmSummary }}</div>
        <div class="gen-time">生成于 {{ data.generatedAt }}</div>
      </el-card>
    </template>
    <el-card v-else-if="!loading" style="margin-top: 16px">
      <el-empty description="实证数据为空" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { valueCompare } from '../../api/value'

const data = ref(null)
const loading = ref(false)

const load = async () => {
  loading.value = true
  try {
    data.value = await valueCompare()
  } finally {
    loading.value = false
  }
}

const router = useRouter()
// value 页是定稿文案的静态对照;AI查询比对页是同题三组真跑(键映射:SILO 的处置环节=REFUND)
const LAB_KEY_MAP = { GATE: 'GATE', SILO: 'REFUND', ADVERSARIAL: 'ADVERSARIAL', TRAVERSE: 'TRAVERSE' }
const goLab = (key) => router.push({ path: '/lab', query: { exp: LAB_KEY_MAP[key] || key } })
// 推演沙盘:事前推演入口
const goSimulation = () => router.push('/simulation')

onMounted(load)
</script>

<style scoped>
.exp-header {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.exp-title {
  font-size: 15px;
  font-weight: 600;
}

.exp-question {
  font-size: 12px;
  font-weight: normal;
  color: #909399;
}

.llm-note {
  font-size: 12px;
  color: #909399;
  font-weight: normal;
}

.summary-text {
  white-space: pre-wrap;
  line-height: 1.9;
  font-size: 14px;
}

.gen-time {
  margin-top: 10px;
  font-size: 12px;
  color: #c0c4cc;
}

.exp-actions {
  margin-top: 8px;
}

.mcp-text {
  font-size: 13px;
  line-height: 1.8;
  color: #303133;
}

.mcp-text p {
  margin: 0 0 6px;
}

.mcp-text ul {
  margin: 0 0 8px;
  padding-left: 18px;
}

.mcp-cmd {
  margin: 6px 0 10px;
  padding: 8px 10px;
  background: #f4f4f5;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  font-family: monospace;
  font-size: 12px;
}

.sim-entry {
  font-size: 13px;
  line-height: 1.8;
  color: #303133;
}

.sim-entry p {
  margin: 0 0 8px;
}
</style>
