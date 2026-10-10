package com.example.agent.spec;

/**
 * AgentTool —— 工具的 Spec 声明接口
 *
 * 对应 pi-mono packages/agent/src/types.ts AgentTool
 * 对应 Spring AI @Tool 注解
 * 对应 LangChain4j @Tool 注解
 */
public interface AgentTool {

    /** 工具名称（LLM 通过名称调用） */
    String name();

    /** 工具描述（LLM 读取描述决定是否调用） */
    String description();

    /** 参数 JSON Schema */
    String parameterSchema();

    /**
     * 执行工具
     * 对应 pi-mono AgentTool.execute(toolCallId, args, signal, onUpdate, ctx)
     */
    ToolResult execute(Object arguments);

    /** 执行模式：顺序 vs 并行 */
    default ExecutionMode executionMode() {
        return ExecutionMode.PARALLEL;
    }

    enum ExecutionMode {
        SEQUENTIAL,
        PARALLEL
    }
}
