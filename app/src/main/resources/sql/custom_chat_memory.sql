-- Custom chat memory table (H2 / MySQL compatible)

CREATE TABLE IF NOT EXISTS custom_chat_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(100) NOT NULL COMMENT '会话ID',
    message_type VARCHAR(20) NOT NULL COMMENT '消息类型: USER, ASSISTANT, SYSTEM, TOOL',
    message_content TEXT NOT NULL COMMENT '消息内容（JSON 序列化的 Spring AI Message）',
    metadata TEXT COMMENT '附加元数据（JSON）',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_conv_id (conversation_id),
    INDEX idx_conv_created (conversation_id, created_at)
);