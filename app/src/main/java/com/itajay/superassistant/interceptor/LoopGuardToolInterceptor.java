package com.itajay.superassistant.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallExecutionContext;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具调用防死循环拦截器（工具调用措施）：
 * <ol>
 *   <li>限制单个会话（thread）内工具调用的总次数；</li>
 *   <li>检测"完全相同的工具 + 参数"的重复调用，超过阈值后直接短路，返回提示让 Agent 停止无意义循环。</li>
 * </ol>
 */
@Component
public class LoopGuardToolInterceptor extends ToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoopGuardToolInterceptor.class);

    private final int maxTotalToolCalls;
    private final int maxIdenticalCalls;

    private final ConcurrentHashMap<String, AtomicInteger> totalCalls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> identicalCalls = new ConcurrentHashMap<>();

    public LoopGuardToolInterceptor(
            @Value("${agent.guard.loop.max-total-tool-calls:60}") int maxTotalToolCalls,
            @Value("${agent.guard.loop.max-identical-calls:3}") int maxIdenticalCalls) {
        this.maxTotalToolCalls = maxTotalToolCalls;
        this.maxIdenticalCalls = maxIdenticalCalls;
    }

    @Override
    public String getName() {
        return "loop_guard_tool";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        String threadId = request.getExecutionContext()
                .flatMap(ToolCallExecutionContext::threadId)
                .orElse("unknown");

        // 1) 会话内工具调用总次数上限
        int total = totalCalls.computeIfAbsent(threadId, k -> new AtomicInteger()).incrementAndGet();
        if (total > maxTotalToolCalls) {
            log.warn("LoopGuard: thread {} exceeded total tool-call limit ({}), short-circuiting",
                    threadId, maxTotalToolCalls);
            return shortCircuit(request,
                    "已达到工具调用次数上限。请停止继续调用工具，基于已有信息直接给出回答，或向用户说明当前无法完成。");
        }

        // 2) 完全相同的"工具 + 参数"重复调用检测
        String fingerprint = threadId + "|" + request.getToolName() + "|" + request.getArguments();
        int identical = identicalCalls.computeIfAbsent(fingerprint, k -> new AtomicInteger()).incrementAndGet();
        if (identical > maxIdenticalCalls) {
            log.warn("LoopGuard: thread {} repeated identical call '{}' {} times, short-circuiting",
                    threadId, request.getToolName(), identical);
            return shortCircuit(request,
                    "你已用完全相同的参数重复调用工具 " + request.getToolName()
                            + " 多次且无进展。请停止重复，改用其它方式、向用户澄清需求，或基于已有信息直接给出回答。");
        }

        return handler.call(request);
    }

    private ToolCallResponse shortCircuit(ToolCallRequest request, String message) {
        return ToolCallResponse.builder()
                .toolName(request.getToolName())
                .toolCallId(request.getToolCallId())
                .status("error")
                .content(message)
                .build();
    }
}
