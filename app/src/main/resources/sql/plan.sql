-- Multi-agent plan task / step tables (MySQL compatible)

CREATE TABLE IF NOT EXISTS plan_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    thread_id VARCHAR(100) NOT NULL COMMENT '会话ID',
    objective TEXT NOT NULL COMMENT '用户目标',
    plan_json TEXT NOT NULL COMMENT '完整计划 JSON',
    status VARCHAR(30) NOT NULL DEFAULT 'AWAITING_APPROVAL' COMMENT 'DRAFT/AWAITING_APPROVAL/APPROVED/REJECTED/EXECUTING/COMPLETED/FAILED/CANCELLED',
    result TEXT COMMENT '最终执行结果',
    error_message TEXT COMMENT '失败原因',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    approved_at DATETIME,
    INDEX idx_plan_thread (thread_id),
    INDEX idx_plan_status (status)
);

CREATE TABLE IF NOT EXISTS plan_step (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL COMMENT 'plan_task.id',
    step_no INT NOT NULL COMMENT '步骤序号',
    agent_name VARCHAR(50) NOT NULL COMMENT '负责子Agent名称',
    goal TEXT NOT NULL COMMENT '子任务目标',
    acceptance_criteria TEXT COMMENT '验收标准',
    depends_on TEXT COMMENT '依赖步骤ID列表 JSON',
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/COMPLETED/FAILED/WAITING_HITL/SKIPPED',
    output_summary TEXT COMMENT '执行结果摘要',
    retry_count INT NOT NULL DEFAULT 0,
    error_message TEXT,
    started_at DATETIME,
    completed_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_step_plan (plan_id),
    INDEX idx_step_status (plan_id, status, step_no)
);
