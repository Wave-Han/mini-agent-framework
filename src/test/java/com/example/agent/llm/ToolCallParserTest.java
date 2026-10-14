package com.example.agent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolCallParserTest —— regression tests for LLM response parsing
 *
 * RED case: argument values containing commas break the hand-rolled
 * split(",") parser. These tests pin the CORRECT behavior first,
 * then the implementation is fixed with Jackson.
 */
class ToolCallParserTest {

    @Test
    @DisplayName("parses plain string arguments")
    void parsesPlainArguments() {
        String json = "{\"name\":\"calculator\",\"arguments\":{\"expression\":\"1+2\"}}";

        List<Map<String, Object>> calls = ToolCallParser.parseToolCalls(json);

        assertEquals(1, calls.size());
        Map<String, Object> function = (Map<String, Object>) calls.get(0).get("function");
        assertEquals("calculator", function.get("name"));
        Map<String, Object> args = (Map<String, Object>) function.get("arguments");
        assertEquals("1+2", args.get("expression"));
    }

    @Test
    @DisplayName("RED: comma inside a string value must survive parsing")
    void commaInsideValueIsPreserved() {
        // Real-world case: user asks "calculate 1+2,3*4" and the LLM
        // passes the whole expression as one argument.
        String json = "{\"name\":\"calculator\",\"arguments\":{\"expression\":\"1+2,3*4\"}}";

        List<Map<String, Object>> calls = ToolCallParser.parseToolCalls(json);

        Map<String, Object> function = (Map<String, Object>) calls.get(0).get("function");
        Map<String, Object> args = (Map<String, Object>) function.get("arguments");
        assertEquals("1+2,3*4", args.get("expression"),
            "comma inside a quoted value must not split the argument");
    }

    @Test
    @DisplayName("RED: nested object arguments must be parsed, not flattened")
    void nestedObjectArgumentIsParsed() {
        String json = "{\"name\":\"report\",\"arguments\":{\"query\":{\"city\":\"Beijing\",\"days\":3}}}";

        List<Map<String, Object>> calls = ToolCallParser.parseToolCalls(json);

        Map<String, Object> function = (Map<String, Object>) calls.get(0).get("function");
        Map<String, Object> args = (Map<String, Object>) function.get("arguments");
        Object query = args.get("query");
        assertInstanceOf(Map.class, query, "nested object should stay a Map, not a flat string");
        assertEquals("Beijing", ((Map<?, ?>) query).get("city"));
    }

    @Test
    @DisplayName("response without tool_calls yields empty list")
    void plainTextResponseHasNoToolCalls() {
        String json = "{\"content\":\"你好，我可以帮你计算。\"}";

        List<Map<String, Object>> calls = ToolCallParser.parseToolCalls(json);

        assertTrue(calls.isEmpty());
    }
}
