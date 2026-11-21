package com.example.agent.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
}
