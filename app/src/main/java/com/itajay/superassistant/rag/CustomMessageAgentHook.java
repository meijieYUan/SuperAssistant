package com.itajay.superassistant.rag;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@HookPositions({HookPosition.BEFORE_AGENT,HookPosition.AFTER_AGENT})
@Component
public class CustomMessageAgentHook extends AgentHook {
    public final int MAX_HISTORY_SIZE = 10;
    public final CustomJdbcChatMemoryRepository customJdbcChatMemoryRepository;
    public CustomMessageAgentHook(@Qualifier("ragDataSource") DataSource ragDataSource) {
       this.customJdbcChatMemoryRepository=CustomJdbcChatMemoryRepository.builder()
               .dataSource(ragDataSource)
               .build();

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
        //加载历史消息
        List<Message> messages = customJdbcChatMemoryRepository.findLatestByConversationId(
                String.valueOf(conversationIdOpt.get()),MAX_HISTORY_SIZE);
        return CompletableFuture.completedFuture(Map.of("messages", messages));
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterAgent(OverAllState state, RunnableConfig config) {
        Optional<String> conversationIdOpt = config.threadId();
        if (conversationIdOpt.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        Optional<Object> input = state.value("input");
        Optional<Object> output = state.value("output");
        if (output.isEmpty()||input.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        //进行消息追加保存
        UserMessage userMessage = new UserMessage(String.valueOf(input.get()));
        AssistantMessage assistantMessage=new AssistantMessage(String.valueOf(output.get()));
        customJdbcChatMemoryRepository.saveAll(String.valueOf(conversationIdOpt.get()),List.of(userMessage,assistantMessage));
        return CompletableFuture.completedFuture(Map.of());
    }
}