package com.itajay.superassistant.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 模型调用兜底拦截器（异常处理兜底措施）：
 * <ol>
 *   <li>对瞬时性错误（超时、限流、连接中断等）进行带指数退避的重试；</li>
 *   <li>重试耗尽后返回一个友好的兜底回答，避免整个 Agent 运行因模型异常而崩溃。</li>
 * </ol>
 */
@Component
public class ModelCallGuardInterceptor extends ModelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ModelCallGuardInterceptor.class);

    private final int maxAttempts;
    private final long initialDelayMs;
    private final long maxDelayMs;
    private final double backoffMultiplier;

    public ModelCallGuardInterceptor(
            @Value("${agent.guard.model-retry.max-attempts:3}") int maxAttempts,
            @Value("${agent.guard.model-retry.initial-delay-ms:500}") long initialDelayMs,
            @Value("${agent.guard.model-retry.max-delay-ms:8000}") long maxDelayMs,
            @Value("${agent.guard.model-retry.backoff-multiplier:2.0}") double backoffMultiplier) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.backoffMultiplier = backoffMultiplier;
    }

    @Override
    public String getName() {
        return "model_call_guard";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return handler.call(request);
            } catch (Exception e) {
                last = e;
                if (!isTransient(e) || attempt == maxAttempts) {
                    break;
                }
                long delay = Math.min(maxDelayMs, (long) (initialDelayMs * Math.pow(backoffMultiplier, attempt - 1)));
                log.warn("Model call failed (attempt {}/{}): {}. Retrying in {} ms",
                        attempt, maxAttempts, e.getMessage(), delay);
                sleepQuietly(delay);
            }
        }
        log.error("Model call failed after {} attempt(s): {}", maxAttempts,
                last == null ? "unknown" : last.getMessage(), last);
        return ModelResponse.of(new AssistantMessage(
                "抱歉，模型服务当前不可用或连续调用失败，我暂时无法完成本次回答。请稍后重试，或检查模型 API 配置（密钥、网络、额度）。"));
    }

    /**
     * 判断异常是否属于可重试的瞬时性错误。
     */
    public static boolean isTransient(Exception e) {
        if (e == null || e.getMessage() == null) {
            return false;
        }
        String m = e.getMessage().toLowerCase();
        return m.contains("timeout") || m.contains("timed out")
                || m.contains("connection") || m.contains("refused")
                || m.contains("rate limit") || m.contains("too many requests")
                || m.contains("429") || m.contains("500") || m.contains("502")
                || m.contains("503") || m.contains("504") || m.contains("unavailable")
                || m.contains("reset by peer") || m.contains("interrupted");
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
