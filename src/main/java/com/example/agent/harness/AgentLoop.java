package com.example.agent.harness;

import com.example.agent.interceptor.ToolInterceptor;
import com.example.agent.spec.AgentTool;
import com.example.agent.spec.ToolResult;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AgentLoop —— Agent 循环引擎（Harness 核心）
 *
 * 对应 pi-mono packages/agent/src/agent-loop.ts agentLoop() / runLoop()
 * 对应 Spring AI ChatClient 内部的 tool-calling loop
 * 对应 Java DispatcherServlet —— 接收请求、路由到工具、返回响应
 *
 * 核心循环：
 *   while (not stopped) {
 *     1. 发送 messages 给 LLM
 *     2. LLM 返回 toolCalls 或最终文本
 *     3. 如果有 toolCalls → 执行工具 → 收集结果 → 加回 messages → 继续
 *     4. 如果是最终文本 → 返回
 *   }
 */
public class AgentLoop {

    private final Map<String, AgentTool> toolRegistry;
    private final List<ToolInterceptor> interceptors;
    private final ExecutorService executor;

    public AgentLoop(List<AgentTool> tools, List<ToolInterceptor> interceptors) {
        this.toolRegistry = new HashMap<>();
        for (AgentTool tool : tools) {
            toolRegistry.put(tool.name(), tool);
        }
        this.interceptors = new ArrayList<>(interceptors);
        this.interceptors.sort(Comparator.comparingInt(ToolInterceptor::order));
        this.executor = Executors.newCachedThreadPool();
    }

    /**
     * 执行一轮工具调用
     *
     * 对应 pi-mono executeToolCalls() / executeToolCallsParallel()
     *
     * 流程：
     *   prepare (beforeInterceptor) → execute → finalize (afterInterceptor)
     */
    public List<ToolResult> executeToolCalls(List<ToolCallRequest> requests) {
        // 按 executionMode 分组
        Map<AgentTool.ExecutionMode, List<ToolCallRequest>> grouped = new EnumMap<>(AgentTool.ExecutionMode.class);
        for (ToolCallRequest req : requests) {
            AgentTool tool = toolRegistry.get(req.toolName());
            AgentTool.ExecutionMode mode = tool != null ? tool.executionMode() : AgentTool.ExecutionMode.SEQUENTIAL;
            grouped.computeIfAbsent(mode, k -> new ArrayList<>()).add(req);
        }

        List<ToolResult> results = new ArrayList<>();

        // Sequential tools
        for (ToolCallRequest req : grouped.getOrDefault(AgentTool.ExecutionMode.SEQUENTIAL, List.of())) {
            results.add(executeSingle(req));
        }

        // Parallel tools
        List<ToolCallRequest> parallelReqs = grouped.getOrDefault(AgentTool.ExecutionMode.PARALLEL, List.of());
        if (!parallelReqs.isEmpty()) {
            List<CompletableFuture<ToolResult>> futures = parallelReqs.stream()
                .map(req -> CompletableFuture.supplyAsync(() -> executeSingle(req), executor))
                .toList();

            for (CompletableFuture<ToolResult> f : futures) {
                results.add(f.join());
            }
        }

        return results;
    }

    /**
     * 执行单个工具 —— 完整的 prepare → execute → finalize 管道
     *
     * 对应 pi-mono agent-loop.ts:
     *   prepareToolCall()     → prepare 阶段
     *   executePreparedToolCall() → execute 阶段
     *   finalizeExecutedToolCall() → finalize 阶段
     */
    private ToolResult executeSingle(ToolCallRequest request) {
        AgentTool tool = toolRegistry.get(request.toolName());
        if (tool == null) {
            return ToolResult.error("Tool not found: " + request.toolName());
        }

        ToolInterceptor.ToolCallContext ctx = new ToolInterceptor.ToolCallContext(
            tool, request.arguments(), request.sessionId()
        );

        // Phase 1: Prepare —— beforeToolCall 拦截器
        for (ToolInterceptor interceptor : interceptors) {
            ToolInterceptor.BeforeResult before = interceptor.beforeToolCall(ctx);
            if (before.block()) {
                return ToolResult.error(before.reason() != null ? before.reason() : "Blocked by policy");
            }
        }

        // Phase 2: Execute —— 实际执行工具
        ToolResult result;
        try {
            result = tool.execute(request.arguments());
        } catch (Exception e) {
            result = ToolResult.error("Execution failed: " + e.getMessage());
        }

        // Phase 3: Finalize —— afterToolCall 拦截器（可修改结果）
        for (ToolInterceptor interceptor : interceptors) {
            ToolInterceptor.AfterResult after = interceptor.afterToolCall(ctx, result);
            if (after.modified() && after.overriddenResult() != null) {
                result = after.overriddenResult();
            }
        }

        return result;
    }

    public void shutdown() {
        executor.shutdown();
    }

    public record ToolCallRequest(String toolName, Object arguments, String sessionId) {}
}
