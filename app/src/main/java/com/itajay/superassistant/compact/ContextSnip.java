package com.itajay.superassistant.compact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Layer 2: Snip — remove low-value historical messages.
 *
 * Strips messages that carry little signal for the current turn:
 * <ul>
 *   <li>Very short assistant "filler" messages (e.g. "Let me read that file.")</li>
 *   <li>Error messages that are clearly transient (e.g. rate-limit, timeout)</li>
 *   <li>Stale system messages from earlier phases (old plan-mode prompts, etc.)</li>
 *   <li>Repeated identical user messages</li>
 * </ul>
 *
 * The goal is to reduce context bloat without losing the conversation's semantic coherence.
 */
public final class ContextSnip {

    private static final Logger log = LoggerFactory.getLogger(ContextSnip.class);
    private ContextSnip() {}

    /**
     * Apply snip heuristics to the message list.
     *
     * @param messages the current message list
     * @return a new (possibly shorter) message list
     */
    public static List<Message> snip(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return messages;

        if (messages.size() <= CompactConfig.SNIP_MIN_MESSAGE_COUNT) {
            return messages;
        }

        if (!hasSnipCandidates(messages)) {
            return messages;
        }

        int originalSize = messages.size();
        List<Message> result = new ArrayList<>(messages);

        // Phase 1: remove short filler assistant messages
        result = removeShortFillers(result);

        // Phase 2: collapse consecutive error messages
        result = collapseErrors(result);

        // Phase 3: deduplicate repeated user messages
        result = deduplicateRepeatedUserMessages(result);

        int removed = originalSize - result.size();
        if (removed > 0) {
            log.info("ContextSnip: removed {} low-value messages ({} → {})", removed, originalSize, result.size());
        }
        return result;
    }

    // ── Phase 1: short filler messages ──

    private static List<Message> removeShortFillers(List<Message> messages) {
        List<Message> result = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            if (msg instanceof AssistantMessage am) {
                String text = am.getText();
                if (text != null && text.length() <= CompactConfig.SNIP_SHORT_MSG_MAX_CHARS
                        && isFiller(text)) {
                    continue; // skip
                }
            }
            result.add(msg);
        }
        return result;
    }

    private static boolean isFiller(String text) {
        String lower = text.toLowerCase().trim();
        return lower.startsWith("let me") ||
               lower.startsWith("i'll") ||
               lower.startsWith("i will") ||
               lower.startsWith("first") && lower.contains("read") ||
               lower.startsWith("ok") && lower.length() < 60 ||
               lower.equals("thinking...") ||
               lower.equals("let me check.") ||
               lower.equals("one moment.") ||
               lower.equals("sure.");
    }

    // ── Phase 2: collapse error messages ──

    private static List<Message> collapseErrors(List<Message> messages) {
        // If we find consecutive messages that are both errors, keep only the first.
        // This handles cases where the agent retries and gets the same error repeatedly.
        List<Message> result = new ArrayList<>(messages.size());
        boolean lastWasError = false;

        for (Message msg : messages) {
            boolean isError = isErrorMessage(msg);
            if (isError && lastWasError) {
                continue; // skip consecutive error
            }
            result.add(msg);
            lastWasError = isError;
        }
        return result;
    }

    private static boolean isErrorMessage(Message msg) {
        String text = msg.getText();
        if (text == null) return false;
        String lower = text.toLowerCase();
        return Arrays.stream(CompactConfig.SNIP_ERROR_PATTERNS)
                .anyMatch(p -> lower.contains(p.toLowerCase()));
    }

    // ── Phase 3: deduplicate repeated user messages ──

    private static List<Message> deduplicateRepeatedUserMessages(List<Message> messages) {
        List<Message> result = new ArrayList<>(messages.size());
        String lastUserText = null;

        for (Message msg : messages) {
            if (msg.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER) {
                String text = msg.getText();
                if (text != null && text.equals(lastUserText)) {
                    continue; // skip duplicate
                }
                lastUserText = text;
            } else {
                lastUserText = null;
            }
            result.add(msg);
        }
        return result;
    }

    /** Fast scan: check if any snip-worthy content exists without allocating. */
    private static boolean hasSnipCandidates(List<Message> messages) {
        String lastUserText = null;
        boolean prevError = false;
        for (Message msg : messages) {
            if (msg instanceof AssistantMessage am) {
                String text = am.getText();
                if (text != null && text.length() <= CompactConfig.SNIP_SHORT_MSG_MAX_CHARS
                        && isFiller(text)) {
                    return true;
                }
                if (isErrorMessage(msg)) {
                    if (prevError) return true;
                    prevError = true;
                } else {
                    prevError = false;
                }
            }
            if (msg.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER) {
                String text = msg.getText();
                if (text != null && text.equals(lastUserText)) return true;
                lastUserText = text;
            } else {
                lastUserText = null;
            }
        }
        return false;
    }
}
