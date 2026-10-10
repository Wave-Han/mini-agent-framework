package com.example.agent.interceptor;

import com.example.agent.spec.AgentTool;
import com.example.agent.spec.ToolResult;

/**
 * ToolInterceptor —— 拦截器接口
 *
 * 对应 pi-mono AgentLoopConfig.beforeToolCall / afterToolCall
 * 对应 Spring AI CallAroundAdvisor
 * 对应 Java Servlet Filter / HandlerInterceptor
 *
 * 两层拦截：
 *   beforeToolCall = HandlerInterceptor.preHandle()
 *   afterToolCall  = HandlerInterceptor.afterCompletion()
 */
public interface ToolInterceptor {

    /**
     * 工具执行前调用
     * 返回非空 result 表示 block（阻止执行）
     *
     * 对应 pi-mono BeforeToolCallResult { block, reason }
     */
    default BeforeResult beforeToolCall(ToolCallContext context) {
        return BeforeResult.proceed();
    }

    /**
     * 工具执行后调用，可以修改结果
     *
     * 对应 pi-mono AfterToolCallResult { content, details, isError, terminate }
     */
    default AfterResult afterToolCall(ToolCallContext context, ToolResult result) {
        return AfterResult.keepOriginal();
    }

    /** 执行顺序（越小越先执行） */
    default int order() {
        return 0;
    }

    // ---- 值对象 ----

    record ToolCallContext(
        AgentTool tool,
        Object arguments,
        String sessionId
    ) {}

    record BeforeResult(boolean block, String reason) {
        public static BeforeResult proceed() { return new BeforeResult(false, null); }
        public static BeforeResult blocked(String reason) { return new BeforeResult(true, reason); }
    }

    record AfterResult(
        ToolResult overriddenResult,
        boolean modified
    ) {
        public static AfterResult keepOriginal() { return new AfterResult(null, false); }
        public static AfterResult override(ToolResult result) { return new AfterResult(result, true); }
    }
}
