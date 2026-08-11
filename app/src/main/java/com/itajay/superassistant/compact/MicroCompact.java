package com.itajay.superassistant.compact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Layer 3: MicroCompact — remove stale early tool call/result pairs.
 *
 * Inspired by Claude Code's microcompact: keep the most recent N pairs and drop older ones.
 * Dropped pairs are replaced with a compact notice.
 */
public final class MicroCompact {

    private static final Logger log = LoggerFactory.getLogger(MicroCompact.class);

    private MicroCompact() {}

    /**
     * Apply micro-compaction: keep only the most recent N tool-call/result pairs.
     */
    public static List<Message> compact(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return messages;

        int keep = CompactConfig.MICROCOMPACT_KEEP_RECENT_TOOL_PAIRS;

        // Scan backwards to count tool result messages
        int toolResultCount = 0;
        int earliestKeptResultIdx = messages.size();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg instanceof ToolResponseMessage) {
                toolResultCount++;
                if (toolResultCount <= keep) {
                    earliestKeptResultIdx = i;
                }
            }
        }

        if (toolResultCount <= keep) {
            return messages;
        }

        // Find the cutoff: the AssistantMessage that pairs with the earliest kept result
        int cutoffIdx = findPairStart(messages, earliestKeptResultIdx);
        int removedPairs = toolResultCount - keep;

        List<Message> result = new ArrayList<>();
        if (cutoffIdx > 0) {
            result.add(new SystemMessage(
                    "[Context micro-compacted: " + removedPairs + " early tool pairs removed. "
                    + keep + " most recent preserved.]"
            ));
        }
        for (int i = cutoffIdx; i < messages.size(); i++) {
            result.add(messages.get(i));
        }

        log.info("MicroCompact: removed {} pairs, kept {} ({} → {} messages)",
                removedPairs, keep, messages.size(), result.size());
        return result;
    }

    /** Find the preceding AssistantMessage with tool calls that pairs with the given result. */
    private static int findPairStart(List<Message> messages, int resultIdx) {
        for (int i = resultIdx - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg instanceof AssistantMessage am
                    && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                return i;
            }
        }
        return resultIdx;
    }
}
