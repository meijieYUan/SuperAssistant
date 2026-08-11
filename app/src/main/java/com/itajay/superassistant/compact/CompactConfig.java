package com.itajay.superassistant.compact;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Central configuration for the context compression system.
 * All thresholds are tuned for DeepSeek's context window (~128K-256K tokens).
 */
public final class CompactConfig {

    private CompactConfig() {}

    // ── Token thresholds ──
    /** When total estimated tokens exceed this, compression starts. */
    public static final int CONTEXT_WARNING_TOKENS = 120_000;
    /** When tokens exceed this, more aggressive compression is applied. */
    public static final int CONTEXT_CRITICAL_TOKENS = 160_000;

    // ── Layer 1: Tool result truncation ──
    /** Max chars for a single tool result before truncation triggers. */
    public static final int TOOL_RESULT_MAX_CHARS = 40_000;
    /** Total tool result chars below which truncation is NOT applied (protects light sessions). */
    public static final int TOOL_RESULT_TOTAL_MIN_CHARS = 200_000;
    /** Characters to keep as preview when truncating. */
    public static final int TOOL_RESULT_PREVIEW_CHARS = 3_000;

    // ── Layer 2: Snip ──
    /** Minimum message count before snip activates. */
    public static final int SNIP_MIN_MESSAGE_COUNT = 60;
    /** Max length for a message to be considered a "short filler" (snip candidate). */
    public static final int SNIP_SHORT_MSG_MAX_CHARS = 80;
    /** Patterns that identify error messages worth snipping. */
    public static final String[] SNIP_ERROR_PATTERNS = {
            "Failed to ", "Error ", "Exception", "timeout", "Connection refused",
            "rate limit", "Too many requests"
    };

    // ── Layer 3: MicroCompact ──
    /** Number of most recent tool call/result pairs to preserve. */
    public static final int MICROCOMPACT_KEEP_RECENT_TOOL_PAIRS = 5;

    // ── Layer 4: Full LLM compaction ──
    public static final int POST_COMPACT_MAX_FILES = 5;
    public static final int POST_COMPACT_MAX_TOKENS_PER_FILE = 5_000;
    public static final int POST_COMPACT_FILE_BUDGET = 50_000;
    public static final int POST_COMPACT_MAX_INVOKED_SKILLS = 3;
    public static final int POST_COMPACT_MAX_TOKENS_PER_SKILL = 5_000;
    public static final int POST_COMPACT_SKILL_BUDGET = 25_000;

    // ── Session memory (async compaction) ──
    /** Max entries in the fileStateCache LRU. */
    public static final int FILE_STATE_CACHE_MAX_ENTRIES = 100;
    /** Cooldown: minimum agent calls between two full compactions. */
    public static final int COMPACT_COOLDOWN_CALLS = 5;
    /** Compaction summary token budget. */
    public static final int SUMMARY_TOKEN_BUDGET = 3_000;

    // ── Storage paths ──
    public static final Path COMPACT_DIR = Path.of(".compact");
    public static final Path TOOL_RESULTS_DIR = COMPACT_DIR.resolve("tool_results");
    public static final Path COMPACT_SNAPSHOTS_DIR = COMPACT_DIR.resolve("snapshots");
    /** Directory for per-thread compaction summaries. */
    public static final Path SESSION_MEMORY_DIR = COMPACT_DIR.resolve("session_memory");

    // ── Token estimation ──
    /** Rough estimate: UTF-8 bytes / 4 ≈ tokens (conservative for mixed CN/EN). */
    public static final int BYTES_PER_TOKEN = 4;

    /** Estimate token count from a string. */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return text.getBytes(StandardCharsets.UTF_8).length / BYTES_PER_TOKEN;
    }

    /** Estimate token count from an iterable of messages. */
    public static int estimateTokens(Iterable<?> messages) {
        int total = 0;
        for (Object m : messages) {
            if (m instanceof org.springframework.ai.chat.messages.Message msg) {
                String text = msg.getText();
                if (text != null) total += estimateTokens(text);
            }
        }
        return total;
    }

    /** Whether tool result content exceeds the truncation threshold. */
    public static boolean shouldTruncate(String content) {
        return content != null && content.length() > TOOL_RESULT_MAX_CHARS;
    }

    /** Whether total tool result chars are below the minimum — if so, skip truncation. */
    public static boolean belowTotalToolResultMin(long totalChars) {
        return totalChars < TOOL_RESULT_TOTAL_MIN_CHARS;
    }
}
