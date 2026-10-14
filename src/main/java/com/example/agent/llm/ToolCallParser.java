package com.example.agent.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ToolCallParser —— parses tool_calls from an LLM response JSON (Jackson-backed)
 *
 * Replaces ~130 lines of hand-rolled string parsing that broke on:
 *   1. commas inside quoted argument values (split(",") truncated them)
 *   2. nested object arguments (flattened to strings)
 * Jackson handles quoting, escaping and nesting by construction.
 *
 * Accepts either a full response (with a "tool_calls" array) or a single
 * call fragment {"name":...,"arguments":{...}}. Malformed JSON yields an
 * empty list instead of an exception: the error stays data, the caller
 * decides what to do with it.
 */
public class ToolCallParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Extracts the assistant text content; empty string when absent. */
    public static String extractContent(String json) {
        if (json == null || json.isBlank()) return "";
        try {
            return MAPPER.readTree(json).path("content").asText("");
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    /**
     * Parses tool calls into the unified shape:
     *   [{ "function": { "name": ..., "arguments": {...} } }]
     */
    public static List<Map<String, Object>> parseToolCalls(String json) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (json == null || json.isBlank()) return result;
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode callNodes = selectCallNodes(root);
            if (callNodes == null) return result;

            for (JsonNode node : callNodes) {
                // OpenAI-style nests under "function"; flat fragments do not
                JsonNode fn = node.has("function") ? node.get("function") : node;
                Map<String, Object> args = MAPPER.convertValue(
                        fn.path("arguments"), new TypeReference<Map<String, Object>>() {});

                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", fn.path("name").asText(""));
                function.put("arguments", args != null ? args : Map.of());
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("function", function);
                result.add(call);
            }
        } catch (JsonProcessingException e) {
            // Malformed JSON -> no tool calls; keep going with an empty list
        }
        return result;
    }

    private static JsonNode selectCallNodes(JsonNode root) {
        if (root.has("tool_calls") && root.get("tool_calls").isArray()) {
            return root.get("tool_calls");
        }
        if (root.has("name") && root.has("arguments")) {
            // Wrap a single flat fragment into a one-element array
            ArrayNode array = MAPPER.createArrayNode();
            array.add(root);
            return array;
        }
        return null;
    }
}
