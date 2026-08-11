package com.itajay.superassistant.compact;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.itajay.superassistant.plan.PlanModeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@HookPositions(HookPosition.BEFORE_AGENT)
@Component
public class CompactHook extends AgentHook {

    private static final Logger log = LoggerFactory.getLogger(CompactHook.class);
    private final ChatModel chatModel;

    private final Map<String, FileReadState> fileStateCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, FileReadState> eldest) {
            return size() > CompactConfig.FILE_STATE_CACHE_MAX_ENTRIES;
        }
    };

    private final Map<String, Integer> callCounters = new ConcurrentHashMap<>();

    private final ExecutorService compactExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "compact-async");
        t.setDaemon(true);
        return t;
    });

    public CompactHook(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String getName() {
        return "context_compact_hook";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        String threadId = config.threadId().orElse("unknown");
        int callCount = callCounters.merge(threadId, 1, Integer::sum);

        @SuppressWarnings("unchecked")
        Optional<Object> msgs = state.value("messages");
        if (msgs.isEmpty() || !(msgs.get() instanceof List<?> list)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        @SuppressWarnings("unchecked")
        List<Message> messages = (List<Message>) list;
        if (messages.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        List<Message> working = new ArrayList<>(messages);

        // Step 0: inject completed SessionMemory if available
        working = applySessionMemory(working, threadId);

        int preTokens = CompactConfig.estimateTokens(working);
        if (preTokens < CompactConfig.CONTEXT_WARNING_TOKENS) {
            log.debug("CompactHook: ok ({} tokens, {} msgs)", preTokens, working.size());
            return CompletableFuture.completedFuture(Map.of("messages", working));
        }

        log.info("CompactHook: {} tokens / {} msgs - compressing", preTokens, working.size());
        int before = preTokens;

        working = ToolResultTruncator.truncate(working, threadId);
        working = ContextSnip.snip(working);
        working = MicroCompact.compact(working);
        int afterL3 = CompactConfig.estimateTokens(working);
        log.info("  L1-L3: {} -> {} tokens, {} msgs", before, afterL3, working.size());

        if (afterL3 >= CompactConfig.CONTEXT_CRITICAL_TOKENS) {
            SessionMemory existing = SessionMemory.load(threadId);
            boolean inCooldown = existing != null && existing.isPending()
                    && callCount < CompactConfig.COMPACT_COOLDOWN_CALLS;

            if (!inCooldown && (existing == null || existing.hasSummary())) {
                if (existing != null) SessionMemory.delete(threadId);
                triggerAsyncCompact(working, threadId);
            }
        }

        return CompletableFuture.completedFuture(Map.of("messages", working));
    }

    private List<Message> applySessionMemory(List<Message> messages, String threadId) {
        SessionMemory sm = SessionMemory.load(threadId);
        if (sm == null || !sm.hasSummary()) return messages;

        int cutoff = sm.getPreCompactMsgCount();
        if (cutoff <= 0 || cutoff >= messages.size()) return messages;

        log.info("CompactHook: injecting session memory - cut at msg {} of {}", cutoff, messages.size());

        List<Message> result = new ArrayList<>();
        result.addAll(sm.buildInjectionMessages());
        for (int i = cutoff; i < messages.size(); i++) {
            result.add(messages.get(i));
        }

        if (PlanModeContext.isActive(threadId)) {
            result.add(new SystemMessage(
                    "[Plan Mode Active - you are still in plan mode. Only read/search/plan allowed.]"));
        }

        SessionMemory.delete(threadId);
        callCounters.put(threadId, 0);
        return result;
    }

    private void triggerAsyncCompact(List<Message> messages, String threadId) {
        int msgCount = messages.size();
        int tokens = CompactConfig.estimateTokens(messages);

        SessionMemory pending = SessionMemory.createPending(msgCount, tokens);
        pending.save(threadId);

        FileReadState fileState;
        synchronized (fileStateCache) {
            fileState = fileStateCache.computeIfAbsent(threadId, k -> new FileReadState());
        }
        fileState.scanMessages(messages);

        boolean planActive = PlanModeContext.isActive(threadId);
        List<Message> copy = new ArrayList<>(messages);

        compactExecutor.submit(() -> {
            try {
                ContextCompactor.compactAsync(copy, chatModel, threadId, fileState, planActive);
            } catch (Exception e) {
                log.error("Async compact failed for thread={}", threadId, e);
                SessionMemory.delete(threadId);
            }
        });

        log.info("Async compact fired: thread={}, {} msgs / {} tokens", threadId, msgCount, tokens);
    }
}
