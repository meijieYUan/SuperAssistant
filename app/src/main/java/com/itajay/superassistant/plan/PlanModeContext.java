package com.itajay.superassistant.plan;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks two orthogonal states per thread:
 * - enabled: user has toggled plan mode ON (permission)
 * - active:  LLM has called enterPlanMode and is currently planning
 */
public final class PlanModeContext {

    /** User preference: is plan mode allowed for this thread? */
    private static final Map<String, Boolean> ENABLED = new ConcurrentHashMap<>();

    /** LLM state: has the LLM entered plan mode for this thread? */
    private static final Map<String, Boolean> ACTIVE = new ConcurrentHashMap<>();

    private PlanModeContext() {}

    // ---- enabled (user permission) ----

    public static void setEnabled(String threadId, boolean enabled) {
        if (threadId == null || threadId.isBlank()) return;
        if (enabled) {
            ENABLED.put(threadId, Boolean.TRUE);
        } else {
            ENABLED.remove(threadId);
            ACTIVE.remove(threadId);
        }
    }

    public static boolean isEnabled(String threadId) {
        return threadId != null && Boolean.TRUE.equals(ENABLED.get(threadId));
    }

    // ---- active (LLM planning state) ----

    public static void enter(String threadId) {
        if (threadId != null && !threadId.isBlank()) {
            ACTIVE.put(threadId, Boolean.TRUE);
        }
    }

    public static boolean isActive(String threadId) {
        return threadId != null && Boolean.TRUE.equals(ACTIVE.get(threadId));
    }

    public static void exit(String threadId) {
        if (threadId != null) {
            ACTIVE.remove(threadId);
        }
    }
}