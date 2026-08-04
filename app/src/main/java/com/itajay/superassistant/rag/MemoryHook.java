package com.itajay.superassistant.rag;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Component
public class MemoryHook extends AgentHook {

    private static final Logger log = LoggerFactory.getLogger(MemoryHook.class);
    private static final Path INDEX_FILE = Path.of(".memory", "MEMORY.md");

    @Override
    public String getName() {
        return "memory_hook";
    }

    @Override
    public HookPosition[] getHookPositions() {
        return new HookPosition[]{HookPosition.BEFORE_AGENT};
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        try {
            if (!Files.exists(INDEX_FILE)) {
                return CompletableFuture.completedFuture(Map.of());
            }

            String memoryContent = Files.readString(INDEX_FILE).trim();
            if (memoryContent.isBlank()) {
                return CompletableFuture.completedFuture(Map.of());
            }

            // Remove trailing auto-gen note and limit to ~3000 chars to avoid bloat
            int cutoff = memoryContent.indexOf("---\n*This index is auto-generated");
            if (cutoff > 0) memoryContent = memoryContent.substring(0, cutoff).trim();
            if (memoryContent.length() > 3000) {
                memoryContent = memoryContent.substring(0, 3000) + "\n\n[...truncated, use listMemories tool for full list]";
            }

            String systemPrompt = String.format("""
                    ## User Memory Profile

                    The following is what we know about the user from past interactions.
                    Use this context to personalize responses. When you learn something new
                    or important about the user, call the remember tool.

                    %s
                    """, memoryContent);

            SystemMessage sysMsg = new SystemMessage(systemPrompt);

            // Prepend to existing messages
            @SuppressWarnings("unchecked")
            Optional<Object> msgs = state.value("messages");
            List<Message> enhanced = new ArrayList<>();
            enhanced.add(sysMsg);
            if (msgs.isPresent() && msgs.get() instanceof List<?> list) {
                for (Object m : list) {
                    if (m instanceof Message msg) enhanced.add(msg);
                }
            }

            log.debug("MemoryHook injected memory profile ({} chars)", memoryContent.length());
            return CompletableFuture.completedFuture(Map.of("messages", enhanced));

        } catch (IOException e) {
            log.warn("Failed to read memory index", e);
            return CompletableFuture.completedFuture(Map.of());
        }
    }
}