<template>
  <div class="page">
    <!-- 链路节点列表 -->
    <el-card>
      <div class="filter-bar">
        <el-select v-model="filterType" style="width: 160px" @change="onFilterChange">
          <el-option label="全部类型" value="" />
          <el-option v-for="(label, key) in nodeTypeMap" :key="key" :label="label" :value="key" />
        </el-select>
        <el-select
          v-model="filterConcept"
          placeholder="全部概念"
          clearable
          filterable
          style="width: 240px"
          @change="onFilterChange"
        >
          <el-option
            v-for="c in conceptStore.concepts"
            :key="c.code"
            :label="`${c.name}（${c.code}）`"
            :value="c.code"
          />
        </el-select>
        <el-tooltip content="只读角色无写权限" :disabled="!userStore.isViewer" placement="top">
          <span>
            <el-button type="primary" plain :disabled="userStore.isViewer" @click="openCreate">新增节点</el-button>
          </span>
        </el-tooltip>
        <el-button type="warning" @click="openImpact">变更影响评估</el-button>
        <el-tooltip content="只读角色无写权限" :disabled="!userStore.isViewer" placement="top">
          <span>
            <el-button type="danger" plain :disabled="userStore.isViewer" :loading="autoTicketing" @click="runAutoTicket">
              异常检测转工单
            </el-button>
          </span>
        </el-tooltip>
      </div>
      <el-table :data="nodes" v-loading="loading" highlight-current-row @row-click="openDetail">
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag :type="nodeTypeTag(row.nodeType)">{{ nodeTypeMap[row.nodeType] || row.nodeType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="refNo" label="单号" width="160" />
        <el-table-column prop="title" label="标题" min-width="240" show-overflow-tooltip />
        <el-table-column prop="conceptCode" label="关联概念" width="130" />
        <el-table-column prop="status" label="状态" width="90" />
        <el-table-column prop="occurredAt" label="发生时间" width="170" />
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button
              v-if="row.nodeType === 'TICKET'"
              size="small"
              type="primary"
              @click.stop="goCs(row)"
            >智能诊断</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-if="nodeTotal > 0"
        class="node-pager"
        small
        layout="total, prev, pager, next"
        :current-page="nodePage"
        :page-size="pageSize"
        :total="nodeTotal"
        @current-change="loadNodes"
      />
    </el-card>

    <!-- 概念链路视图（全生命周期流水线） -->
    <el-card style="margin-top: 16px">
      <template #header>
        <div class="card-header">
          <span>概念链路视图</span>
          <el-select v-model="chainConcept" filterable style="width: 240px" @change="loadChain">
            <el-option
              v-for="c in conceptStore.concepts"
              :key="c.code"
              :label="`${c.name}（${c.code}）`"
              :value="c.code"
            />
          </el-select>
        </div>
      </template>
      <div v-if="chainConclusion" class="conclusion-bar" :class="`level-${chainConclusion.level}`">
        {{ chainConclusion.text }}
      </div>
      <el-row :gutter="12" class="health-row">
        <el-col :span="4">
          <div
            class="health-card"
            :class="{ active: activeLane === 'TICKET' }"
            @click="onLaneClick('TICKET')"
          >
            <div class="health-value danger">{{ stats.openTickets }}</div>
            <div class="health-label">未结工单</div>
          </div>
        </el-col>
        <el-col :span="4">
          <div
            class="health-card"
            :class="{ active: activeLane === 'REQUIREMENT' }"
            @click="onLaneClick('REQUIREMENT')"
          >
            <div class="health-value">{{ stats.requirement }}</div>
            <div class="health-label">需求</div>
          </div>
        </el-col>
        <el-col :span="4">
          <div
            class="health-card"
            :class="{ active: activeLane === 'CHANGE' }"
            @click="onLaneClick('CHANGE')"
          >
            <div class="health-value">{{ stats.change }}</div>
            <div class="health-label">研发变更</div>
          </div>
        </el-col>
        <el-col :span="4">
          <div
            class="health-card"
            :class="{ active: activeLane === 'TESTCASE' }"
            @click="onLaneClick('TESTCASE')"
          >
            <div class="health-value">{{ stats.testcase }}</div>
            <div class="health-label">测试用例</div>
          </div>
        </el-col>
        <el-col :span="4">
          <div
            class="health-card"
            :class="{ active: activeLane === 'DEPLOY' }"
            @click="onLaneClick('DEPLOY')"
          >
            <div class="health-value">{{ stats.deploy }}</div>
            <div class="health-label">发布</div>
          </div>
        </el-col>
        <el-col :span="4">
          <div
            class="health-card"
            :class="{ active: activeLane === 'ALERT' }"
            @click="onLaneClick('ALERT')"
          >
            <div class="health-value" :class="{ danger: stats.alert > 0 }">
              {{ stats.alert }}
            </div>
            <div class="health-label">危重预警</div>
          </div>
        </el-col>
      </el-row>
      <div class="pipeline-row" v-loading="chainLoading">
        <div
          v-for="lane in pipelineLanes"
          :key="lane.key"
          class="chain-col"
          :class="{ active: activeLane === lane.key }"
          @click="onLaneClick(lane.key)"
        >
          <div class="lane-head">
            <el-badge :value="chain[lane.key]?.length || 0" :type="nodeTypeTag(lane.key)">
              <span class="chain-title" :class="{ 'alert-title': lane.key === 'ALERT' }">
                {{ lane.label }}
              </span>
            </el-badge>
            <el-link
              v-if="laneAction(lane.key)"
              type="primary"
              :underline="false"
              class="lane-action"
              @click.stop="laneAction(lane.key).go"
            >去处理</el-link>
          </div>
          <el-timeline class="chain-timeline">
            <el-timeline-item
              v-for="item in chain[lane.key] || []"
              :key="item.id"
              :timestamp="item.occurredAt"
              placement="top"
            >
              <div class="chain-item-title">{{ item.title }}</div>
              <div class="chain-item-ref">{{ item.refNo }} · {{ item.status }}</div>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-if="!(chain[lane.key] || []).length" description="无节点" :image-size="40" />
        </div>
      </div>
    </el-card>

    <!-- 节点详情抽屉 -->
    <el-drawer v-model="drawerVisible" :title="`节点详情：${currentNode?.refNo || ''}`" size="45%">
      <el-descriptions v-if="currentNode" :column="1" border>
        <el-descriptions-item label="类型">
          <el-tag :type="nodeTypeTag(currentNode.nodeType)">
            {{ nodeTypeMap[currentNode.nodeType] || currentNode.nodeType }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="单号">{{ currentNode.refNo }}</el-descriptions-item>
        <el-descriptions-item label="标题">{{ currentNode.title }}</el-descriptions-item>
        <el-descriptions-item label="关联概念">{{ currentNode.conceptCode }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ currentNode.status }}</el-descriptions-item>
        <el-descriptions-item label="发生时间">{{ currentNode.occurredAt }}</el-descriptions-item>
      </el-descriptions>
      <template v-if="currentNode?.payload">
        <el-divider content-position="left">业务负载（payload）</el-divider>
        <pre class="payload-block">{{ formatPayload(currentNode.payload) }}</pre>
      </template>

      <template v-if="currentNode">
        <el-divider content-position="left">链路追溯</el-divider>
        <div v-loading="traceLoading">
          <div class="trace-block">
            <div class="trace-head">上游（根因 / 来源方向）</div>
            <el-empty v-if="!upstreamChain.length" description="无上游关联" :image-size="40" />
            <div
              v-for="item in upstreamChain"
              :key="item.rel.id"
              class="trace-row"
              @click="openDetail(item.node)"
            >
              <el-tag size="small" :type="nodeTypeTag(item.node.nodeType)">
                {{ nodeTypeMap[item.node.nodeType] || item.node.nodeType }}
              </el-tag>
              <span class="trace-ref">{{ item.node.refNo }}</span>
              <span class="trace-name">{{ item.node.title }}</span>
              <span class="trace-rel">—{{ relTypeShort[item.rel.relType] || item.rel.relType }}→ 本节点</span>
            </div>
          </div>
          <div class="trace-block">
            <div class="trace-head">下游（影响 / 闭环方向）</div>
            <el-empty v-if="!downstreamChain.length" description="无下游关联" :image-size="40" />
            <div
              v-for="item in downstreamChain"
              :key="item.rel.id"
              class="trace-row"
              @click="openDetail(item.node)"
            >
              <span class="trace-rel">本节点 —{{ relTypeShort[item.rel.relType] || item.rel.relType }}→</span>
              <el-tag size="small" :type="nodeTypeTag(item.node.nodeType)">
                {{ nodeTypeMap[item.node.nodeType] || item.node.nodeType }}
              </el-tag>
              <span class="trace-ref">{{ item.node.refNo }}</span>
              <span class="trace-name">{{ item.node.title }}</span>
            </div>
          </div>

          <template v-if="directRels.length">
            <div class="trace-head">直接关联</div>
            <div v-for="rel in directRels" :key="rel.id" class="trace-row trace-rel-row">
              <span class="trace-rel-text">
                {{ rel.fromRefNo }} —{{ relTypeShort[rel.relType] || rel.relType }}→ {{ rel.toRefNo }}
              </span>
              <span v-if="rel.remark" class="trace-remark">{{ rel.remark }}</span>
              <el-button size="small" type="danger" plain link @click="removeRel(rel)">删除</el-button>
            </div>
          </template>

          <div class="trace-head">添加关联</div>
          <div class="rel-form">
            <el-radio-group v-model="relForm.direction">
              <el-radio value="down">对方 → 本节点（本节点为下游）</el-radio>
              <el-radio value="up">本节点 → 对方（本节点为上游）</el-radio>
            </el-radio-group>
            <div class="rel-form-row">
              <el-select v-model="relForm.relType" style="width: 220px">
                <el-option v-for="(label, key) in relTypeMap" :key="key" :label="label" :value="key" />
              </el-select>
              <el-select
                v-model="relForm.targetRefNo"
                filterable
                placeholder="选择关联节点"
                style="flex: 1"
              >
                <el-option
                  v-for="n in relCandidates"
                  :key="n.refNo"
                  :label="`${n.refNo} ${n.title}`"
                  :value="n.refNo"
                />
              </el-select>
              <el-button type="primary" :loading="relSaving" @click="submitRel">保存</el-button>
            </div>
            <el-input
              v-model="relForm.remark"
              placeholder="关系说明（可选），如：RCA-20260913 定位的根因"
              size="small"
            />
          </div>
        </div>
      </template>
    </el-drawer>

    <!-- 新增节点 -->
    <el-dialog v-model="createVisible" title="新增链路节点" width="520px">
      <el-form :model="createForm" label-width="90px">
        <el-form-item label="节点类型" required>
          <el-select v-model="createForm.nodeType" style="width: 100%">
            <el-option v-for="(label, key) in nodeTypeMap" :key="key" :label="label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item label="单号" required>
          <el-input v-model="createForm.refNo" placeholder="如 T-20260901-001" />
        </el-form-item>
        <el-form-item label="标题" required>
          <el-input v-model="createForm.title" />
        </el-form-item>
        <el-form-item label="关联概念" required>
          <el-select v-model="createForm.conceptCode" filterable style="width: 100%">
            <el-option
              v-for="c in conceptStore.concepts"
              :key="c.code"
              :label="`${c.name}（${c.code}）`"
              :value="c.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-input v-model="createForm.status" placeholder="如 处理中 / 已上线" />
        </el-form-item>
        <el-form-item label="发生时间">
          <el-date-picker
            v-model="createForm.occurredAt"
            type="datetime"
            style="width: 100%"
            value-format="YYYY-MM-DDTHH:mm:ss"
          />
        </el-form-item>
        <el-form-item label="payload">
          <el-input
            v-model="createForm.payload"
            type="textarea"
            :rows="3"
            placeholder='JSON 字符串，如 {"patient":"张三"}'
          />
        </el-form-item>
        <el-form-item label="关联节点">
          <el-select
            v-model="createForm.linkTarget"
            clearable
            filterable
            placeholder="可选：将该节点挂到已有链路上"
            style="width: 100%"
          >
            <el-option
              v-for="n in allNodes"
              :key="n.refNo"
              :label="`${n.refNo} ${n.title}`"
              :value="n.refNo"
            />
          </el-select>
        </el-form-item>
        <template v-if="createForm.linkTarget">
          <el-form-item label="关系类型">
            <el-select v-model="createForm.linkRelType" style="width: 100%">
              <el-option v-for="(label, key) in relTypeMap" :key="key" :label="label" :value="key" />
            </el-select>
          </el-form-item>
          <el-form-item label="方向">
            <el-radio-group v-model="createForm.linkDirection">
              <el-radio value="down">对方 → 新节点（新节点为下游）</el-radio>
              <el-radio value="up">新节点 → 对方（新节点为上游）</el-radio>
            </el-radio-group>
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">保存</el-button>
      </template>
    </el-dialog>

    <!-- 变更影响评估 -->
    <el-dialog v-model="impactVisible" title="变更影响评估" width="60%">
      <el-form label-width="90px">
        <el-form-item label="概念" required>
          <el-select
            v-model="impactForm.conceptCode"
            filterable
            style="width: 100%"
            placeholder="选择要变更的概念"
          >
            <el-option
              v-for="c in conceptStore.concepts"
              :key="c.code"
              :label="`${c.name}（${c.code}）`"
              :value="c.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="影响深度">
          <el-input-number v-model="impactForm.depth" :min="1" :max="6" />
          <span class="depth-tip">沿概念关系向外扩散的跳数（1-6）</span>
        </el-form-item>
        <el-form-item label="变更描述" required>
          <el-input
            v-model="impactForm.changeDesc"
            type="textarea"
            :rows="3"
            placeholder="例如：LIS检验申请状态字典新增状态码R（已拒收）"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="warning" :loading="analyzing" @click="runImpact">开始评估</el-button>
        </el-form-item>
      </el-form>

      <template v-if="impactResult">
        <el-alert
          v-if="impactResult.impactCapped"
          type="warning"
          :closable="false"
          show-icon
          title="影响面较大，结果已截断"
          style="margin-bottom: 12px"
        />
        <el-descriptions :column="1" border>
          <el-descriptions-item label="概念">
            {{ impactResult.conceptName }}（{{ impactResult.conceptCode }}）
          </el-descriptions-item>
          <el-descriptions-item label="变更描述">{{ impactResult.changeDesc }}</el-descriptions-item>
          <el-descriptions-item label="影响深度">{{ impactResult.impactDepth ?? impactForm.depth }} 跳</el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">受影响产品库映射</el-divider>
        <div class="impact-tables">
          <el-card
            v-for="(cols, dsCode) in impactResult.affectedTables || {}"
            :key="dsCode"
            class="impact-table-card"
            shadow="never"
          >
            <template #header>
              <span class="impact-ds-title">{{ dsCode }}</span>
            </template>
            <div v-for="(col, i) in cols" :key="i" class="impact-table-row">{{ col }}</div>
          </el-card>
          <el-empty
            v-if="!Object.keys(impactResult.affectedTables || {}).length"
            description="无受影响映射"
            :image-size="40"
          />
        </div>

        <el-divider content-position="left">关联指标</el-divider>
        <div v-if="impactResult.affectedMetrics?.length" class="impact-metrics">
          <el-tag
            v-for="mt in impactResult.affectedMetrics"
            :key="mt.code"
            type="warning"
            effect="plain"
          >{{ mt.name }}（{{ mt.code }}）</el-tag>
        </div>
        <span v-else class="impact-none">无</span>

        <el-divider content-position="left">测试用例覆盖</el-divider>
        <el-table
          v-if="impactResult.coveredTestcases?.length"
          :data="impactResult.coveredTestcases"
          size="small"
          border
        >
          <el-table-column prop="refNo" label="单号" width="200" />
          <el-table-column prop="title" label="标题" />
          <el-table-column prop="status" label="状态" width="100" />
        </el-table>
        <el-alert
          v-else
          type="warning"
          :closable="false"
          title="当前无测试用例覆盖该概念，必须补充"
        />

        <el-divider content-position="left">上下游概念（按跳分层）</el-divider>
        <div v-if="impactLayerKeys.length" class="impact-list">
          <div v-for="depth in impactLayerKeys" :key="depth" class="impact-layer-row">
            <span class="impact-layer-depth">第 {{ depth }} 跳</span>
            <el-tag
              v-for="c in impactResult.impactLayers[depth]"
              :key="c"
              size="small"
              effect="plain"
              class="impact-layer-tag"
            >{{ c }}</el-tag>
          </div>
        </div>
        <div v-else-if="impactResult.relatedConcepts?.length" class="impact-list">
          <div v-for="(rc, i) in impactResult.relatedConcepts" :key="i" class="impact-list-row">
            {{ rc }}
          </div>
        </div>
        <span v-else class="impact-none">无</span>

        <el-divider content-position="left">同类历史变更/工单</el-divider>
        <div v-if="impactResult.history?.length" class="impact-list">
          <div v-for="h in impactResult.history" :key="h.refNo" class="impact-list-row">
            <el-tag size="small" :type="nodeTypeTag(h.type)">{{ nodeTypeMap[h.type] || h.type }}</el-tag>
            <span class="impact-ref">{{ h.refNo }}</span>
            <span>{{ h.title }}</span>
          </div>
        </div>
        <span v-else class="impact-none">无</span>

        <el-divider content-position="left">评估意见</el-divider>
        <el-card shadow="never" class="impact-advice-card">
          <template #header>
            <div class="card-header">
              <span>评估意见</span>
              <el-tag size="small" effect="plain" :type="impactResult.llmUsed ? 'success' : 'info'">
                {{ impactResult.llmUsed ? 'AI 生成' : '规则降级生成' }}
              </el-tag>
            </div>
          </template>
          <div class="impact-advice">{{ impactResult.advice }}</div>
        </el-card>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElLoading, ElMessageBox } from 'element-plus'
import { listLinks, linkChain, linkChainRels, createLink, analyzeImpact, autoTicket, traceLink, createRel, deleteRel } from '../../api/link'
import { useConceptStore } from '../../store/concept'
import { useUserStore } from '../../store/user'

const router = useRouter()
const route = useRoute()
const conceptStore = useConceptStore()
const userStore = useUserStore()

// ---------- 类型字典 ----------
const nodeTypeMap = {
  TICKET: '客服工单',
  REQUIREMENT: '需求',
  CHANGE: '研发变更',
  TESTCASE: '测试用例',
  DEPLOY: '运维发布',
  ALERT: '危重预警',
  DISPOSAL: '处置单'
}
// 概念链路流水线顺序：需求 → 研发变更 → 测试用例 → 运维发布 → 危重预警 → 客服工单 → 处置单
const pipelineLanes = [
  { key: 'REQUIREMENT', label: '需求' },
  { key: 'CHANGE', label: '研发变更' },
  { key: 'TESTCASE', label: '测试用例' },
  { key: 'DEPLOY', label: '运维发布' },
  { key: 'ALERT', label: '危重预警' },
  { key: 'TICKET', label: '客服工单' },
  { key: 'DISPOSAL', label: '处置单' }
]
const nodeTypeTag = (t) =>
  ({ TICKET: 'danger', REQUIREMENT: 'primary', CHANGE: 'warning', TESTCASE: 'success', DEPLOY: 'info', ALERT: 'danger', DISPOSAL: 'success' }[t] ||
  'info')

// ---------- 追溯边类型 ----------
// 方向约定：沿生命周期流向（先 → 后），from = 源头/原因侧，to = 结果/闭环侧
const relTypeMap = {
  CAUSES: '引发（变更 → 工单/预警）',
  TRIGGERS: '催生（工单/预警 → 需求）',
  LEADS_TO: '落地为（需求 → 变更）',
  COVERED_BY: '被用例覆盖（需求/变更 → 用例）',
  SHIPPED_BY: '经发布上线（变更 → 发布）',
  CLOSED_BY: '被处置闭环（工单/预警 → 处置单）',
  RELATES: '通用关联'
}
const relTypeShort = {
  CAUSES: '引发',
  TRIGGERS: '催生',
  LEADS_TO: '落地为',
  COVERED_BY: '被覆盖',
  SHIPPED_BY: '发布于',
  CLOSED_BY: '闭环于',
  RELATES: '关联'
}

// ---------- 节点列表 ----------
const nodes = ref([])
const nodePage = ref(1)
const nodeTotal = ref(0)
const pageSize = 20
const loading = ref(false)
const filterType = ref('')
const filterConcept = ref('')

const loadNodes = async (page = nodePage.value) => {
  loading.value = true
  try {
    const res = await listLinks(
      filterType.value || undefined,
      filterConcept.value || undefined,
      page,
      pageSize
    )
    nodes.value = res.list
    nodeTotal.value = res.total
    nodePage.value = res.pageNum
  } finally {
    loading.value = false
  }
}

// 筛选变化重置到第 1 页（手动筛选时取消泳道联动高亮）
const onFilterChange = () => {
  activeLane.value = ''
  loadNodes(1)
}

// ---------- 节点详情 + 链路追溯 ----------
const drawerVisible = ref(false)
const currentNode = ref(null)
const traceNodes = ref([])
const traceEdges = ref([])
const traceLoading = ref(false)

const openDetail = (row) => {
  currentNode.value = row
  drawerVisible.value = true
  loadTrace(row.refNo)
}

const loadTrace = async (refNo) => {
  traceLoading.value = true
  try {
    const res = await traceLink(refNo)
    traceNodes.value = res.nodes || []
    traceEdges.value = res.edges || []
  } finally {
    traceLoading.value = false
  }
}

// 从当前节点出发沿边走BFS：up=沿入边向根因方向，down=沿出边向影响/闭环方向
const walkChain = (dir) => {
  const root = currentNode.value?.refNo
  if (!root) return []
  const nodeByRef = Object.fromEntries(traceNodes.value.map((n) => [n.refNo, n]))
  const result = []
  const visited = new Set([root])
  let frontier = [root]
  while (frontier.length) {
    const next = []
    for (const cur of frontier) {
      for (const e of traceEdges.value) {
        if (dir === 'up' && e.toRefNo !== cur) continue
        if (dir === 'down' && e.fromRefNo !== cur) continue
        const otherRef = dir === 'up' ? e.fromRefNo : e.toRefNo
        if (visited.has(otherRef)) continue
        visited.add(otherRef)
        result.push({ rel: e, node: nodeByRef[otherRef] || { refNo: otherRef } })
        next.push(otherRef)
      }
    }
    frontier = next
  }
  return result
}
const upstreamChain = computed(() => walkChain('up'))
const downstreamChain = computed(() => walkChain('down'))

// 当前节点直接相连的边（用于删除入口）
const directRels = computed(() => {
  const root = currentNode.value?.refNo
  return traceEdges.value.filter((e) => e.fromRefNo === root || e.toRefNo === root)
})

// 可选关联对象：排除自身
const relCandidates = computed(() =>
  allNodes.value.filter((n) => n.refNo !== currentNode.value?.refNo)
)

const relForm = reactive({ direction: 'down', relType: 'CAUSES', targetRefNo: '', remark: '' })
const relSaving = ref(false)

const submitRel = async () => {
  if (!relForm.targetRefNo) {
    ElMessage.warning('请选择要关联的节点')
    return
  }
  const self = currentNode.value.refNo
  const [fromRefNo, toRefNo] =
    relForm.direction === 'up' ? [self, relForm.targetRefNo] : [relForm.targetRefNo, self]
  relSaving.value = true
  try {
    await createRel({ fromRefNo, toRefNo, relType: relForm.relType, remark: relForm.remark })
    ElMessage.success('关联已保存')
    relForm.targetRefNo = ''
    relForm.remark = ''
    loadTrace(self)
  } finally {
    relSaving.value = false
  }
}

const removeRel = async (rel) => {
  await deleteRel(rel.id)
  ElMessage.success('关联已删除')
  loadTrace(currentNode.value.refNo)
}

const formatPayload = (payload) => {
  try {
    return JSON.stringify(JSON.parse(payload), null, 2)
  } catch {
    return payload
  }
}

// ---------- 工单跳转AI客服智能诊断 ----------
const goCs = (row) => router.push({ path: '/cs', query: { ticketId: row.id } })

// ---------- 概念链路视图 ----------
const chainConcept = ref('FEE_DETAIL')
const chain = ref({})
const chainRels = ref([])
const chainLoading = ref(false)
// 当前联动过滤的泳道（'' = 不过滤）
const activeLane = ref('')

const loadChain = async () => {
  if (!chainConcept.value) return
  chainLoading.value = true
  try {
    const [chainRes, relsRes] = await Promise.all([
      linkChain(chainConcept.value),
      linkChainRels(chainConcept.value)
    ])
    chain.value = chainRes
    chainRels.value = relsRes
    // 切换概念后保持泳道联动：节点列表跟随新概念刷新
    if (activeLane.value) {
      filterConcept.value = chainConcept.value
      loadNodes(1)
    }
  } finally {
    chainLoading.value = false
  }
}

// 泳道/健康卡点击：节点列表联动过滤为该概念+该类型，再次点击取消
const onLaneClick = (key) => {
  if (activeLane.value === key) {
    activeLane.value = ''
    filterType.value = ''
    filterConcept.value = ''
  } else {
    activeLane.value = key
    filterType.value = key
    filterConcept.value = chainConcept.value
  }
  loadNodes(1)
}

const TICKET_DONE = ['已解决', '已完成']
const ALERT_DONE = ['已处置', '已处理', '已关闭']

// 健康度统计：未结工单（非 已解决/已完成）红色突出
const stats = computed(() => {
  const c = chain.value
  const tickets = c.TICKET || []
  return {
    openTickets: tickets.filter((t) => !TICKET_DONE.includes(t.status)).length,
    requirement: (c.REQUIREMENT || []).length,
    change: (c.CHANGE || []).length,
    testcase: (c.TESTCASE || []).length,
    deploy: (c.DEPLOY || []).length,
    alert: (c.ALERT || []).length,
    openAlerts: (c.ALERT || []).filter((a) => !ALERT_DONE.includes(a.status)).length
  }
})

// 顶部一句话业务结论：从链路数据推导当前概念处在什么状态、该不该管
// 优先沿追溯边判断（可跨概念定位根因），无边时退化为类型计数规则
const chainConclusion = computed(() => {
  if (!chainConcept.value) return null
  const c = chain.value
  const name =
    conceptStore.concepts.find((x) => x.code === chainConcept.value)?.name || chainConcept.value
  const { openTickets, openAlerts, change, testcase, deploy } = stats.value

  // 沿边分析未结工单：已定位根因（有入向 CAUSES 边）的工单与根因变更
  const openTicketRefs = new Set(
    (c.TICKET || []).filter((t) => !TICKET_DONE.includes(t.status)).map((t) => t.refNo)
  )
  const rootCauseRefs = [
    ...new Set(
      chainRels.value
        .filter((e) => e.relType === 'CAUSES' && openTicketRefs.has(e.toRefNo))
        .map((e) => e.fromRefNo)
    )
  ]
  const tracedTickets = new Set(
    chainRels.value
      .filter(
        (e) =>
          (openTicketRefs.has(e.toRefNo) && ['CAUSES', 'CLOSED_BY'].includes(e.relType)) ||
          (openTicketRefs.has(e.fromRefNo) && ['TRIGGERS', 'CLOSED_BY'].includes(e.relType))
      )
      .flatMap((e) => [e.fromRefNo, e.toRefNo])
      .filter((r) => openTicketRefs.has(r))
  )

  // 距最近一次变更/发布的月数（无任何变更/发布记录则为 null）
  const lastFixAt = [...(c.CHANGE || []), ...(c.DEPLOY || [])]
    .map((n) => n.occurredAt)
    .filter(Boolean)
    .sort()
    .pop()
  let staleClause = ''
  if (openTickets > 0 && rootCauseRefs.length === 0) {
    if (!lastFixAt) {
      staleClause = '且至今无关联变更'
    } else {
      const months = Math.floor((Date.now() - new Date(lastFixAt).getTime()) / (30 * 86400000))
      if (months >= 1) staleClause = `且 ${months} 个月无关联变更`
    }
  }
  const head = `「${name}」当前有 ${openTickets} 张未结工单${staleClause}`

  // 边驱动结论：未结工单已沿追溯边定位到根因变更
  if (openTickets > 0 && rootCauseRefs.length > 0) {
    const all = tracedTickets.size >= openTickets ? '已全部' : `中 ${tracedTickets.size} 张已`
    return {
      level: 'warning',
      text: `${head}——${all}沿追溯边定位到根因变更（${rootCauseRefs.join('、')}），建议按根因集中修复并批量处置工单`
    }
  }
  if (openTickets > 0 && change === 0 && deploy === 0) {
    return { level: 'error', text: `${head}——问题正在重现但尚未立项修复，建议立即处置` }
  }
  if (change > 0 && testcase === 0) {
    return { level: 'warning', text: `${head}——变更未经测试覆盖，存在质量缺口` }
  }
  if (testcase > 0 && deploy === 0) {
    return { level: 'info', text: `${head}——修复已验证待发布` }
  }
  if (openTickets === 0 && openAlerts === 0) {
    return { level: 'success', text: `${head}——运转正常，无需关注` }
  }
  if (openAlerts > 0) {
    return { level: 'error', text: `${head}——有 ${openAlerts} 条危重预警未处置，建议立即前往临床页处置` }
  }
  return { level: 'warning', text: `${head}——修复链路已建立，持续跟进至工单闭环` }
})

// 泳道"去处理"入口：仅该泳道有待办时出现
const laneAction = (key) => {
  if (key === 'TICKET' && stats.value.openTickets > 0) {
    return { go: () => router.push('/cs') }
  }
  if (key === 'ALERT' && stats.value.openAlerts > 0) {
    return { go: () => router.push('/clinical') }
  }
  return null
}

// ---------- 新增节点 ----------
const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({
  nodeType: 'TICKET',
  refNo: '',
  title: '',
  conceptCode: '',
  status: '',
  occurredAt: '',
  payload: '',
  linkTarget: '',
  linkRelType: 'RELATES',
  linkDirection: 'down'
})

// 全部节点（用于"关联节点"下拉候选，节点量大时按分页上限截取）
const allNodes = ref([])

const loadAllNodes = async () => {
  const res = await listLinks(null, null, 1, 500)
  allNodes.value = res.list
}

const openCreate = () => {
  Object.assign(createForm, {
    nodeType: 'TICKET',
    refNo: '',
    title: '',
    conceptCode: filterConcept.value || '',
    status: '',
    occurredAt: '',
    payload: '',
    linkTarget: '',
    linkRelType: 'RELATES',
    linkDirection: 'down'
  })
  createVisible.value = true
}

const submitCreate = async () => {
  if (!createForm.nodeType || !createForm.refNo || !createForm.title || !createForm.conceptCode) {
    ElMessage.warning('请填写节点类型、单号、标题和关联概念')
    return
  }
  creating.value = true
  try {
    await createLink({
      nodeType: createForm.nodeType,
      refNo: createForm.refNo,
      title: createForm.title,
      conceptCode: createForm.conceptCode,
      status: createForm.status,
      occurredAt: createForm.occurredAt,
      payload: createForm.payload
    })
    // 选择了关联节点：同步建边（对方→新节点 或 新节点→对方）
    if (createForm.linkTarget) {
      const [fromRefNo, toRefNo] =
        createForm.linkDirection === 'up'
          ? [createForm.refNo, createForm.linkTarget]
          : [createForm.linkTarget, createForm.refNo]
      try {
        await createRel({ fromRefNo, toRefNo, relType: createForm.linkRelType })
      } catch {
        ElMessage.warning('节点已保存，但关联创建失败，可在节点详情中补建')
      }
    }
    ElMessage.success('节点已保存')
    createVisible.value = false
    loadNodes()
    loadAllNodes()
    if (createForm.conceptCode === chainConcept.value) loadChain()
  } finally {
    creating.value = false
  }
}

// ---------- 变更影响评估 ----------
const impactVisible = ref(false)
const analyzing = ref(false)
const impactResult = ref(null)
const impactForm = reactive({ conceptCode: '', changeDesc: '', depth: 3 })

// 按跳分层展示的层序号（升序）
const impactLayerKeys = computed(() =>
  Object.keys(impactResult.value?.impactLayers || {})
    .map(Number)
    .sort((a, b) => a - b)
)

const openImpact = () => {
  impactForm.conceptCode = filterConcept.value || ''
  impactForm.changeDesc = ''
  impactForm.depth = 3
  impactResult.value = null
  impactVisible.value = true
}

const runImpact = async () => {
  if (!impactForm.conceptCode || !impactForm.changeDesc.trim()) {
    ElMessage.warning('请选择概念并填写变更描述')
    return
  }
  analyzing.value = true
  const loadingInstance = ElLoading.service({
    text: 'AI 正在评估影响面...',
    background: 'rgba(255, 255, 255, 0.7)'
  })
  try {
    impactResult.value = await analyzeImpact({
      conceptCode: impactForm.conceptCode,
      changeDesc: impactForm.changeDesc.trim(),
      depth: String(impactForm.depth)
    })
    ElMessage.success('影响评估完成')
  } finally {
    loadingInstance.close()
    analyzing.value = false
  }
}

// ---------- 异常检测自动转工单 ----------
const autoTicketing = ref(false)

const runAutoTicket = async () => {
  try {
    await ElMessageBox.confirm(
      '将扫描「取消未退费」患者并自动生成客服工单，已存在工单的患者自动跳过',
      '异常检测转工单',
      { type: 'warning', confirmButtonText: '开始检测', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  autoTicketing.value = true
  try {
    const res = await autoTicket()
    if (res.createdCount > 0) {
      ElMessage.success(`检测 ${res.scannedPatients} 名异常患者，新生成 ${res.createdCount} 张工单`)
    } else {
      ElMessage.success('无新增工单（均已覆盖）')
    }
    loadNodes()
  } finally {
    autoTicketing.value = false
  }
}

onMounted(() => {
  // 深链承接：/link?concept=X（证据链「链路追溯」锚点等）——按概念过滤进入
  const c0 = route.query.concept
  if (c0) filterConcept.value = String(c0)
  conceptStore.fetchAll()
  loadNodes()
  loadChain()
  loadAllNodes()
})
</script>

<style scoped>
.filter-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

.node-pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.chain-col {
  flex: 1;
  min-width: 0;
  min-height: 220px;
  cursor: pointer;
  border: 1px solid transparent;
  border-radius: 4px;
  padding: 4px 8px;
  transition: border-color 0.2s, background-color 0.2s;
}

.chain-col:hover {
  background: #f5f7fa;
}

.chain-col.active {
  border-color: #409eff;
  background: #ecf5ff;
}

.pipeline-row {
  display: flex;
  gap: 12px;
}

.lane-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.lane-action {
  font-size: 12px;
  flex-shrink: 0;
}

.conclusion-bar {
  margin-bottom: 12px;
  padding: 10px 16px;
  border-radius: 4px;
  font-size: 14px;
  border-left: 4px solid;
}

.conclusion-bar.level-error {
  color: #f56c6c;
  background: #fef0f0;
  border-color: #f56c6c;
}

.conclusion-bar.level-warning {
  color: #e6a23c;
  background: #fdf6ec;
  border-color: #e6a23c;
}

.conclusion-bar.level-info {
  color: #409eff;
  background: #ecf5ff;
  border-color: #409eff;
}

.conclusion-bar.level-success {
  color: #67c23a;
  background: #f0f9eb;
  border-color: #67c23a;
}

.chain-title {
  font-size: 14px;
  font-weight: 600;
}

.chain-timeline {
  margin-top: 16px;
  padding-left: 2px;
}

.chain-item-title {
  font-size: 13px;
  line-height: 1.5;
}

.chain-item-ref {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.health-row {
  margin-bottom: 16px;
}

.health-card {
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 10px 12px;
  text-align: center;
  background: #fafafa;
  cursor: pointer;
  transition: border-color 0.2s, background-color 0.2s;
}

.health-card:hover {
  background: #f5f7fa;
}

.health-card.active {
  border-color: #409eff;
  background: #ecf5ff;
}

.health-value {
  font-size: 20px;
  font-weight: 700;
  color: #303133;
}

.health-value.danger {
  color: #f56c6c;
}

.health-label {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.alert-title {
  color: #f56c6c;
}

.impact-tables {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.impact-table-card {
  min-width: 300px;
  flex: 1;
}

.impact-ds-title {
  font-size: 13px;
  font-weight: 600;
}

.impact-table-row {
  font-size: 12px;
  color: #606266;
  line-height: 1.9;
  word-break: break-all;
}

.impact-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.impact-list-row {
  font-size: 13px;
  line-height: 2;
  display: flex;
  align-items: center;
  gap: 8px;
}

.impact-ref {
  color: #909399;
  font-size: 12px;
}

.impact-none {
  color: #c0c4cc;
  font-size: 13px;
}

.depth-tip {
  margin-left: 12px;
  font-size: 12px;
  color: #909399;
}

.impact-layer-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 6px 0;
}

.impact-layer-depth {
  font-size: 12px;
  color: #909399;
  width: 60px;
  flex-shrink: 0;
}

.impact-layer-tag {
  margin-right: 4px;
}

.impact-advice-card {
  margin-bottom: 8px;
}

.impact-advice {
  white-space: pre-wrap;
  line-height: 1.8;
  font-size: 13px;
}

.trace-block {
  margin-bottom: 12px;
}

.trace-head {
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  margin: 10px 0 6px;
}

.trace-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  line-height: 2;
  cursor: pointer;
  border-radius: 4px;
  padding: 0 6px;
}

.trace-row:hover {
  background: #f5f7fa;
}

.trace-rel-row {
  cursor: default;
}

.trace-ref {
  color: #909399;
  font-size: 12px;
  flex-shrink: 0;
}

.trace-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trace-rel {
  color: #c0c4cc;
  font-size: 12px;
  flex-shrink: 0;
}

.trace-rel-text {
  font-size: 13px;
}

.trace-remark {
  color: #909399;
  font-size: 12px;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rel-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.rel-form-row {
  display: flex;
  gap: 8px;
}
</style>
