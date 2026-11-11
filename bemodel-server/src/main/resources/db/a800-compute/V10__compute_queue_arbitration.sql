ALTER TABLE target_group_selection ADD UNIQUE(id,request_id,node_id);
ALTER TABLE compute_queue
 ADD COLUMN node_id BIGINT NULL,
 ADD COLUMN last_selection_id BIGINT NULL,
 ADD COLUMN last_evaluated_at DATETIME(6) NULL,
 ADD COLUMN next_evaluate_at DATETIME(6) NULL,
 ADD FOREIGN KEY(node_id) REFERENCES compute_node(id),
 ADD FOREIGN KEY(target_group_id,node_id) REFERENCES gpu_group(id,node_id),
 ADD FOREIGN KEY(last_selection_id,request_id,node_id) REFERENCES target_group_selection(id,request_id,node_id),
 ADD INDEX ix_queue_node_status(node_id,status,next_evaluate_at);
UPDATE compute_queue q JOIN gpu_group g ON g.id=q.target_group_id SET q.node_id=g.node_id WHERE q.node_id IS NULL;
