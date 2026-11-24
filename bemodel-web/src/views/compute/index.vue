<template>
  <section class="compute-workspace" v-loading="busy">
    <header><div><h2>算力申请与恢复工作台</h2><p>查看申请、抢占和恢复进度；确认现场结果后再改变资源账本。</p></div><el-button @click="refresh">刷新</el-button></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="runtime">
      <div class="toolbar"><el-tag>{{ runtime.simulation ? '模拟环境' : '真实环境' }}</el-tag><span>节点 {{ runtime.nodeKey }}</span><span>{{ runtime.workflowEnabled ? '流程自动推进已开启' : '流程按步骤手动推进' }}</span><el-tag type="info">{{ user.roleText }}</el-tag>
        <template v-if="admin && runtime.simulatorEnabled"><el-button @click="initialize">初始化模拟节点</el-button><el-button @click="collect">采集模拟观测</el-button></template>
      </div>
      <el-alert :title="runtime.recoveryWorkflowEnabled ? '支持 FLEX 单卡与 B 组两卡训练抢占恢复' : '当前服务支持 FLEX 单卡'" description="预留不是实际持有。恢复责任不随训练结束、失败或取消而消失；有待仲裁队首时保留进度，等待仲裁。" :closable="false" type="info" />
      <el-card v-if="canWrite && runtime.recoveryWorkflowEnabled"><template #header>部署恢复依据 · 抢占前显式登记</template>
        <el-table :data="deployments" @row-click="row => recovery.deployment = row"><el-table-column prop="name" label="部署" /><el-table-column prop="group_code" label="原卡组" /><el-table-column label="允许抢占"><template #default="{ row }">{{ row.preemptible ? '是' : '否' }}</template></el-table-column><el-table-column prop="actual_revision_id" label="实际持有修订" /><el-table-column prop="current_revision_id" label="当前配置修订" /><el-table-column prop="recovery_spec_id" label="最新需求登记" /></el-table>
        <template v-if="recovery.deployment?.actual_revision_id"><p>为 {{ recovery.deployment.name }} 的实际持有修订 #{{ recovery.deployment.actual_revision_id }} 登记未来执行依据。新登记不会改变已开始流程的恢复版本。</p>
          <el-form inline @submit.prevent="registerRecovery"><el-form-item label="每卡显存 GiB"><el-input-number v-model="recovery.vram" :min="1" :precision="0" /></el-form-item><el-form-item label="互联需求"><el-select v-model="recovery.interconnect" placeholder="显式选择" style="width:170px"><el-option label="无互联要求" value="NONE" /><el-option label="NVLink（能力未知会阻断）" value="NVLINK" /></el-select></el-form-item><el-form-item label="harness 团队编号"><el-input-number v-model="recovery.harness" :min="1" :precision="0" /></el-form-item><el-form-item label="依据说明"><el-input v-model="recovery.declaration" maxlength="512" placeholder="说明资源及团队责任依据" /></el-form-item><el-button type="primary" native-type="submit" :disabled="!recovery.vram || !recovery.harness || !recovery.interconnect || !recovery.declaration.trim() || !recovery.deployment.actual_revision_id">追加恢复需求登记</el-button></el-form>
        </template>
      </el-card>
      <el-tabs v-model="tab">
        <el-tab-pane v-if="canWrite" label="申请与队列" name="requests">
          <el-card><template #header>提交算力申请</template>
            <el-form inline @submit.prevent="submit">
              <el-form-item label="员工编号"><el-input-number v-model="form.employeeId" :min="1" :precision="0" /></el-form-item>
              <el-form-item label="项目编号"><el-input-number v-model="form.projectId" :min="1" :precision="0" /></el-form-item>
              <el-form-item label="任务类型编号"><el-input-number v-model="form.taskTypeId" :min="1" :precision="0" /></el-form-item>
              <el-form-item label="卡数"><el-select v-model="form.gpus" style="width:160px"><el-option label="FLEX 单卡" :value="1" /><el-option v-if="runtime.recoveryWorkflowEnabled" label="B 组两卡" :value="2" /></el-select></el-form-item>
              <el-form-item label="内存 GiB"><el-input-number v-model="form.memory" :min="0" :max="1024" :precision="0" /></el-form-item>
              <el-form-item label="单卡显存 GiB"><el-input-number v-model="form.vram" :min="1" :max="80" :precision="0" /></el-form-item>
              <el-form-item label="镜像"><el-input v-model="form.image" placeholder="请输入镜像引用" /></el-form-item>
              <el-button type="primary" native-type="submit" :disabled="busy || !form.image.trim()">提交申请</el-button>
            </el-form><small>当前样例员工、项目与训练类型编号均为 1；规格以字节提交。</small>
          </el-card>
          <el-table :data="requests" empty-text="还没有申请" @row-click="selectRequest" highlight-current-row>
            <el-table-column prop="id" label="申请编号" /><el-table-column label="状态"><template #default="{ row }">{{ label(row.status) }}</template></el-table-column><el-table-column prop="requestedGpuCount" label="卡数" /><el-table-column prop="submittedAt" label="提交时间（UTC）" />
          </el-table>
          <el-pagination :current-page="requestPage" :page-size="20" :total="requestTotal" layout="prev, pager, next" @current-change="changePage" />
          <el-card v-if="selected"><template #header>申请 #{{ selected.request.id }} · {{ label(selected.request.status) }}</template>
            <el-button v-if="['SUBMITTED','APPROVED'].includes(selected.request.status)" type="primary" @click="reserve">入队并尝试预留</el-button>
            <el-button v-if="selected.request.status==='SUBMITTED' && !selected.jobs.length" @click="cancelRequest">取消申请</el-button>
            <el-alert v-if="waitReason" :title="waitReason" type="warning" :closable="false" />
            <h4>作业历史</h4><el-table :data="selected.jobs" empty-text="尚未产生作业"><el-table-column prop="id" label="作业编号" /><el-table-column label="状态"><template #default="{ row }">{{ label(row.status) }}</template></el-table-column><el-table-column prop="endedAt" label="结束时间（UTC）" /><el-table-column label="操作"><template #default="{ row }"><el-button v-if="row.status==='FAILED'" link @click="retryJob(row)">重试失败作业</el-button></template></el-table-column></el-table>
          </el-card>
          <h3>节点队列</h3><el-table :data="queue" empty-text="没有等待的申请"><el-table-column prop="requestId" label="申请" /><el-table-column prop="targetGroupCode" label="目标组" /><el-table-column prop="position" label="组内顺序" /><el-table-column prop="reasonCode" label="等待原因" /><el-table-column prop="submittedAt" label="原提交时间" /></el-table>
        </el-tab-pane>
        <el-tab-pane label="流程与回收" name="workflows">
          <el-table :data="workflows" empty-text="还没有执行流程" @row-click="selectWorkflow"><el-table-column prop="id" label="流程" /><el-table-column prop="request_id" label="申请" /><el-table-column prop="job_id" label="作业" /><el-table-column label="当前步骤"><template #default="{ row }">{{ label(row.step) }}</template></el-table-column><el-table-column label="状态"><template #default="{ row }">{{ label(row.status) }}</template></el-table-column><el-table-column prop="last_error" label="等待或异常" /></el-table>
          <el-pagination :current-page="flowPage" :total="flowTotal" :page-size="20" layout="prev, pager, next" @current-change="changeFlowPage" />
          <el-card v-if="flow"><template #header>流程 #{{ flow.id }} · {{ label(flow.step) }}</template>
            <el-descriptions v-if="flow.original_deployment_id" :column="2" border><el-descriptions-item label="原部署 / 原卡组">部署 #{{ flow.original_deployment_id }} / {{ flow.group_code }}</el-descriptions-item><el-descriptions-item label="固定恢复依据">修订 #{{ flow.restore_revision_id }} · 需求登记 #{{ flow.restore_spec_id }}</el-descriptions-item><el-descriptions-item label="恢复责任">{{ label(flow.recovery_state) }}</el-descriptions-item><el-descriptions-item label="现场原则">恢复成功后先健康检查，再接回流量；异常时保留进度与保护。</el-descriptions-item></el-descriptions>
            <el-descriptions :column="2" border><el-descriptions-item label="目标">{{ label(flow.desired_state) }}</el-descriptions-item><el-descriptions-item label="完成原因">{{ label(flow.end_reason) || '尚未完成' }}</el-descriptions-item><el-descriptions-item label="下次检查（UTC）">{{ flow.next_run_at || '无' }}</el-descriptions-item><el-descriptions-item label="最近异常">{{ flow.last_error || '无' }}</el-descriptions-item></el-descriptions>
            <div v-if="canWrite && !['COMPLETED','CANCELLED'].includes(flow.status)" class="toolbar"><el-button type="primary" @click="flowAction('advance')">推进或核对一步</el-button><el-button @click="flowAction('stop')">停止并回收</el-button><el-button type="danger" plain @click="flowAction('cancel')">取消并回收</el-button></div>
            <el-table :data="flow.actions" @row-click="selectAction" empty-text="尚未发送执行动作"><el-table-column prop="id" label="动作" /><el-table-column label="类型"><template #default="{ row }">{{ row.action_type === 'NOTIFY' && row.workflow_step === 'RESTORED_NOTICE' ? '恢复后通知' : label(row.action_type) }}</template></el-table-column><el-table-column label="回执状态"><template #default="{ row }">{{ label(row.status) }}</template></el-table-column><el-table-column prop="attempt_count" label="发送次数" /><el-table-column prop="error_code" label="原因码" /></el-table>
            <small v-if="!canWrite">当前角色可查看流程进度；执行配置和回执明细仅向管理员、建模员开放。</small>
          </el-card>
          <el-card v-if="action && canWrite"><template #header>动作 #{{ action.id }} · {{ label(action.status) }}</template>
            <el-descriptions :column="2" border><el-descriptions-item label="原操作编号">{{ action.operationKey }}</el-descriptions-item><el-descriptions-item label="原因">{{ action.errorCode || '无' }}</el-descriptions-item><el-descriptions-item label="模拟回执">{{ action.receipt?.simulation ? '是' : '未标记' }}</el-descriptions-item><el-descriptions-item label="确认 GPU 释放">{{ action.receipt?.gpuReleased === true ? '已确认' : '尚未确认' }}</el-descriptions-item></el-descriptions>
            <div class="toolbar"><el-button v-if="['UNKNOWN','RUNNING'].includes(action.status)" @click="actionCall('reconcile')">按原编号核对</el-button><el-button v-if="admin && action.status==='FAILED' && action.retryAllowed" @click="actionCall('retry')">安全重试动作</el-button></div>
          </el-card>
        </el-tab-pane>
      </el-tabs>
    </template>
  </section>
</template>
<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../../api/request'
import { useUserStore } from '../../store/user'
const user=useUserStore(), admin=computed(()=>user.user?.role==='ADMIN'),canWrite=computed(()=>['ADMIN','EDITOR'].includes(user.user?.role))
const runtime=ref(null),busy=ref(false),error=ref(''),tab=ref(canWrite.value?'requests':'workflows'),requests=ref([]),queue=ref([]),workflows=ref([]),selected=ref(null),flow=ref(null),action=ref(null),waitReason=ref(''),requestPage=ref(1),requestTotal=ref(0),flowPage=ref(1),flowTotal=ref(0)
const deployments=ref([]),recovery=ref({deployment:null,vram:null,interconnect:'',harness:null,declaration:''})
const form=ref({employeeId:1,projectId:1,taskTypeId:1,gpus:1,memory:1,vram:1,image:'demo/train'});let clientKey=crypto.randomUUID()
const names={SUBMITTED:'已提交',APPROVED:'已批准',PENDING:'待执行',RUNNING:'运行中',UNKNOWN:'结果未知，等待核对',FAILED:'失败',SUCCEEDED:'已确认成功',STOPPED:'已停止',COMPLETED:'已完成',CANCELLED:'已取消',HANDOVER:'原子交接',START_JOB:'启动作业',STOP_JOB:'停止作业',RELEASE:'确认后回收',DONE:'已结束',READY:'待推进',START_FAILED:'启动失败后已回收'}
Object.assign(names,{NOTIFY:'抢占前通知',DRAIN:'摘流',WAIT_INFLIGHT:'等待在途请求结束',STOP:'确认原部署停服',ABORT_STOPPED:'撤销训练并恢复原部署',RESTORE_CHECK:'重评恢复与队列',RESTORE:'按固定修订恢复',HEALTH_CHECK:'恢复后健康检查',RESUME_TRAFFIC:'接回流量',RESTORED_NOTICE:'恢复后通知',ABORT_HEALTH:'撤销后的健康检查',ABORT_RESUME:'撤销后接回流量',OWED:'待恢复，责任保留',WAITING:'等待资源或队列仲裁',RESTORING:'恢复资源已保留',CHECKING:'恢复后检查中',RESTORED:'恢复与接流量已确认',NOT_REQUIRED:'未改变原部署，无需恢复'})
const label=v=>v?names[v]||v:''
async function guarded(fn){if(busy.value)return;busy.value=true;error.value='';try{await fn()}catch(e){if(e!=='cancel'&&e!=='close')error.value=e.message||'操作失败'}finally{busy.value=false}}
async function load(){if(!runtime.value)return;const f=await request.get('/compute/workflows',{params:{pageNum:flowPage.value}});workflows.value=f.list;flowTotal.value=f.total;if(canWrite.value){const [r,q]=await Promise.all([request.get('/compute/requests',{params:{pageNum:requestPage.value}}),request.get('/compute/queue',{params:{nodeKey:runtime.value.nodeKey}})]);requests.value=r.list;requestTotal.value=r.total;queue.value=q.list;if(runtime.value.recoveryWorkflowEnabled){deployments.value=await request.get('/compute/deployments');if(recovery.value.deployment)recovery.value.deployment=deployments.value.find(d=>d.id===recovery.value.deployment.id)||null}}if(selected.value)selected.value=await request.get(`/compute/requests/${selected.value.request.id}`);if(flow.value)flow.value=await request.get(`/compute/workflows/${flow.value.id}`)}
const refresh=()=>guarded(load)
const selectRequest=row=>guarded(async()=>{selected.value=await request.get(`/compute/requests/${row.id}`);waitReason.value=''})
const selectWorkflow=row=>guarded(async()=>{flow.value=await request.get(`/compute/workflows/${row.id}`);action.value=null;tab.value='workflows'})
const selectAction=row=>{if(canWrite.value)guarded(async()=>{action.value=(await request.get(`/compute/execution-actions/${row.id}`)).action})}
const changePage=p=>{requestPage.value=p;refresh()};const changeFlowPage=p=>{flowPage.value=p;refresh()}
const submit=()=>guarded(async()=>{const f=form.value;const row=await request.post('/compute/requests',{employeeId:f.employeeId,projectId:f.projectId,taskTypeId:f.taskTypeId,clientRequestKey:clientKey,requestedGpuCount:f.gpus,requestedResourceSpec:{memoryBytes:f.memory*1073741824,vramBytesPerGpu:f.vram*1073741824,gpuInterconnect:'NONE'},launchSpec:{imageRef:f.image.trim()}});clientKey=crypto.randomUUID();selected.value=await request.get(`/compute/requests/${row.id}`);await load();ElMessage.success(`申请 #${row.id} 已提交`)})
const reserve=()=>guarded(async()=>{const body={nodeKey:runtime.value.nodeKey,requestId:selected.value.request.id};const existing=await request.get('/compute/reservations',{params:{requestId:body.requestId}});let r={receipt:existing.list.find(row=>['PENDING','OCCUPIED'].includes(row.allocationStatus))};if(!r.receipt){await request.post('/compute/queue',body);r=await request.post('/compute/reservations',body)}if(r.receipt){flow.value=await request.post('/compute/workflows',{reservationReceiptId:r.receipt.id});tab.value='workflows'}else waitReason.value=`暂不能创建流程：${r.reasonCode || '等待资源或核对既有分配'}`;await load()})
const registerRecovery=()=>guarded(async()=>{const r=recovery.value;await request.post(`/compute/deployments/${r.deployment.id}/recovery-specs`,{revisionId:r.deployment.actual_revision_id,vramBytesPerGpu:r.vram*1073741824,gpuInterconnect:r.interconnect,harnessTeamId:r.harness,declaration:r.declaration.trim()});ElMessage.success('已追加恢复需求，旧记录与进行中流程保持原依据');await load()})
const cancelRequest=()=>guarded(async()=>{await ElMessageBox.confirm('确认取消尚未执行的申请？','取消申请');await request.post(`/compute/requests/${selected.value.request.id}/cancel`);await load()})
const retryJob=row=>guarded(async()=>{await request.post(`/compute/requests/${selected.value.request.id}/jobs/${row.id}/retry`);await load()})
const flowAction=op=>guarded(async()=>{if(op!=='advance')await ElMessageBox.confirm('将请求退出，取得停止确认后才回收资源。是否继续？','确认退出');flow.value=await request.post(`/compute/workflows/${flow.value.id}/${op}`);await load()})
const actionCall=op=>guarded(async()=>{action.value=await request.post(`/compute/execution-actions/${action.value.id}/${op}`);await load()})
const initialize=()=>guarded(async()=>{await request.post(`/compute/simulator/nodes/${runtime.value.nodeKey}/initialize`,{});ElMessage.success('模拟节点已准备，重复初始化保留原状态')})
const collect=()=>guarded(async()=>{await request.post(`/compute/simulator/nodes/${runtime.value.nodeKey}/observations`,{});ElMessage.success('已写入新观测；是否可用仍按生效规则检查');await load()})
onMounted(()=>guarded(async()=>{runtime.value=await request.get('/compute/runtime');await load()}))
</script>
<style scoped>
.compute-workspace{max-width:1400px;margin:auto;display:grid;gap:18px}header,.toolbar{display:flex;align-items:center;gap:16px;flex-wrap:wrap}header{justify-content:space-between}h2{margin:0}p,small{color:var(--el-text-color-secondary)}.el-card{margin:18px 0}.toolbar{margin:14px 0}.el-pagination{margin-top:16px}.el-alert{margin:12px 0}
</style>
