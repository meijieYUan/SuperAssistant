package com.itajay.superassistant.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.itajay.superassistant.plan.PlanStepHook;
import com.itajay.superassistant.service.AgentRunLogService;
import com.itajay.superassistant.service.PlanService;
import com.itajay.superassistant.service.PlanStepService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

@Component
public class WriterAgent {

    public final ReactAgent reactAgent;

    public WriterAgent(ChatModel chatModel,
                       SkillsAgentHook skillsAgentHook,
                       PlanStepService planStepService,
                       PlanService planService,
                       AgentRunLogService agentRunLogService) {
        this.reactAgent = ReactAgent.builder()
                .name("writer-agent")
                .description("专业文档撰写 agent：根据调研材料生成结构化 Markdown 报告或综述文档。")
                .model(chatModel)
                .instruction("""
                        你是 WriterAgent，负责根据会话中的调研材料和用户目标撰写文档。
                        输入：{input}
                        要求：
                        1. 使用 research-writing skill 的模板与规范。
                        2. 文档结构完整，包含标题、章节、汇总表和参考文献。
                        3. 所有事实和引用必须来自已提供的材料，不得编造来源。
                        4. 不要执行写入文件、发送邮件等副作用操作，直接输出 Markdown 文档内容。
                        """)
                .hooks(skillsAgentHook, new PlanStepHook(
                        "writer-agent", planStepService, planService, agentRunLogService))
                .build();
    }
}
