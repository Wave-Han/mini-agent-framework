package com.example.agent;

import com.example.agent.harness.AgentLoop;
import com.example.agent.interceptor.AuditInterceptor;
import com.example.agent.session.AgentSession;
import com.example.agent.spec.AgentTool;
import com.example.agent.spec.ToolResult;
import com.example.agent.tools.CalculatorTool;
import com.example.agent.tools.CurrentTimeTool;

import java.util.List;
import java.util.Map;

/**
 * MiniAgentDemo —— 框架演示入口
 *
 * 演示完整的 Spec -> Harness -> Session -> Interceptor 工作流
 * 无需 LLM API Key，直接测试工具执行管道
 *
 * 对应 pi-mono 的完整数据流：
 *   用户输入 -> Session 记录 -> AgentLoop 调度 -> 拦截器链 -> Tool 执行 -> 结果返回
 */
public class MiniAgentDemo {

    public static void main(String[] args) {
        System.out.println("=== Mini Agent Framework Demo ===");
        System.out.println("基于 pi-mono 架构的 Java 版 AI Agent 框架\n");

        // Step 1: 注册工具（Spec 层）
        List<AgentTool> tools = List.of(
            new CurrentTimeTool(),
            new CalculatorTool()
        );

        System.out.println("已注册工具:");
        for (AgentTool tool : tools) {
            System.out.printf("  - %s: %s%n", tool.name(), tool.description());
        }
        System.out.println();

        // Step 2: 配置拦截器（Harness 层）
        List<com.example.agent.interceptor.ToolInterceptor> interceptors = List.of(
            new AuditInterceptor()
        );

        // Step 3: 创建 AgentLoop（Harness 核心）
        AgentLoop agentLoop = new AgentLoop(tools, interceptors);

        // Step 4: 创建 Session（会话管理）
        AgentSession session = new AgentSession("demo-session-001", "/tmp/demo");

        // === 测试 1: 查询时间 ===
        System.out.println("=== 测试 1: 查询时间 ===");
        session.appendMessage(AgentSession.MessageRole.USER, "现在几点了？");
        List<ToolResult> timeResults = agentLoop.executeToolCalls(
            List.of(new AgentLoop.ToolCallRequest("currentTime", Map.of("timezone", "Asia/Shanghai"), session.getSessionId()))
        );
        for (ToolResult r : timeResults) {
            session.appendToolResult("currentTime", r);
            System.out.println("  结果: " + r.content().get(0).text());
        }
        System.out.println();

        // === 测试 2: 数学计算 ===
        System.out.println("=== 测试 2: 数学计算 ===");
        session.appendMessage(AgentSession.MessageRole.USER, "计算 (123 + 456) * 7");
        List<ToolResult> calcResults = agentLoop.executeToolCalls(
            List.of(new AgentLoop.ToolCallRequest("calculate", Map.of("expression", "(123 + 456) * 7"), session.getSessionId()))
        );
        for (ToolResult r : calcResults) {
            session.appendToolResult("calculate", r);
            System.out.println("  结果: " + r.content().get(0).text());
        }
        System.out.println();

        // === 测试 3: 并行执行 ===
        System.out.println("=== 测试 3: 并行执行 ===");
        session.appendMessage(AgentSession.MessageRole.USER, "同时查时间和算数学");
        List<ToolResult> parallelResults = agentLoop.executeToolCalls(List.of(
            new AgentLoop.ToolCallRequest("currentTime", Map.of("format", "iso"), session.getSessionId()),
            new AgentLoop.ToolCallRequest("calculate", Map.of("expression", "2^10"), session.getSessionId()),
            new AgentLoop.ToolCallRequest("currentTime", Map.of("timezone", "America/New_York"), session.getSessionId())
        ));
        for (ToolResult r : parallelResults) {
            System.out.println("  结果: " + r.content().get(0).text());
        }
        System.out.println();

        // === 测试 4: 错误工具 ===
        System.out.println("=== 测试 4: 错误工具（不存在） ===");
        List<ToolResult> errResults = agentLoop.executeToolCalls(
            List.of(new AgentLoop.ToolCallRequest("nonExistentTool", Map.of(), session.getSessionId()))
        );
        for (ToolResult r : errResults) {
            System.out.println("  结果: " + r.content().get(0).text());
        }
        System.out.println();

        // Step 5: 查看 Session 历史
        System.out.println("=== Session 历史 ===");
        List<AgentSession.SessionEntry> context = session.buildContext();
        for (AgentSession.SessionEntry entry : context) {
            System.out.printf("  [%s] %s%n", entry.type(), truncate(String.valueOf(entry.data()), 80));
        }
        System.out.printf("%n共 %d 条记录, leafId=%s%n", context.size(), session.getLeafId());

        agentLoop.shutdown();

        // 架构映射总结
        System.out.println("\n=== 架构映射总结 ===");
        System.out.println("┌──────────────────┬──────────────────────────────┬──────────────────────────┐");
        System.out.println("│ pi-mono 概念     │ 本框架 Java 类               │ Spring AI 等价           │");
        System.out.println("├──────────────────┼──────────────────────────────┼──────────────────────────┤");
        System.out.println("│ AgentTool        │ AgentTool 接口               │ @Tool 注解               │");
        System.out.println("│ agentLoop()      │ AgentLoop 类                 │ ChatClient 内部 loop     │");
        System.out.println("│ beforeToolCall   │ ToolInterceptor.before()     │ CallAroundAdvisor        │");
        System.out.println("│ afterToolCall    │ ToolInterceptor.after()      │ CallAroundAdvisor        │");
        System.out.println("│ SessionManager   │ AgentSession 类              │ ChatMemory               │");
        System.out.println("│ api-registry     │ ModelRegistry 类             │ ChatModel Bean 体系      │");
        System.out.println("│ AgentToolResult  │ ToolResult record            │ Spring AI ToolResult     │");
        System.out.println("└──────────────────┴──────────────────────────────┴──────────────────────────┘");
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
