package com.itajay.superassistant.prompt;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.itajay.superassistant.plan.PlanModeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Dynamically assembles and injects system prompts before each agent invocation.
 * Handles: memory profile (from MEMORY.md), plan mode guidance (when enabled).
 */
@Component
public class PromptSubmitHook extends AgentHook {

    private static final Logger log = LoggerFactory.getLogger(PromptSubmitHook.class);
    private static final Path MEMORY_FILE = Path.of(".memory", "MEMORY.md");

    @Override
    public String getName() {
        return "prompt_submit_hook";
    }

    @Override
    public HookPosition[] getHookPositions() {
        return new HookPosition[]{HookPosition.BEFORE_AGENT};
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        String threadId = config.threadId().orElse("unknown");
        List<SystemMessage> prompts = new ArrayList<>();

        // 1. Memory profile — inject when MEMORY.md exists and is non-empty
        String memoryPrompt = buildMemoryPrompt();
        if (memoryPrompt != null) {
            prompts.add(new SystemMessage(memoryPrompt));
            log.debug("PromptSubmitHook: injected memory profile");
        }

        // 2. Plan mode guidance — inject when user has enabled plan mode
        if (PlanModeContext.isEnabled(threadId)) {
            prompts.add(new SystemMessage(buildPlanModePrompt()));
            log.debug("PromptSubmitHook: injected plan mode prompt [thread={}]", threadId);
        }

        if (prompts.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        // Prepend prompts before existing messages
        @SuppressWarnings("unchecked")
        Optional<Object> msgs = state.value("messages");
        List<Message> enhanced = new ArrayList<>(prompts);
        if (msgs.isPresent() && msgs.get() instanceof List<?> list) {
            for (Object m : list) {
                if (m instanceof Message msg) enhanced.add(msg);
            }
        }

        log.debug("PromptSubmitHook: prepended {} prompt(s), total {} messages", prompts.size(), enhanced.size());
        return CompletableFuture.completedFuture(Map.of("messages", enhanced));
    }

    // ---- private helpers ----

    private String buildMemoryPrompt() {
        try {
            if (!Files.exists(MEMORY_FILE)) return null;

            String content = Files.readString(MEMORY_FILE).trim();
            if (content.isBlank()) return null;

            // Remove trailing auto-gen note
            int cutoff = content.indexOf("---\n*This index is auto-generated");
            if (cutoff > 0) content = content.substring(0, cutoff).trim();

            // Limit size to avoid context bloat
            if (content.length() > 3000) {
                content = content.substring(0, 3000) + "\n\n[...truncated, use listMemories for full list]";
            }

            return """
                    ## User Memory Profile

                    The following is what we know about the user from past interactions.
                    Use this context to personalize responses. When you learn something new
                    or important about the user, call the remember tool.

                    %s
                    """.formatted(content);
        } catch (IOException e) {
            log.warn("Failed to read memory file", e);
            return null;
        }
    }

    private String buildPlanModePrompt() {
        return """
                ## Plan Mode Active — RESTRICTED OPERATION

                You are currently in Plan Mode. You have ONLY the following permissions:
                - Read and analyze code/files
                - Search the web for information
                - Write plan documents to plans/{threadId}.md
                - Ask the user questions for clarification

                STRICTLY FORBIDDEN:
                - Writing or modifying business code
                - Deleting any files
                - Executing terminal commands
                - Any other destructive or mutating operations

                """;
    }
}