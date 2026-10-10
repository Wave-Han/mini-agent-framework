package com.example.agent.session;

import com.example.agent.spec.AgentTool;
import com.example.agent.spec.ToolResult;

import java.time.Instant;
import java.util.*;

/**
 * AgentSession —— 会话管理器
 *
 * 对应 pi-mono packages/coding-agent/src/core/session-manager.ts SessionManager
 * 对应 Spring AI ChatMemory + MessageChatMemoryAdvisor
 * 对应 LangChain4j ChatMemory + ChatMemoryProvider
 *
 * 核心设计：
 *   - Append-Only 不可变消息列表
 *   - 每条消息有唯一 ID 和 parentId 形成树结构
 *   - 支持 branching（回到历史节点重新开始）
 */
public class AgentSession {

    private final String sessionId;
    private final String cwd;
    private final Instant createdAt;
    private final List<SessionEntry> entries;
    private String leafId;

    public AgentSession(String sessionId, String cwd) {
        this.sessionId = sessionId;
        this.cwd = cwd;
        this.createdAt = Instant.now();
        this.entries = new ArrayList<>();
        this.leafId = null;
    }

    /**
     * 追加消息 —— 对应 pi-mono SessionManager.appendMessage()
     * Java 对比：类似 HttpSession.setAttribute() 追加状态
     */
    public String appendMessage(MessageRole role, Object content) {
        String id = generateId();
        SessionEntry entry = new SessionEntry(
            id, leafId, Instant.now().toString(), "message",
            new SessionMessage(role, content)
        );
        entries.add(entry);
        leafId = id;
        return id;
    }

    /**
     * 追加工具结果 —— 对应 pi-mono 中的 toolResult 消息
     */
    public String appendToolResult(String toolName, ToolResult result) {
        String id = generateId();
        SessionEntry entry = new SessionEntry(
            id, leafId, Instant.now().toString(), "tool_result",
            new ToolResultMessage(toolName, result)
        );
        entries.add(entry);
        leafId = id;
        return id;
    }

    /**
     * 分支 —— 对应 pi-mono SessionManager.branch()
     * 将 leafId 移到历史节点，下次追加会创建新分支
     * Java 对比：类似 Git checkout 到旧 commit
     */
    public void branch(String fromEntryId) {
        if (entries.stream().noneMatch(e -> e.id().equals(fromEntryId))) {
            throw new IllegalArgumentException("Entry not found: " + fromEntryId);
        }
        this.leafId = fromEntryId;
    }

    /**
     * 构建上下文 —— 对应 pi-mono buildSessionContext()
     * 从 leaf 沿 parentId 回溯到 root，收集路径上的所有消息
     */
    public List<SessionEntry> buildContext() {
        if (leafId == null) return List.of();

        Map<String, SessionEntry> byId = new HashMap<>();
        for (SessionEntry e : entries) {
            byId.put(e.id(), e);
        }

        List<SessionEntry> path = new ArrayList<>();
        SessionEntry current = byId.get(leafId);
        while (current != null) {
            path.add(0, current);
            current = current.parentId() != null ? byId.get(current.parentId()) : null;
        }
        return Collections.unmodifiableList(path);
    }

    public String getSessionId() { return sessionId; }
    public String getCwd() { return cwd; }
    public String getLeafId() { return leafId; }
    public List<SessionEntry> getEntries() { return Collections.unmodifiableList(entries); }

    private String generateId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // ---- 值对象 ----

    public record SessionEntry(String id, String parentId, String timestamp, String type, Object data) {}
    public record SessionMessage(MessageRole role, Object content) {}
    public record ToolResultMessage(String toolName, ToolResult result) {}

    public enum MessageRole { USER, ASSISTANT, TOOL_RESULT, SYSTEM }
}
