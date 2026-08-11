package com.itajay.superassistant.compact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Layer 1: Tool result budget management.
 *
 * When a tool result exceeds {@link CompactConfig#TOOL_RESULT_MAX_CHARS},
 * the full content is saved to disk at {@code .compact/tool_results/{threadId}/{id}_{timestamp}.txt},
 * and the in-context message is replaced with a truncated preview plus a pointer to the file.
 *
 * The readFile tool is exempt — users can always retrieve the full content via a dedicated read.
 */
public final class ToolResultTruncator {

    private static final Logger log = LoggerFactory.getLogger(ToolResultTruncator.class);

    private ToolResultTruncator() {}

    /**
     * Scans messages and truncates oversized tool results.
     *
     * @param messages the full message list
     * @param threadId used for organizing disk storage
     * @return a new list with truncated tool results, or the same list if no truncation was needed
     */
    public static List<Message> truncate(List<Message> messages, String threadId) {
        if (messages == null || messages.isEmpty()) return messages;

        // Fast scan: is there any oversized result AND is total below the minimum?
        long totalToolResultChars = 0;
        boolean hasOversized = false;
        for (Message msg : messages) {
            if (msg instanceof ToolResponseMessage trm) {
                for (var resp : trm.getResponses()) {
                    String content = resp.responseData();
                    if (content != null) {
                        totalToolResultChars += content.length();
                        if (content.length() > CompactConfig.TOOL_RESULT_MAX_CHARS) {
                            hasOversized = true;
                        }
                    }
                }
            }
        }

        // Skip truncation if no oversized results or if total is below the minimum
        if (!hasOversized || CompactConfig.belowTotalToolResultMin(totalToolResultChars)) {
            return messages;
        }

        List<Message> result = new ArrayList<>(messages.size());
        boolean anyTruncated = false;

        for (Message msg : messages) {
            if (msg instanceof ToolResponseMessage trm) {
                List<ToolResponseMessage.ToolResponse> responses = trm.getResponses();
                List<ToolResponseMessage.ToolResponse> newResponses = new ArrayList<>(responses.size());
                boolean msgTruncated = false;

                for (ToolResponseMessage.ToolResponse resp : responses) {
                    String content = resp.responseData();
                    if (CompactConfig.shouldTruncate(content)) {
                        String savedPath = saveToDisk(threadId, resp.id(), content);
                        String truncated = buildTruncatedContent(content, savedPath);
                        newResponses.add(new ToolResponseMessage.ToolResponse(
                                resp.id(), resp.name(), truncated));
                        msgTruncated = true;
                        log.info("Truncated tool result [id={}, name={}]: {} → {} chars → saved to {}",
                                resp.id(), resp.name(), content.length(), truncated.length(), savedPath);
                    } else {
                        newResponses.add(resp);
                    }
                }

                if (msgTruncated) {
                    result.add(ToolResponseMessage.builder()
                            .responses(newResponses)
                            .metadata(trm.getMetadata())
                            .build());
                    anyTruncated = true;
                } else {
                    result.add(msg);
                }
            } else {
                result.add(msg);
            }
        }

        log.info("ToolResultTruncator: truncated oversized results (total tool chars: {})", totalToolResultChars);
        return result;
    }

    // ── private helpers ──

    private static String buildTruncatedContent(String fullContent, String savedPath) {
        int previewLen = Math.min(CompactConfig.TOOL_RESULT_PREVIEW_CHARS, fullContent.length());
        String preview = fullContent.substring(0, previewLen);

        return preview + "\n\n" +
               "╔══════════════════════════════════════════════════════════════╗\n" +
               "║  [Tool output truncated — full content saved to disk]       ║\n" +
               "║  Original: " + String.format("%,d", fullContent.length()) + " characters                                   ║\n" +
               "║  File: " + padRight(savedPath, 56) + "║\n" +
               "║  Use readFile to retrieve the complete output.              ║\n" +
               "╚══════════════════════════════════════════════════════════════╝";
    }

    private static String saveToDisk(String threadId, String toolCallId, String content) {
        try {
            Path dir = CompactConfig.TOOL_RESULTS_DIR.resolve(sanitize(threadId));
            Files.createDirectories(dir);

            String filename = sanitize(toolCallId) + "_" + Instant.now().toEpochMilli() + ".txt";
            Path file = dir.resolve(filename);
            Files.writeString(file, content);
            return file.toAbsolutePath().toString();
        } catch (IOException e) {
            log.error("Failed to save truncated tool result to disk", e);
            return CompactConfig.TOOL_RESULTS_DIR.resolve(sanitize(threadId)).toAbsolutePath().toString()
                    + " (save failed: " + e.getMessage() + ")";
        }
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    private static String padRight(String s, int len) {
        if (s.length() >= len) return s;
        return s + " ".repeat(len - s.length());
    }
}
