package com.example.agent.harness;

import com.example.agent.harness.AgentLoop.ToolCallRequest;
import com.example.agent.interceptor.ToolInterceptor;
import com.example.agent.spec.AgentTool;
import com.example.agent.spec.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentLoopTest —— pins the three-phase interceptor pipeline:
 *   prepare (beforeToolCall, may block) -> execute -> finalize (afterToolCall, may override)
 *
 * Error philosophy under test: blocks, unknown tools and tool exceptions all
 * come back as ToolResult.error — errors stay DATA and flow back to the LLM.
 */
class AgentLoopTest {

    private AgentLoop loop;

    @AfterEach
    void tearDown() {
        if (loop != null) {
            loop.shutdown();
        }
    }

    /** Minimal tool for pipeline tests. */
    private static class EchoTool implements AgentTool {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "echoes arguments"; }
        @Override public String parameterSchema() { return "{}"; }
        @Override public ToolResult execute(Object arguments) {
            return ToolResult.success("echo:" + arguments);
        }
    }

    private static class ThrowingTool implements AgentTool {
        @Override public String name() { return "boom"; }
        @Override public String description() { return "always throws"; }
        @Override public String parameterSchema() { return "{}"; }
        @Override public ToolResult execute(Object arguments) {
            throw new IllegalStateException("kaboom");
        }
    }

    @Test
    @DisplayName("successful call passes through the pipeline")
    void executesToolAndReturnsSuccess() {
        loop = new AgentLoop(List.of(new EchoTool()), List.of());
        List<ToolResult> results = loop.executeToolCalls(
                List.of(new ToolCallRequest("echo", Map.of("k", "v"), "s1")));

        assertEquals(1, results.size());
        assertFalse(results.get(0).isError());
        assertTrue(results.get(0).content().get(0).text().contains("echo:"));
    }

    @Test
    @DisplayName("blocked by beforeToolCall: the reason becomes an error result")
    void blockedReasonFlowsBackAsErrorResult() {
        ToolInterceptor blocker = new ToolInterceptor() {
            @Override
            public BeforeResult beforeToolCall(ToolCallContext ctx) {
                return BeforeResult.blocked("policy: tool disabled");
            }
        };
        loop = new AgentLoop(List.of(new EchoTool()), List.of(blocker));

        List<ToolResult> results = loop.executeToolCalls(
                List.of(new ToolCallRequest("echo", Map.of(), "s1")));

        assertTrue(results.get(0).isError(), "a blocked call must surface as isError");
        assertEquals("policy: tool disabled", results.get(0).content().get(0).text(),
                "the block reason is the data the LLM will see");
    }

    @Test
    @DisplayName("afterToolCall can override the tool result")
    void afterInterceptorOverridesResult() {
        ToolInterceptor mutator = new ToolInterceptor() {
            @Override
            public AfterResult afterToolCall(ToolCallContext ctx, ToolResult result) {
                return AfterResult.override(ToolResult.success("sanitized"));
            }
        };
        loop = new AgentLoop(List.of(new EchoTool()), List.of(mutator));

        List<ToolResult> results = loop.executeToolCalls(
                List.of(new ToolCallRequest("echo", Map.of(), "s1")));

        assertEquals("sanitized", results.get(0).content().get(0).text());
    }

    @Test
    @DisplayName("unknown tool yields an error result, not an exception")
    void unknownToolProducesErrorResult() {
        loop = new AgentLoop(List.of(new EchoTool()), List.of());

        List<ToolResult> results = loop.executeToolCalls(
                List.of(new ToolCallRequest("nope", Map.of(), "s1")));

        assertTrue(results.get(0).isError());
        assertTrue(results.get(0).content().get(0).text().contains("Tool not found"));
    }

    @Test
    @DisplayName("tool exception is caught and converted to an error result")
    void toolExceptionBecomesErrorResult() {
        loop = new AgentLoop(List.of(new ThrowingTool()), List.of());

        List<ToolResult> results = loop.executeToolCalls(
                List.of(new ToolCallRequest("boom", Map.of(), "s1")));

        assertTrue(results.get(0).isError(),
                "a throwing tool must not blow up the loop");
        assertTrue(results.get(0).content().get(0).text().contains("kaboom"),
                "the exception message flows back as data");
    }
}
