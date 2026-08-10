-- 计划步骤与 Agent 分配解耦：现有数据库执行一次即可
ALTER TABLE plan_step MODIFY COLUMN agent_name VARCHAR(50) NULL COMMENT '执行时由SupervisorAgent分配的Agent名称，创建计划时为空';
