package com.itajay.superassistant.rag;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class CustomMessageAgentHook extends AgentHook {
    public final ChatMemory chatMemory;
    public final JdbcChatMemoryRepository jdbcChatMemoryRepository;
    public final int MAX_HISTORY_SIZE = 10;

    public CustomMessageAgentHook(@Qualifier("ragDataSource") DataSource ragDataSource) {
        this.jdbcChatMemoryRepository = JdbcChatMemoryRepository.builder()
                .dataSource(ragDataSource)
                .build();
        this.chatMemory = null;
    }

    @Override
    public String getName() {
        return "message_hook";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        Optional<String> conversationIdOpt = config.threadId();
        if (conversationIdOpt.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        List<Message> messages = jdbcChatMemoryRepository.findByConversationId(
                String.valueOf(conversationIdOpt.get()));
        if (messages.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        if (messages.size() < MAX_HISTORY_SIZE) {
            return CompletableFuture.completedFuture(Map.of("messages", messages));
        }
        List<Message> trimMessage = messages.subList(
                messages.size() - MAX_HISTORY_SIZE, messages.size());
        return CompletableFuture.completedFuture(Map.of("messages", trimMessage));
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterAgent(OverAllState state, RunnableConfig config) {
        Optional<String> conversationIdOpt = config.threadId();
        if (conversationIdOpt.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        Optional<Object> input = state.value("input");
        Optional<Object> output = state.value("output");
        if (input.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        UserMessage userMessage = new UserMessage(String.valueOf(input.get()));
        jdbcChatMemoryRepository.saveAll(
                String.valueOf(conversationIdOpt.get()), List.of(userMessage));
        return CompletableFuture.completedFuture(Map.of());
    }
}