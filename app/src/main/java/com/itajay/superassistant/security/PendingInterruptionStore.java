package com.itajay.superassistant.security;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class PendingInterruptionStore {

    private final ConcurrentHashMap<String, PendingInterruption> store = new ConcurrentHashMap<>();

    public void put(String threadId,
                    RunnableConfig config,
                    InterruptionMetadata metadata,
                    String inputMessage,
                    Long planId,
                    Long planStepId) {
        store.put(threadId, new PendingInterruption(config, metadata, inputMessage, planId, planStepId));
    }

    public PendingInterruption get(String threadId) {
        return store.get(threadId);
    }

    public void remove(String threadId) {
        store.remove(threadId);
    }

    public record PendingInterruption(
            RunnableConfig config,
            InterruptionMetadata metadata,
            String inputMessage,
            Long planId,
            Long planStepId
    ) {}
}
