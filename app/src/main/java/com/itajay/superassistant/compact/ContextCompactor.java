package com.itajay.superassistant.compact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ContextCompactor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactor.class);

    private static final String COMPACT_PROMPT = """
            你是一个上下文压缩器。请对以下对话历史进行完整而精炼的总结。

            ## 必须包含的内容（逐一覆盖，不得遗漏）：

            1. **用户的主要请求和意图**：列出用户提出的每一个请求，包括原始措辞和深层意图。

            2. **关键技术概念**：对话中涉及的所有技术术语、架构决策、框架选择及其原因。

            3. **涉及的文件和代码片段**：列出所有被读取、写入、修改的文件（含完整路径）。
               保留关键的代码片段和行号引用。

            4. **遇到的错误和修复方案**：记录每个错误（含完整错误信息）、排查过程和最终解决方案。

            5. **问题解决过程**：按时间线描述每个问题是如何被发现、分析和解决的。

            6. **用户的所有消息**：逐条列出用户的每条输入，不可合并、不可省略。

            7. **待完成的任务**：列出所有尚未完成的工作项。

            8. **当前工作状态**：现在进行到哪一步了？

            9. **建议的下一步**：基于当前状态，合理建议接下来应该做什么。

            ## 格式要求：
            - 使用与对话相同的语言
            - 只输出总结本身
            - 保持结构化

            对话内容：
            """;

    private static final int COMPACT_INPUT_MAX_CHARS = 30_000;
    private static final int SUMMARY_MAX_CHARS = 4_000;

    private ContextCompactor() {}

    /**
     * Async compaction: generate summary via LLM, save to SessionMemory.
     * Called from the async executor thread.
     */
    public static void compactAsync(List<Message> messages, ChatModel chatModel,
                                     String threadId, FileReadState fileState, boolean planActive) {
        int preCompactTokens = CompactConfig.estimateTokens(messages);
        int preCompactMsgCount = messages.size();

        // Generate summary via LLM
        String summary = generateSummary(messages, chatModel);
        if (summary == null || summary.isBlank()) {
            log.warn("Async compact: empty summary, deleting pending entry");
            SessionMemory.delete(threadId);
            return;
        }

        // Truncate summary if needed
        if (summary.length() > SUMMARY_MAX_CHARS) {
            summary = summary.substring(0, SUMMARY_MAX_CHARS) + "\n...[summary truncated]";
        }

        // Build recovery data
        List<String> recoveredPaths = fileState.getRecentFiles(CompactConfig.POST_COMPACT_MAX_FILES);
        String memoryProfile = loadMemoryProfile();
        String planContent = planActive ? loadPlanFile(threadId) : null;

        // Complete the session memory
        SessionMemory sm = SessionMemory.load(threadId);
        if (sm == null) {
            log.warn("Async compact: no pending SessionMemory found for thread={}", threadId);
            return;
        }
        sm.complete(summary, recoveredPaths, memoryProfile, planContent);
        sm.save(threadId);

        // Archive old messages
        archiveMessages(messages, threadId);

        log.info("Async compact done: thread={}, {} msgs / {} tokens -> summary {} chars, {} files",
                threadId, preCompactMsgCount, preCompactTokens, summary.length(), recoveredPaths.size());
    }

    // ── Summary generation ──

    private static String generateSummary(List<Message> messages, ChatModel chatModel) {
        String conversationText = messagesToTailText(messages, COMPACT_INPUT_MAX_CHARS);
        String promptText = COMPACT_PROMPT + conversationText;
        try {
            var response = chatModel.call(new Prompt(new UserMessage(promptText)));
            String text = response.getResult().getOutput().getText();
            return text != null ? text.trim() : null;
        } catch (Exception e) {
            log.error("LLM compaction call failed", e);
            return null;
        }
    }

    // ── Recovery helpers ──

    static String loadMemoryProfile() {
        try {
            Path memFile = Path.of(".memory", "MEMORY.md");
            if (!Files.exists(memFile)) return null;
            String content = Files.readString(memFile);
            if (content.length() > 3000) content = content.substring(0, 3000) + "\n...[truncated]";
            return "## Memory Profile (recovered after compaction)\n\n" + content;
        } catch (IOException e) {
            return null;
        }
    }

    static String loadPlanFile(String threadId) {
        try {
            Path planFile = Path.of("plans", sanitize(threadId) + ".md");
            if (!Files.exists(planFile)) return null;
            String content = Files.readString(planFile);
            if (content.length() > 8000) content = content.substring(0, 8000) + "\n...[plan truncated]";
            return "[Recovered plan: plans/" + threadId + ".md]\n\n" + content;
        } catch (IOException e) {
            return null;
        }
    }

    // ── Archive ──

    private static void archiveMessages(List<Message> messages, String threadId) {
        try {
            Path dir = CompactConfig.COMPACT_SNAPSHOTS_DIR.resolve(sanitize(threadId));
            Files.createDirectories(dir);
            Path file = dir.resolve("compact_" + Instant.now().toEpochMilli() + ".txt");
            Files.writeString(file, messagesToText(messages, Integer.MAX_VALUE));
            log.info("Archived {} messages to {}", messages.size(), file);
        } catch (IOException e) {
            log.warn("Failed to archive messages", e);
        }
    }

    // ── Text conversion ──

    /** Take the most recent N chars from the end of the conversation. */
    private static String messagesToTailText(List<Message> messages, int maxChars) {
        StringBuilder sb = new StringBuilder();
        int chars = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            String text = msg.getText();
            if (text == null) continue;
            String role = switch (msg.getMessageType()) {
                case USER -> "User";
                case ASSISTANT -> "Assistant";
                case SYSTEM -> "System";
                case TOOL -> "Tool";
            };
            String line = "[" + role + "] " + text + "\n";
            if (chars + line.length() > maxChars) break;
            sb.insert(0, line);
            chars += line.length();
        }
        return sb.toString();
    }

    private static String messagesToText(List<Message> messages, int maxChars) {
        StringBuilder sb = new StringBuilder();
        int chars = 0;
        for (Message msg : messages) {
            String text = msg.getText();
            if (text == null) continue;
            String role = switch (msg.getMessageType()) {
                case USER -> "User";
                case ASSISTANT -> "Assistant";
                case SYSTEM -> "System";
                case TOOL -> "Tool";
            };
            String line = "[" + role + "] " + text + "\n";
            if (chars + line.length() > maxChars) { sb.append("...[truncated]"); break; }
            sb.append(line);
            chars += line.length();
        }
        return sb.toString();
    }

    static String sanitize(String s) {
        if (s == null) return "unknown";
        return s.replaceAll("[^a-zA-Z0-9._\\\\-]", "_");
    }
}
