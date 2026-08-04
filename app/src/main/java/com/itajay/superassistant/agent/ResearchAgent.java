package com.itajay.superassistant.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.itajay.superassistant.plan.PlanStepHook;
import com.itajay.superassistant.service.AgentRunLogService;
import com.itajay.superassistant.service.PlanService;
import com.itajay.superassistant.service.PlanStepService;
import com.itajay.superassistant.tool.WebSearchTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

@Component
public class ResearchAgent {

    public final ReactAgent reactAgent;

    public ResearchAgent(WebSearchTool webSearchTool,
                         ChatModel chatModel,
                         SkillsAgentHook skillsAgentHook,
                         PlanStepService planStepService,
                         PlanService planService,
                         AgentRunLogService agentRunLogService) {
        this.reactAgent = ReactAgent.builder()
                .name("research-agent")
                .description("科研论文与专业资料调研 agent：使用 research-writing skill 中的论文检索网站、筛选标准和信息提取规则，结合网页搜索与网页抓取输出带来源链接的调研材料。")
                .model(chatModel)
                .instruction("""
                        你是 ResearchAgent，负责为后续写作收集高质量资料。
                        输入：{input}
                        请严格按照 research-writing skill 执行以下工作：
                        1. 根据 skill 中的论文检索网站优先级选择检索来源，如 arXiv、Semantic Scholar、Google Scholar、DBLP、CNKI、PubMed、IEEE Xplore 等。
                        2. 按 skill 的筛选标准选择高质量论文，优先顶会/顶刊、高引用、近 3 年文献，并覆盖不同方法流派。
                        3. 对关键页面使用 webCrawl 获取全文，并按 skill 要求提取论文概要、方法框架、关键机制与创新点、训练目标。
                        4. 整理为结构化调研材料，包含来源标题、URL 和关键信息摘要。
                        5. 不要执行写入、删除、发送邮件等副作用操作。
                        输出格式：Markdown 调研材料，末尾附参考文献列表。
                        """)
                .methodTools(webSearchTool)
                .hooks(skillsAgentHook, new PlanStepHook(
                        "research-agent", planStepService, planService, agentRunLogService))
                .build();
    }
}
