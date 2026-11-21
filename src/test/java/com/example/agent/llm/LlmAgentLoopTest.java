package com.example.agent.llm;

import com.example.agent.spec.AgentTool;
import com.example.agent.spec.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmAgentLoopTest {

    /** Review finding Important#2: hand-rolled tool-definition JSON corrupted quotes/newlines. */
    @Test
    void toolDefinitionsJson_quotesAndNewlinesInDescriptionSurviveRoundTrip() throws Exception {
        String tricky = "查询\"当前\"时间\n第二行 \\ 反斜杠";
        String json = LlmAgentLoop.toolDefinitionsJson(List.of(toolWithDescription(tricky)));
        var parsed = new ObjectMapper().readTree(json);
        assertThat(parsed.get(0).path("function").path("description").asText()).isEqualTo(tricky);
    }

    private AgentTool toolWithDescription(String description) {
        return new AgentTool() {
            @Override public String name() { return "demo"; }
            @Override public String description() { return description; }
            @Override public String parameterSchema() { return "{}"; }
            @Override public ToolResult execute(Object arguments) { return ToolResult.success("ok"); }
        };
    }
}
