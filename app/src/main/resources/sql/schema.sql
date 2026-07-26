-- todo_task table for the SuperAssistant
-- This table stores todo task information used by the TodoTool

CREATE TABLE IF NOT EXISTS todo_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(500) NOT NULL COMMENT 'Task title',
    description VARCHAR(2000) COMMENT 'Task description/details',
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING' COMMENT 'Status: PENDING, IN_PROGRESS, COMPLETED, CANCELLED',
    priority VARCHAR(20) DEFAULT 'MEDIUM' COMMENT 'Priority: LOW, MEDIUM, HIGH, URGENT',
    due_date DATETIME COMMENT 'Due date for the task',
    assigned_to VARCHAR(100) COMMENT 'Person assigned to this task',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    tags VARCHAR(1000) COMMENT 'Comma-separated tags',

    INDEX idx_status (status),
    INDEX idx_priority (priority),
    INDEX idx_assigned_to (assigned_to),
    INDEX idx_due_date (due_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Todo task table for SuperAssistant';
