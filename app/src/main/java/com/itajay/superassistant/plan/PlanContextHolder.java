package com.itajay.superassistant.plan;

/**
 * Fallback context for tools invoked outside the normal ToolContext bridge.
 */
public final class PlanContextHolder {

    private static final ThreadLocal<String> THREAD_ID = new ThreadLocal<>();

    private PlanContextHolder() {}

    public static void setThreadId(String threadId) {
        THREAD_ID.set(threadId);
    }

    public static String getThreadId() {
        return THREAD_ID.get();
    }

    public static void clear() {
        THREAD_ID.remove();
    }
}
