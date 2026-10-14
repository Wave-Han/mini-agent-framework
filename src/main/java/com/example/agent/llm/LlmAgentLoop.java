package com.example.agent.llm;

import com.example.agent.harness.AgentLoop;
import com.example.agent.interceptor.ToolInterceptor;
import com.example.agent.spec.AgentTool;
import com.example.agent.spec.ToolResult;

import java.util.*;

/**
 * LlmAgentLoop —— 真正的 LLM Agent 循环
 *
 * 对应 pi-mono packages/agent/src/agent-loop.ts 中的双层 while 循环
 * 对应 Spring AI ChatClient 的内部 tool-calling loop
 *
 * 核心流程（与 pi-mono agentLoop 完全一致）：
 *   while (true) {
 *     1. 构建消息 -> 发送给 LLM
 *     2. LLM 返回 tool_calls 或纯文本
 *     3. 如果有 tool_calls -> 执行工具 -> 结果追加到消息 -> 继续
 *     4. 如果是纯文本 -> 返回给用户 -> break
 *   }
 */
public class LlmAgentLoop {

    private static final int MAX_ITERATIONS = 10;

    private final OllamaClient client;
    private final String model;
    private final AgentLoop toolExecutor;
    private final List<AgentTool> tools;
    private final List<Map<String, Object>> toolDefinitions;

    public LlmAgentLoop(OllamaClient client, String model, List<AgentTool> tools,
                        List<ToolInterceptor> interceptors) {
        this.client = client;
        this.model = model;
        this.tools = tools;
        this.toolExecutor = new AgentLoop(tools, interceptors);
        this.toolDefinitions = buildToolDefinitions(tools);
    }

    /**
     * 运行 Agent 循环 —— 对应 pi-mono agentLoop() 的内层循环
     */
    public String run(String userMessage) {
        List<Map<String, Object>> messages = new ArrayList<>();

        messages.add(Map.of("role", "system", "content", buildSystemPrompt()));
        messages.add(Map.of("role", "user", "content", userMessage));

        System.out.println("\n[Agent Loop] 开始执行，模型: " + model);
        System.out.println("[Agent Loop] 用户输入: " + userMessage);

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            System.out.println("\n--- 迭代 " + (i + 1) + "/" + MAX_ITERATIONS + " ---");

            // 1. 调用 LLM
            System.out.println("[LLM Request] 发送 " + messages.size() + " 条消息...");
            String response = client.chat(model, messages, toJson(toolDefinitions));
            System.out.println("[LLM Response] " + truncate(response, 300));

            // 2. 解析响应（Jackson-backed，见 ToolCallParser）
            String content = ToolCallParser.extractContent(response);
            List<Map<String, Object>> toolCalls = ToolCallParser.parseToolCalls(response);

            // 追加 assistant 消息到历史
            Map<String, Object> msgEntry = new LinkedHashMap<>();
            msgEntry.put("role", "assistant");
            msgEntry.put("content", content != null ? content : "");
            if (toolCalls != null && !toolCalls.isEmpty()) {
                msgEntry.put("tool_calls", toolCalls);
            }
            messages.add(msgEntry);

            // 3. 没有 tool_calls -> 返回文本
            if (toolCalls == null || toolCalls.isEmpty()) {
                System.out.println("\n[Agent Loop] LLM 返回最终回复（无 tool_calls）");
                return content != null ? content : "(空回复)";
            }

            // 4. 执行工具调用
            System.out.println("[Agent Loop] LLM 请求调用 " + toolCalls.size() + " 个工具");
            for (Map<String, Object> tc : toolCalls) {
                @SuppressWarnings("unchecked")
                Map<String, Object> func = (Map<String, Object>) tc.get("function");
                String toolName = (String) func.get("name");
                @SuppressWarnings("unchecked")
                Map<String, Object> argsMap = (Map<String, Object>) func.get("arguments");
                System.out.println("  -> " + toolName + "(" + truncate(String.valueOf(argsMap), 100) + ")");

                Map<String, Object> args = argsMap != null ? argsMap : Map.of();
                List<ToolResult> results = toolExecutor.executeToolCalls(
                    List.of(new AgentLoop.ToolCallRequest(toolName, args, "llm-session"))
                );

                for (ToolResult r : results) {
                    String resultText = r.content().get(0).text();
                    Map<String, Object> toolMsg = new LinkedHashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("content", resultText);
                    messages.add(toolMsg);
                    System.out.println("  <- " + truncate(resultText, 100));

                    if (r.terminate()) {
                        System.out.println("[Agent Loop] 工具请求终止循环");
                        return resultText;
                    }
                }
            }
        }

        return "达到最大迭代次数 (" + MAX_ITERATIONS + ")，Agent 循环终止。";
    }

    public void shutdown() {
        toolExecutor.shutdown();
    }

    // ---- 内部方法 ----

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个有用的AI助手。你可以使用以下工具来帮助用户：\n\n");
        for (AgentTool tool : tools) {
            sb.append("- ").append(tool.name()).append(": ").append(tool.description()).append("\n");
        }
        sb.append("\n当需要使用工具时，请调用相应的工具。否则直接回答用户的问题。");
        sb.append("\n请用中文回答。");
        return sb.toString();
    }

    private List<Map<String, Object>> buildToolDefinitions(List<AgentTool> tools) {
        List<Map<String, Object>> defs = new ArrayList<>();
        for (AgentTool tool : tools) {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("type", "function");
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.put("parameters", Map.of("type", "object", "properties", Map.of()));
            def.put("function", function);
            defs.add(def);
        }
        return defs;
    }

    private String toJson(Object obj) {
        if (obj instanceof String) return (String) obj;
        if (obj instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            List<?> list = (List<?>) obj;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(toJson(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        if (obj instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            Map<?, ?> map = (Map<?, ?>) obj;
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(e.getKey()).append("\":");
                Object v = e.getValue();
                if (v instanceof String) sb.append("\"").append(v).append("\"");
                else if (v instanceof Map || v instanceof List) sb.append(toJson(v));
                else sb.append(v);
            }
            sb.append("}");
            return sb.toString();
        }
        return obj.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "(null)";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
