package com.example.agent.spec;

import java.util.List;

/**
 * ToolResult —— 工具执行结果
 *
 * 对应 pi-mono AgentToolResult { content, details, isError, terminate }
 * 对应 Spring AI ToolResult
 */
public record ToolResult(
    List<Content> content,
    Object details,
    boolean isError,
    boolean terminate
) {
    public record Content(String type, String text) {
        public static Content text(String text) {
            return new Content("text", text);
        }
    }

    public static ToolResult success(String text) {
        return new ToolResult(List.of(Content.text(text)), null, false, false);
    }

    public static ToolResult error(String message) {
        return new ToolResult(List.of(Content.text(message)), null, true, false);
    }

    public static ToolResult terminate(String text) {
        return new ToolResult(List.of(Content.text(text)), null, false, true);
    }
}
