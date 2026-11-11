package com.bemodel.compute;

import com.bemodel.common.BizException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.function.Function;

/** Fixed lock order for N-07/N-08: node shared capacity first, then the target group. */
@Service @Profile("compute")
public class ComputeBusinessTransactions {
    private final ComputeDatabase.Store store;
    ComputeBusinessTransactions(ComputeDatabase.Store store){this.store=store;}
    public void requireBusinessTransaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.hasResource(store.jdbc().getDataSource()))
            throw new IllegalStateException("写算力账本必须使用 computeTransactionManager");
    }
    public <T> T writeLocked(long nodeId,long groupId,Function<JdbcTemplate,T> writes) {
        if(nodeId<=0||groupId<=0||writes==null)throw new BizException("节点、卡组与业务操作不能为空");
        return store.transactions().execute(status->{
            requireBusinessTransaction();var jdbc=store.jdbc();
            var nodes=jdbc.queryForList("SELECT id FROM compute_node WHERE id=? FOR UPDATE",nodeId);
            if(nodes.size()!=1)throw new BizException("节点不存在");
            var groups=jdbc.queryForList("SELECT id FROM gpu_group WHERE id=? AND node_id=? FOR UPDATE",groupId,nodeId);
            if(groups.size()!=1)throw new BizException("卡组不属于所锁节点");
            return writes.apply(jdbc);
        });
    }
}
