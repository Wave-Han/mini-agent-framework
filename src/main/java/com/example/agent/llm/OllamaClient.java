package com.example.agent.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OllamaClient —— Ollama HTTP API 客户端
 *
 * 对应 pi-mono packages/ai/src/providers/ 中的 Provider 实现
 * 对应 Spring AI 的 ChatModel 接口实现（如 OpenAiChatModel）
 *
 * Ollama API:
 *   POST /api/chat  — 对应 OpenAI Chat Completions API
 *   支持 tools 参数实现 Tool Calling（需要模型支持）
 */
public class OllamaClient {

    private static final String DEFAULT_BASE_URL = "http://localhost:11434";

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
     * 发送 Chat 请求 —— 核心方法
     *
     * 对应 pi-mono 中 Model.processRequest()
     * 对应 Spring AI 中 ChatModel.call(Prompt)
     *
     * @param model    模型名称，如 "deepseek-r1:8b"
     * @param messages 消息列表
     * @param tools    工具定义（JSON 字符串）
     * @return Ollama 响应（原始 JSON 字符串）
     */
    public String chat(String model, List<Map<String, Object>> messages, String toolsJson) {
        StringBuilder body = new StringBuilder();
        body.append("{\"model\":\"").append(model).append("\"");
        body.append(",\"messages\":").append(toJsonArray(messages));
        body.append(",\"stream\":false");
        if (toolsJson != null && !toolsJson.isEmpty()) {
            body.append(",\"tools\":").append(toolsJson);
        }
        body.append("}");

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/chat"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
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

    // ---- 简易 JSON 构建 ----

    private String toJsonArray(List<Map<String, Object>> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(toJsonObject(list.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }

    private String toJsonObject(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(entry.getKey()).append("\":");
            sb.append(valueToJson(entry.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String valueToJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "\"" + escapeJson((String) value) + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map) return toJsonObject((Map<String, Object>) value);
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(valueToJson(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escapeJson(value.toString()) + "\"";
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
