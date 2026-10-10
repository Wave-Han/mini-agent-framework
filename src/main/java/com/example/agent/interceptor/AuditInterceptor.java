package com.example.agent.interceptor;

import com.example.agent.spec.ToolResult;

/**
 * AuditInterceptor —— 审计日志拦截器
 *
 * 对应 pi-mono 02-interceptors.ts myBeforeToolCall
 * 对应 Spring AI CallAroundAdvisor
 */
public class AuditInterceptor implements ToolInterceptor {

    @Override
    public BeforeResult beforeToolCall(ToolCallContext context) {
        System.out.printf("  [审计 beforeToolCall] tool=%s args=%s%n",
            context.tool().name(),
            truncate(String.valueOf(context.arguments()), 80)
        );
        return BeforeResult.proceed();
    }

    @Override
    public AfterResult afterToolCall(ToolCallContext context, ToolResult result) {
        if (result.isError()) {
            System.out.printf("  [审计 afterToolCall] tool=%s ERROR: %s%n",
                context.tool().name(),
                truncate(String.valueOf(result.content()), 100)
            );
        } else {
            System.out.printf("  [审计 afterToolCall] tool=%s OK%n", context.tool().name());
        }
        return AfterResult.keepOriginal();
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
