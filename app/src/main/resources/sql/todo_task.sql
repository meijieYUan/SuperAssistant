-- 子任务/任务表：todoWrite 拆解结果与主 Agent 调度共用
-- 如果环境中已存在旧结构的 todo_task，请先迁移或重建该表
CREATE TABLE IF NOT EXISTS todo_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    thread_id VARCHAR(100) COMMENT '会话/计划归属',
    objective TEXT COMMENT '任务总目标/复杂任务描述',
    step_key VARCHAR(50) COMMENT '计划内步骤标识，如 t1',
    title VARCHAR(255) NOT NULL COMMENT '子任务标题',
    description TEXT COMMENT '子任务说明/执行要求',
    acceptance_criteria TEXT COMMENT '验收标准',
    depends_on TEXT COMMENT '依赖的 step_key 列表 JSON',
    step_no INT COMMENT '步骤序号',
    parent_id BIGINT COMMENT '父任务ID，支持层级拆解',
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/COMPLETED/FAILED/CANCELLED',
    priority VARCHAR(20) COMMENT 'LOW/MEDIUM/HIGH/URGENT',
    due_date DATETIME COMMENT '截止时间',
    assigned_to VARCHAR(100) COMMENT '负责Agent，待分配为空',
    output_summary TEXT COMMENT '执行结果摘要',
    error_message TEXT COMMENT '失败原因',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_todo_thread (thread_id),
    INDEX idx_todo_thread_status (thread_id, status),
    INDEX idx_todo_thread_step (thread_id, step_no),
    INDEX idx_todo_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
