CREATE DATABASE IF NOT EXISTS superassistant
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS superassistant_rag
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE superassistant;

CREATE TABLE IF NOT EXISTS todo_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    priority VARCHAR(30) NOT NULL DEFAULT 'MEDIUM',
    due_date DATETIME,
    assigned_to VARCHAR(100),
    tags VARCHAR(255),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_todo_status (status),
    INDEX idx_todo_priority (priority),
    INDEX idx_todo_due (due_date)
);

CREATE TABLE IF NOT EXISTS custom_chat_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(100) NOT NULL,
    message_type VARCHAR(20) NOT NULL,
    message_content TEXT NOT NULL,
    metadata TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_conv_id (conversation_id),
    INDEX idx_conv_created (conversation_id, created_at)
);

CREATE TABLE IF NOT EXISTS plan_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    thread_id VARCHAR(100) NOT NULL,
    objective TEXT NOT NULL,
    plan_json TEXT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'AWAITING_APPROVAL',
    result TEXT,
    error_message TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    approved_at DATETIME,
    INDEX idx_plan_thread (thread_id),
    INDEX idx_plan_status (status)
);

CREATE TABLE IF NOT EXISTS plan_step (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    step_no INT NOT NULL,
    step_key VARCHAR(50),
    agent_name VARCHAR(50) NOT NULL,
    goal TEXT NOT NULL,
    acceptance_criteria TEXT,
    depends_on TEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    output_summary TEXT,
    retry_count INT NOT NULL DEFAULT 0,
    error_message TEXT,
    started_at DATETIME,
    completed_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_step_plan (plan_id),
    INDEX idx_step_status (plan_id, status, step_no)
);

CREATE TABLE IF NOT EXISTS agent_run_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    thread_id VARCHAR(100),
    plan_id BIGINT,
    step_id BIGINT,
    agent_name VARCHAR(50) NOT NULL,
    phase VARCHAR(30) NOT NULL,
    status VARCHAR(30),
    input_summary TEXT,
    output_summary TEXT,
    error_message TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_agent_run_thread (thread_id, created_at),
    INDEX idx_agent_run_plan (plan_id, step_id)
);

USE superassistant_rag;

CREATE TABLE IF NOT EXISTS custom_chat_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(100) NOT NULL,
    message_type VARCHAR(20) NOT NULL,
    message_content TEXT NOT NULL,
    metadata TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_conv_id (conversation_id),
    INDEX idx_conv_created (conversation_id, created_at)
);
