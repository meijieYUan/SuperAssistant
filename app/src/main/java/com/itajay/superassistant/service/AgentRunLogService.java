package com.itajay.superassistant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.itajay.superassistant.entity.AgentRunLog;
import com.itajay.superassistant.mapper.AgentRunLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AgentRunLogService {

    private static final Logger log = LoggerFactory.getLogger(AgentRunLogService.class);
    private static final int MAX_SUMMARY_LENGTH = 8_000;

    private final AgentRunLogMapper agentRunLogMapper;

    public AgentRunLogService(AgentRunLogMapper agentRunLogMapper) {
        this.agentRunLogMapper = agentRunLogMapper;
    }

    public void logStart(String threadId, Long planId, Long stepId, String agentName, String input) {
        insert("START", "RUNNING", threadId, planId, stepId, agentName, input, null, null);
    }

    public void logEnd(String threadId, Long planId, Long stepId, String agentName, String output, String error) {
        insert("END", error == null ? "COMPLETED" : "FAILED",
                threadId, planId, stepId, agentName, null, output, error);
    }

    public List<AgentRunLog> search(Long planId, String threadId, String agentName, int limit) {
        LambdaQueryWrapper<AgentRunLog> wrapper = new LambdaQueryWrapper<>();
        if (planId != null) {
            wrapper.eq(AgentRunLog::getPlanId, planId);
        }
        if (threadId != null && !threadId.isBlank()) {
            wrapper.eq(AgentRunLog::getThreadId, threadId);
        }
        if (agentName != null && !agentName.isBlank()) {
            wrapper.eq(AgentRunLog::getAgentName, agentName);
        }
        wrapper.orderByDesc(AgentRunLog::getId).last("LIMIT " + Math.max(1, Math.min(limit, 200)));
        return agentRunLogMapper.selectList(wrapper);
    }

    private void insert(String phase,
                        String status,
                        String threadId,
                        Long planId,
                        Long stepId,
                        String agentName,
                        String input,
                        String output,
                        String error) {
        try {
            AgentRunLog record = new AgentRunLog();
            record.setThreadId(threadId);
            record.setPlanId(planId);
            record.setStepId(stepId);
            record.setAgentName(agentName);
            record.setPhase(phase);
            record.setStatus(status);
            record.setInputSummary(truncate(input));
            record.setOutputSummary(truncate(output));
            record.setErrorMessage(truncate(error));
            record.setCreatedAt(LocalDateTime.now());
            agentRunLogMapper.insert(record);
        } catch (Exception e) {
            log.warn("Failed to persist agent run log [agent={}, phase={}]", agentName, phase, e);
        }
    }

    private String truncate(String text) {
        if (text == null || text.codePointCount(0, text.length()) <= MAX_SUMMARY_LENGTH) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, MAX_SUMMARY_LENGTH));
    }
}
