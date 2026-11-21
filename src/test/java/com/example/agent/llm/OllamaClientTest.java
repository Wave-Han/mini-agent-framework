package com.example.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaClientTest {

    @Test
    void buildRequestBody_specialCharactersSurviveRoundTrip() throws Exception {
        var messages = List.<Map<String, Object>>of(
            Map.of("role", "user", "content", "line1\nline2 \"quoted\" \\ end"));
        String body = OllamaClient.buildRequestBody("llama3", messages, "[{\"type\":\"function\"}]", true);
        var root = new ObjectMapper().readTree(body);
        assertThat(root.path("model").asText()).isEqualTo("llama3");
        assertThat(root.path("stream").asBoolean()).isTrue();
        assertThat(root.path("tools").toString()).isEqualTo("[{\"type\":\"function\"}]");
        assertThat(root.path("messages").get(0).path("content").asText())
            .isEqualTo("line1\nline2 \"quoted\" \\ end"); // hand-rolled builder corrupted exactly this
    }

    @Test
    void buildRequestBody_withoutTools_omitsToolsField() throws Exception {
        String body = OllamaClient.buildRequestBody("llama3",
            List.of(Map.of("role", "user", "content", "hi")), null, false);
        var root = new ObjectMapper().readTree(body);
        assertThat(root.path("stream").asBoolean()).isFalse();
        assertThat(root.has("tools")).isFalse();
        assertThat(root.path("messages").get(0).path("content").asText()).isEqualTo("hi");
    }

    /** Review finding Important#1: non-200 must drain the body into the error message. */
    @Test
    void errorFromStream_includesStatusAndDrainedBody() {
        var lines = java.util.stream.Stream.of("{\"error\":\"model not found\"}");
        var ex = OllamaClient.errorFromStream(404, lines);
        assertThat(ex).isInstanceOf(RuntimeException.class)
            .hasMessageContaining("404")
            .hasMessageContaining("model not found");
    }
}
