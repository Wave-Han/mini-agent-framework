package com.example.agent.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * OllamaStreamParser —— accumulates an Ollama NDJSON stream into one response.
 *
 * Feeding rule: pass each stream line to {@link #accept(String, Consumer)} as it
 * arrives. Non-empty message.content chunks append to the assembled text and are
 * forwarded live to onDelta (nullable). tool_calls fragments (function name on the
 * first chunk, arguments as JSON-string pieces afterwards) are buffered silently
 * per call index — they never surface as content deltas. A done:true line marks
 * the stream finished. Malformed or blank lines are skipped silently — the error
 * stays data, matching ToolCallParser's philosophy.
 *
 * {@link #toResponseJson()} emits the framework's canonical flat shape
 * {"content": ..., "tool_calls": [...]}, which is exactly what
 * ToolCallParser.extractContent/parseToolCalls consume — so the agent loop can
 * treat a streamed turn identically to a blocking one.
 */
public final class OllamaStreamParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StringBuilder content = new StringBuilder();
    private final TreeMap<Integer, String> toolNames = new TreeMap<>();
    private final Map<Integer, StringBuilder> toolArgs = new HashMap<>();
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

        JsonNode message = root.path("message");

        String delta = message.path("content").asText("");
        if (!delta.isEmpty()) {
            content.append(delta);
            if (onDelta != null) {
                onDelta.accept(delta);
            }
        }

        JsonNode calls = message.path("tool_calls");
        if (calls.isArray()) {
            int position = 0;
            for (JsonNode call : calls) {
                int index = call.path("index").isInt() ? call.get("index").asInt() : position;
                JsonNode fn = call.path("function");
                String name = fn.path("name").asText("");
                if (!name.isEmpty()) {
                    toolNames.putIfAbsent(index, name);
                }
                JsonNode args = fn.path("arguments");
                if (!args.isMissingNode()) {
                    // arguments arrive as string fragments, or complete objects
                    String fragment = args.isTextual() ? args.asText() : args.toString();
                    toolArgs.computeIfAbsent(index, k -> new StringBuilder()).append(fragment);
                }
                position++;
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

    /** Canonical flat response JSON: {"content": ...} plus "tool_calls" when any were buffered. */
    public String toResponseJson() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("content", content.toString());
        if (!toolNames.isEmpty()) {
            ArrayNode calls = node.putArray("tool_calls");
            for (Map.Entry<Integer, String> entry : toolNames.entrySet()) {
                ObjectNode fn = calls.addObject().putObject("function");
                fn.put("name", entry.getValue());
                fn.set("arguments", MAPPER.valueToTree(parseArguments(entry.getKey())));
            }
        }
        return node.toString();
    }

    /** Accumulated argument fragments parsed into an object; anything else resolves to an empty map. */
    private Map<String, Object> parseArguments(int index) {
        StringBuilder raw = toolArgs.get(index);
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        try {
            JsonNode parsed = MAPPER.readTree(raw.toString());
            if (!parsed.isObject()) {
                return Map.of();
            }
            Map<String, Object> asMap = MAPPER.convertValue(parsed, new TypeReference<Map<String, Object>>() {});
            return asMap != null ? asMap : Map.of();
        } catch (JsonProcessingException e) {
            return Map.of(); // unparseable fragments stay data, not exceptions
        }
    }
}
