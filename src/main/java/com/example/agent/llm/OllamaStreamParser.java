package com.example.agent.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.function.Consumer;

/**
 * OllamaStreamParser —— accumulates an Ollama NDJSON stream into one response.
 *
 * Feeding rule: pass each stream line to {@link #accept(String, Consumer)} as it
 * arrives. Non-empty message.content chunks append to the assembled text and are
 * forwarded live to onDelta (nullable). A done:true line marks the stream finished.
 * Malformed or blank lines are skipped silently — the error stays data, matching
 * ToolCallParser's philosophy.
 *
 * {@link #toResponseJson()} emits the framework's canonical flat shape
 * {"content": ...} (tool_calls joined by Task 2), which is exactly what
 * ToolCallParser.extractContent/parseToolCalls consume — so the agent loop can
 * treat a streamed turn identically to a blocking one.
 */
public final class OllamaStreamParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StringBuilder content = new StringBuilder();
    private boolean done;

    /** Accepts one NDJSON line from the stream. onDelta may be null. */
    public void accept(String line, Consumer<String> onDelta) {
        if (line == null || line.isBlank()) {
            return;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(line);
        } catch (JsonProcessingException e) {
            return; // malformed line: skip, keep the stream alive
        }

        String delta = root.path("message").path("content").asText("");
        if (!delta.isEmpty()) {
            content.append(delta);
            if (onDelta != null) {
                onDelta.accept(delta);
            }
        }
        if (root.path("done").asBoolean(false)) {
            done = true;
        }
    }

    /** True once a done:true line has been seen. */
    public boolean isDone() {
        return done;
    }

    /** Canonical flat response JSON: {"content": "<assembled text>"}. */
    public String toResponseJson() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("content", content.toString());
        return node.toString();
    }
}
