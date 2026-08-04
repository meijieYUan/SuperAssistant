package com.itajay.superassistant.plan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class PlanProgressNotifier {

    private static final Logger log = LoggerFactory.getLogger(PlanProgressNotifier.class);
    private static final long SSE_TIMEOUT = 30 * 60 * 1000L;

    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long planId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        subscribers.computeIfAbsent(planId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(planId, emitter));
        emitter.onTimeout(() -> remove(planId, emitter));
        emitter.onError(e -> remove(planId, emitter));
        return emitter;
    }

    public void broadcast(Long planId, Map<String, Object> payload) {
        CopyOnWriteArrayList<SseEmitter> emitters = subscribers.get(planId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("plan.progress").data(payload));
            } catch (IOException | IllegalStateException e) {
                remove(planId, emitter);
            }
        }
    }

    public void complete(Long planId) {
        CopyOnWriteArrayList<SseEmitter> emitters = subscribers.remove(planId);
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.debug("Failed to complete SSE emitter for plan {}", planId, e);
            }
        }
    }

    private void remove(Long planId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = subscribers.get(planId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                subscribers.remove(planId);
            }
        }
    }
}
