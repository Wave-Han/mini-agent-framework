package com.example.agent.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * OllamaClient —— Ollama HTTP API 客户端
 *
 * 对应 pi-mono packages/ai/src/providers/ 中的 Provider 实现
 * 对应 Spring AI 的 ChatModel 接口实现（如 OpenAiChatModel）
 *
 * Ollama API:
 *   POST /api/chat  — 对应 OpenAI Chat Completions API
 *   支持 tools 参数实现 Tool Calling（需要模型支持）
 *   stream:true 返回 NDJSON 行流，由 OllamaStreamParser 汇聚
 *
 * Request bodies are built with Jackson: the previous hand-rolled builder
 * corrupted quotes, newlines and backslashes inside message content.
 */
public class OllamaClient {

    private static final String DEFAULT_BASE_URL = "http://localhost:11434";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient httpClient;

    public OllamaClient() {
        this(DEFAULT_BASE_URL);
    }

    public OllamaClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /**
     * 发送 Chat 请求（阻塞，stream:false）—— 降级路径
     */
    public String chat(String model, List<Map<String, Object>> messages, String toolsJson) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/chat"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                buildRequestBody(model, messages, toolsJson, false)))
            .timeout(Duration.ofMinutes(5))
            .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Ollama API error: " + response.statusCode() + " " + response.body());
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Ollama API call failed: " + e.getMessage(), e);
        }
    }

    /**
     * 发送流式 Chat 请求（stream:true，NDJSON 行流）
     *
     * Content chunks are forwarded live to onDelta (nullable); tool_calls fragments
     * are buffered inside the parser. Returns the assembled canonical flat response
     * JSON — same contract as chat()'s consumers expect (ToolCallParser shape).
     */
    public String chatStream(String model, List<Map<String, Object>> messages, String toolsJson,
                             Consumer<String> onDelta) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/chat"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                buildRequestBody(model, messages, toolsJson, true)))
            .timeout(Duration.ofMinutes(5))
            .build();

        try {
            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            // try-with-resources wraps BOTH paths: a non-200 must not leak the stream
            try (Stream<String> lines = response.body()) {
                if (response.statusCode() != 200) {
                    throw errorFromStream(response.statusCode(), lines);
                }
                OllamaStreamParser parser = new OllamaStreamParser();
                lines.forEach(line -> parser.accept(line, onDelta));
                return parser.toResponseJson();
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Ollama API call failed: " + e.getMessage(), e);
        }
    }

    public boolean isServerRunning() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tags"))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- 请求体构建（Jackson-backed，可测） ----

    /** Drains the (already-open) error body into a RuntimeException carrying status + body. */
    static RuntimeException errorFromStream(int statusCode, Stream<String> lines) {
        String body = lines.collect(java.util.stream.Collectors.joining("\n"));
        if (body.length() > 500) {
            body = body.substring(0, 500);
        }
        return new RuntimeException("Ollama API error: " + statusCode + " " + body);
    }

    /** Builds the /api/chat request body; toolsJson is embedded verbatim when present. */
    static String buildRequestBody(String model, List<Map<String, Object>> messages,
                                   String toolsJson, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", stream);
        if (toolsJson != null && !toolsJson.isEmpty()) {
            try {
                body.put("tools", MAPPER.readTree(toolsJson));
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("toolsJson is not valid JSON: " + e.getMessage(), e);
            }
        }
        try {
            return MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize request body: " + e.getMessage(), e);
        }
    }
}
