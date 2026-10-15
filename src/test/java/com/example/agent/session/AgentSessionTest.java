package com.example.agent.session;

import com.example.agent.session.AgentSession.MessageRole;
import com.example.agent.session.AgentSession.SessionEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentSessionTest —— pins the tree-shaped append-only memory design.
 *
 * Key behaviors under test:
 *   1. branch() rewinds the leaf; buildContext() returns ONLY the new path
 *   2. the old branch is never lost (append-only)
 *   3. branching to an unknown entry is rejected
 */
class AgentSessionTest {

    @Test
    @DisplayName("buildContext walks the full linear path before branching")
    void linearContextBeforeBranch() {
        AgentSession session = new AgentSession("s1", "/tmp");
        session.appendMessage(MessageRole.USER, "你好");
        session.appendMessage(MessageRole.ASSISTANT, "需要帮忙吗");
        session.appendMessage(MessageRole.USER, "算 1+2");

        List<SessionEntry> ctx = session.buildContext();

        assertEquals(3, ctx.size(), "no branching: context is the whole history");
    }

    @Test
    @DisplayName("branch rewinds the leaf: context follows the NEW path only")
    void branchBuildsContextFromNewPathOnly() {
        AgentSession session = new AgentSession("s1", "/tmp");
        String u1 = session.appendMessage(MessageRole.USER, "你好");
        session.appendMessage(MessageRole.ASSISTANT, "旧回答A");
        // rewind to u1 and diverge
        session.branch(u1);
        session.appendMessage(MessageRole.ASSISTANT, "新回答B");

        List<SessionEntry> ctx = session.buildContext();

        assertEquals(2, ctx.size(), "context = root..branch-point + new messages");
        assertEquals("你好", ((AgentSession.SessionMessage) ctx.get(0).data()).content());
        assertEquals("新回答B", ((AgentSession.SessionMessage) ctx.get(1).data()).content());
    }

    @Test
    @DisplayName("old branch stays in the tree after rewind (append-only)")
    void oldBranchIsNeverLost() {
        AgentSession session = new AgentSession("s1", "/tmp");
        String u1 = session.appendMessage(MessageRole.USER, "你好");
        session.appendMessage(MessageRole.ASSISTANT, "旧回答A");
        session.branch(u1);
        session.appendMessage(MessageRole.ASSISTANT, "新回答B");

        assertEquals(3, session.getEntries().size(),
            "append-only: the abandoned branch is still stored");
    }

    @Test
    @DisplayName("branching to an unknown entry is rejected")
    void branchToUnknownEntryThrows() {
        AgentSession session = new AgentSession("s1", "/tmp");
        session.appendMessage(MessageRole.USER, "你好");

        assertThrows(IllegalArgumentException.class, () -> session.branch("no-such-id"));
    }

    @Test
    @DisplayName("empty session yields empty context")
    void emptySessionHasEmptyContext() {
        AgentSession session = new AgentSession("s1", "/tmp");

        assertTrue(session.buildContext().isEmpty());
    }
}
