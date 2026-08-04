package com.itajay.superassistant.service;

import com.itajay.superassistant.entity.AgentRunLog;
import com.itajay.superassistant.mapper.AgentRunLogMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentRunLogServiceTest {

    @Test
    void truncatesLongSummariesBeforeInsert() {
        AgentRunLogMapper mapper = mock(AgentRunLogMapper.class);
        AgentRunLogService service = new AgentRunLogService(mapper);

        service.logStart("thread-1", 1L, 2L, "writer-agent", "x".repeat(20_000));

        ArgumentCaptor<AgentRunLog> captor = ArgumentCaptor.forClass(AgentRunLog.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getInputSummary()).hasSize(8_000);
        assertThat(captor.getValue().getPhase()).isEqualTo("START");
    }

    @Test
    void keepsLoggingBestEffortWhenInsertFails() {
        AgentRunLogMapper mapper = mock(AgentRunLogMapper.class);
        doThrow(new RuntimeException("db down")).when(mapper).insert(any(AgentRunLog.class));
        AgentRunLogService service = new AgentRunLogService(mapper);

        service.logEnd("thread-1", 1L, 2L, "writer-agent", "ok", null);

        verify(mapper).insert(any(AgentRunLog.class));
    }
}
