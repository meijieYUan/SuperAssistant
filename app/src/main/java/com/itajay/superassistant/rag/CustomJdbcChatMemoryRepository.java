package com.itajay.superassistant.rag;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class CustomJdbcChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(CustomJdbcChatMemoryRepository.class);

    private static final String TABLE_NAME = "CUSTOM_CHAT_MEMORY";

    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (" +
            "`conversation_id` VARCHAR(64) NOT NULL," +
            "`content` TEXT NOT NULL," +
            "`type` VARCHAR(20) NOT NULL," +
            "`timestamp` TIMESTAMP NOT NULL," +
            "`sequence_id` BIGINT  AUTO_INCREMENT," +
            "INDEX `CUSTOM_CM_CONV_SEQ_IDX` (`conversation_id`, `sequence_id`)" +
            ")";

    private static final String FIND_CONVERSATION_IDS_SQL =
            "SELECT DISTINCT conversation_id FROM " + TABLE_NAME;

    private static final String FIND_BY_CONVERSATION_ID_SQL =
            "SELECT content, type FROM " + TABLE_NAME +
            " WHERE conversation_id = ? ORDER BY sequence_id ASC";

    private static final String FIND_LATEST_BY_CONVERSATION_ID_SQL =
            "SELECT content, type FROM " + TABLE_NAME +
            " WHERE conversation_id = ? ORDER BY sequence_id DESC LIMIT ?";

    private static final String INSERT_MESSAGE_SQL =
            "INSERT INTO " + TABLE_NAME +
            " (conversation_id, content, type, timestamp) VALUES (?, ?, ?, ?)";

    private static final String DELETE_BY_CONVERSATION_ID_SQL =
            "DELETE FROM " + TABLE_NAME + " WHERE conversation_id = ?";


    private final DataSource dataSource;

    public CustomJdbcChatMemoryRepository(DataSource dataSource) {
        this.dataSource=dataSource;
    }


    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private DataSource dataSource;

        public Builder dataSource(DataSource dataSource) {
            this.dataSource = dataSource;
            return this;
        }



        public CustomJdbcChatMemoryRepository build() {
            return new CustomJdbcChatMemoryRepository(dataSource);
        }
    }

    private void initTable() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE_SQL);
            log.info("Custom chat memory table `{}` ready", TABLE_NAME);
        } catch (SQLException e) {
            log.error("Failed to initialize custom chat memory table", e);
            throw new RuntimeException("Failed to initialize custom chat memory table", e);
        }
    }

    @Override
    public List<String> findConversationIds() {
        List<String> ids = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(FIND_CONVERSATION_IDS_SQL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getString("conversation_id"));
            }
        } catch (SQLException e) {
            log.error("Error finding conversation ids", e);
        }
        return ids;
    }

    @NotNull
    @Override
    public List<Message> findByConversationId(@NotNull String conversationId) {
        return findByConversationIdInternal(conversationId, FIND_BY_CONVERSATION_ID_SQL, null);
    }

    @Override
    public void saveAll( String conversationId,  List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_MESSAGE_SQL)) {
            for (Message message : messages) {
                ps.setString(1, conversationId);
                ps.setString(2, message.getText());
                ps.setString(3, message.getMessageType().name());
                ps.setTimestamp(4, Timestamp.from(Instant.now()));
                ps.addBatch();
            }
            ps.executeBatch();
            log.debug("Saved {} messages to conversation `{}`", messages.size(), conversationId);
        } catch (SQLException e) {
            log.error("Error saving messages for conversation `{}`", conversationId, e);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(DELETE_BY_CONVERSATION_ID_SQL)) {
            ps.setString(1, conversationId);
            int deleted = ps.executeUpdate();
            log.debug("Deleted {} messages from conversation `{}`", deleted, conversationId);
        } catch (SQLException e) {
            log.error("Error deleting messages for conversation `{}`", conversationId, e);
        }
    }

    /**
     * 窗口化查询：获取指定会话最新的 limit 条消息，按时间正序返回（最旧到最新）。
     *
     * @param conversationId 会话 ID
     * @param limit          返回的消息数量上限
     * @return 最新 N 条消息（正序）
     */
    public List<Message> findLatestByConversationId(String conversationId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<Message> messages = findByConversationIdInternal(
                conversationId, FIND_LATEST_BY_CONVERSATION_ID_SQL, limit);
        java.util.Collections.reverse(messages);
        return messages;
    }

    /**
     * 追加插入一条新消息到指定会话。
     *
     * @param conversationId 会话 ID
     * @param message        要追加的消息
     */
    public void appendMessage(String conversationId, Message message) {
        if (message == null) {
            return;
        }
        saveAll(conversationId, List.of(message));
    }

    private List<Message> findByConversationIdInternal(
            String conversationId, String sql, Integer limit) {
        List<Message> messages = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            if (limit != null) {
                ps.setInt(2, limit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String content = rs.getString("content");
                    String type = rs.getString("type");
                    messages.add(buildMessage(content, type));
                }
            }
        } catch (SQLException e) {
            log.error("Error finding messages for conversation `{}`", conversationId, e);
        }
        return messages;
    }


    private Message buildMessage(String content, String type) {
        try {
            return switch (type) {
               case "USER" -> new UserMessage(content);
               case "ASSISTANT" -> new AssistantMessage(content);
               default -> new UserMessage(content);
           };
        } catch (Exception e) {
            log.debug("JSON deserialization failed for type {}, falling back to text-only", type, e);
        }
        return null;
    }



}
