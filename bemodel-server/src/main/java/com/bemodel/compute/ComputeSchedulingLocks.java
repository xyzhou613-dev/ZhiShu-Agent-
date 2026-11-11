package com.bemodel.compute;
import com.bemodel.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;

/** All scheduling writers lock node, groups by id, requests by id, jobs, then queue rows. */
final class ComputeSchedulingLocks {
    private ComputeSchedulingLocks() {}
    static long node(JdbcTemplate j,String key){
        var ids=j.queryForList("SELECT id FROM compute_node WHERE node_key=? FOR UPDATE",Long.class,key);
        if(ids.isEmpty())throw new BizException("节点不存在");
        groups(j,ids.getFirst());return ids.getFirst();
    }
    static void node(JdbcTemplate j,long id){
        if(j.queryForList("SELECT id FROM compute_node WHERE id=? FOR UPDATE",Long.class,id).isEmpty())throw new BizException("节点不存在");
        groups(j,id);
    }
    private static void groups(JdbcTemplate j,long id){j.queryForList("SELECT id FROM gpu_group WHERE node_id=? ORDER BY id FOR UPDATE",Long.class,id);}
}
