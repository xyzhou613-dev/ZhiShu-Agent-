-- 合成样例，观测时间固定，便于重现。
INSERT INTO compute_node (id, node_key, hostname, memory_capacity_bytes, memory_reserved_bytes,
                          temporary_capacity_bytes, persistent_capacity_bytes)
VALUES (1, 'a800-demo', 'a800-demo', 137438953472, 8589934592, 549755813888, 1099511627776);

INSERT INTO gpu_group (id, node_id, group_code) VALUES
(1, 1, 'A'), (2, 1, 'B'), (3, 1, 'C'), (4, 1, 'FLEX');

INSERT INTO gpu_device (id, node_id, group_id, gpu_uuid, gpu_index, model, vram_bytes) VALUES
(1, 1, 1, 'GPU-DEMO-0', 0, 'A800', 85899345920),
(2, 1, 1, 'GPU-DEMO-1', 1, 'A800', 85899345920),
(3, 1, 1, 'GPU-DEMO-2', 2, 'A800', 85899345920),
(4, 1, 1, 'GPU-DEMO-3', 3, 'A800', 85899345920),
(5, 1, 2, 'GPU-DEMO-4', 4, 'A800', 85899345920),
(6, 1, 2, 'GPU-DEMO-5', 5, 'A800', 85899345920),
(7, 1, 3, 'GPU-DEMO-6', 6, 'A800', 85899345920),
(8, 1, 4, 'GPU-DEMO-7', 7, 'A800', 85899345920);

INSERT INTO team (id, team_code, name) VALUES (1, 'DEMO', '算力样例团队');
INSERT INTO employee (id, employee_code, team_id, name) VALUES (1, 'DEMO-001', 1, '样例员工');
INSERT INTO project (id, project_code, name, owner_employee_id) VALUES (1, 'DEMO', '样例训练项目', 1);
INSERT INTO project_team (project_id, team_id) VALUES (1, 1);
INSERT INTO task_type (id, type_code, name, priority, default_resource_spec)
VALUES (1, 'TRAINING', '训练', 2, JSON_OBJECT('memoryBytes', 34359738368));

INSERT INTO deployment (id, group_id, project_id, owner_employee_id, name, preemptible, status) VALUES
(1, 1, 1, 1, '模型 A', TRUE, 'RUNNING'), (2, 2, 1, 1, '模型 B', TRUE, 'RUNNING'),
(3, 3, 1, 1, '模型 C', FALSE, 'RUNNING');

INSERT INTO deployment_revision (id, deployment_id, version, image_ref, command_args, mounts, health_check, memory_bytes)
VALUES (1, 1, 1, 'demo/model-a', JSON_ARRAY(), JSON_ARRAY(), JSON_OBJECT(), 34359738368),
       (2, 2, 1, 'demo/model-b', JSON_ARRAY(), JSON_ARRAY(), JSON_OBJECT(), 17179869184),
       (3, 3, 1, 'demo/model-c', JSON_ARRAY(), JSON_ARRAY(), JSON_OBJECT(), 8589934592);
UPDATE deployment SET current_revision_id = id WHERE id IN (1, 2, 3);

INSERT INTO hold_period (id, group_id, deployment_id, deployment_revision_id, started_at)
VALUES (1, 1, 1, 1, '2026-11-06 09:00:00'), (2, 2, 2, 2, '2026-11-06 09:00:00'),
       (3, 3, 3, 3, '2026-11-06 09:00:00');
UPDATE gpu_group SET current_hold_id = id WHERE id IN (1, 2, 3);

INSERT INTO compute_request (id, employee_id, project_id, task_type_id, client_request_key,
  request_fingerprint, requested_gpu_count, requested_resource_spec, launch_spec, status, submitted_at)
VALUES (1, 1, 1, 1, 'a800-demo-training', SHA2('a800-demo-training', 256), 2,
        JSON_OBJECT('memoryBytes', 34359738368), JSON_OBJECT('imageRef','demo/train'), 'APPROVED', '2026-11-06 10:00:00');
INSERT INTO compute_job (id, request_id, task_type_id, effective_gpu_count, effective_resource_spec)
VALUES (1, 1, 1, 2, JSON_OBJECT('memoryBytes', 34359738368));
INSERT INTO active_job (request_id, job_id) VALUES (1, 1);
INSERT INTO gpu_allocation (id, job_id, group_id, memory_bytes, reserved_at)
VALUES (1, 1, 2, 34359738368, '2026-11-06 10:00:00');
UPDATE gpu_group SET pending_allocation_id = 1 WHERE id = 2;

INSERT INTO resource_observation (node_id, source_subject_kind, source_subject_key, subject_kind,
                                  subject_id, source, observed_at, valid_until, status, values_json)
SELECT 1, 'GPU', gpu_uuid, 'GPU', id, 'A800_DEMO_FIXTURE', '2026-11-06 10:00:00', '2026-11-06 10:00:30',
       'OK', JSON_OBJECT('healthy', TRUE, 'occupied', gpu_index < 7)
FROM gpu_device;
