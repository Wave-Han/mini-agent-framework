package com.example.agent.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaStreamParserTest {

    @Test
    void contentChunks_eachEmitDelta_andAssembleFullText() {
        var parser = new OllamaStreamParser();
        List<String> deltas = new ArrayList<>();
        parser.accept("{\"message\":{\"role\":\"assistant\",\"content\":\"你好\"},\"done\":false}", deltas::add);
        parser.accept("{\"message\":{\"role\":\"assistant\",\"content\":\",世界\"},\"done\":false}", deltas::add);
        parser.accept("{\"done\":true,\"total_duration\":1}", null);
        assertThat(deltas).containsExactly("你好", ",世界");
        assertThat(parser.isDone()).isTrue();
        assertThat(ToolCallParser.extractContent(parser.toResponseJson())).isEqualTo("你好,世界");
        assertThat(ToolCallParser.parseToolCalls(parser.toResponseJson())).isEmpty();
    }

    @Test
    void toolCallArgumentsFragmentedAcrossChunks_mergedAndParsedAtDone() {
        var parser = new OllamaStreamParser();
        List<String> deltas = new ArrayList<>();
        parser.accept("{\"message\":{\"tool_calls\":[{\"function\":{\"name\":\"calculator\",\"arguments\":\"{\\\"expr\\\":\"}}]},\"done\":false}", deltas::add);
        parser.accept("{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":\"\\\"1+2\\\"}\"}}]},\"done\":false}", deltas::add);
        parser.accept("{\"done\":true}", null);
        assertThat(deltas).isEmpty(); // tool fragments never surface as content deltas
        var calls = ToolCallParser.parseToolCalls(parser.toResponseJson());
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).get("function"))
            .extracting(f -> ((Map<?, ?>) f).get("name"), f -> ((Map<?, ?>) f).get("arguments"))
            .containsExactly("calculator", Map.of("expr", "1+2"));
    }

    @Test
    void malformedLine_isSkipped_streamContinues() {
        var parser = new OllamaStreamParser();
        List<String> deltas = new ArrayList<>();
        parser.accept("not json at all", deltas::add);
        parser.accept("", deltas::add);
        parser.accept("{\"message\":{\"content\":\"ok\"},\"done\":false}", deltas::add);
        parser.accept("{\"done\":true}", null);
        assertThat(deltas).containsExactly("ok");
        assertThat(ToolCallParser.extractContent(parser.toResponseJson())).isEqualTo("ok");
    }

    @Test
    void argumentsAsCompleteObject_isMerged() {
        var parser = new OllamaStreamParser();
        parser.accept("{\"message\":{\"tool_calls\":[{\"function\":{\"name\":\"now\",\"arguments\":{\"tz\":\"UTC\"}}}]},\"done\":false}", null);
        parser.accept("{\"done\":true}", null);
        var calls = ToolCallParser.parseToolCalls(parser.toResponseJson());
        assertThat(calls.get(0).get("function")).isInstanceOf(Map.class)
            .satisfies(f -> assertThat(((Map<?, ?>) f).get("arguments")).isEqualTo(Map.of("tz", "UTC")));
    }
}
