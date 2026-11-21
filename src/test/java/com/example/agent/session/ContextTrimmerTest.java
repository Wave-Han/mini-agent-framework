package com.example.agent.session;

import com.example.agent.spec.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ContextTrimmerTest {

    private final AtomicInteger ids = new AtomicInteger();
    private final HeuristicTokenEstimator estimator = new HeuristicTokenEstimator();

    private AgentSession.SessionEntry msg(AgentSession.MessageRole role, String text) {
        return new AgentSession.SessionEntry("e" + ids.incrementAndGet(), null, "", "message",
            new AgentSession.SessionMessage(role, text));
    }

    private AgentSession.SessionEntry toolResult(String text) {
        return new AgentSession.SessionEntry("e" + ids.incrementAndGet(), null, "", "tool_result",
            new AgentSession.ToolResultMessage("t", ToolResult.success(text)));
    }

    /** Entry cost under the plan's contract: estimate(String.valueOf(entry.data())). */
    private int costOf(AgentSession.SessionEntry entry) {
        return estimator.estimate(String.valueOf(entry.data()));
    }

    @Test
    void largeBudget_identicalToFullPath_noMarker() {
        var path = List.of(msg(AgentSession.MessageRole.SYSTEM, "sys"),
            msg(AgentSession.MessageRole.USER, "hello"),
            msg(AgentSession.MessageRole.USER, "world"),
            msg(AgentSession.MessageRole.USER, "again"));
        var out = ContextTrimmer.trim(path, 10_000, estimator);
        assertThat(out).isEqualTo(path); // same entries, same order, nothing trimmed
    }

    @Test
    void tightBudget_keepsSystemAndNewest_marksDroppedMiddle() {
        String fill = "a".repeat(400); // far over any budget here
        var system = msg(AgentSession.MessageRole.SYSTEM, "sys");
        var dropped1 = msg(AgentSession.MessageRole.USER, fill);
        var dropped2 = msg(AgentSession.MessageRole.USER, fill);
        var latest = msg(AgentSession.MessageRole.USER, "latest");
        int budget = costOf(system) + costOf(latest); // fits exactly system + newest

        var out = ContextTrimmer.trim(List.of(system, dropped1, dropped2, latest), budget, estimator);

        assertThat(out).hasSize(3);
        assertThat(out.get(0)).isEqualTo(system);
        assertThat(out.get(1).type()).isEqualTo("trim_marker");
        assertThat(out.get(1).data()).isEqualTo("[... 2 entries trimmed ...]");
        assertThat(out.get(2)).isEqualTo(latest);
    }

    @Test
    void toolResultNeverOrphaned_atBoundary() {
        var system = msg(AgentSession.MessageRole.SYSTEM, "s");
        var assistant = msg(AgentSession.MessageRole.ASSISTANT, "call tool X");
        var result = toolResult("output");
        var latest = msg(AgentSession.MessageRole.USER, "latest");

        // everything fits -> no marker, pair intact
        int fullBudget = costOf(system) + costOf(assistant) + costOf(result) + costOf(latest);
        var full = ContextTrimmer.trim(List.of(system, assistant, result, latest), fullBudget, estimator);
        assertThat(full).isEqualTo(List.of(system, assistant, result, latest));

        // budget covers system + latest only -> pair does not fit and BOTH drop,
        // never the tool_result alone (orphaned tool_result would break the API)
        int tightBudget = costOf(system) + costOf(latest);
        var trimmed = ContextTrimmer.trim(List.of(system, assistant, result, latest), tightBudget, estimator);
        assertThat(trimmed).hasSize(3);
        assertThat(trimmed.get(1).type()).isEqualTo("trim_marker");
        assertThat(trimmed.get(1).data()).isEqualTo("[... 2 entries trimmed ...]");
        assertThat(trimmed.get(2)).isEqualTo(latest);
    }

    @Test
    void newestAlwaysPresent_evenWhenOverBudget_noMarkerWhenNothingDropped() {
        var system = msg(AgentSession.MessageRole.SYSTEM, "s");
        var huge = msg(AgentSession.MessageRole.USER, "h".repeat(4_000)); // 1000 tokens alone

        var out = ContextTrimmer.trim(List.of(system, huge), 10, estimator);

        assertThat(out).hasSize(2); // system + newest, over budget but never empty
        assertThat(out).containsExactly(system, huge);
        assertThat(out).noneMatch(e -> "trim_marker".equals(e.type())); // nothing else was dropped
    }

    @Test
    void markerNotPersistedToTree_viaAgentSessionOverload() {
        var session = new AgentSession("s1", "/tmp");
        session.appendMessage(AgentSession.MessageRole.SYSTEM, "sys");
        session.appendMessage(AgentSession.MessageRole.USER, "a".repeat(400));
        session.appendMessage(AgentSession.MessageRole.USER, "latest");
        int entriesBefore = session.getEntries().size();
        String leafBefore = session.getLeafId();

        var trimmed = session.buildContext(4, estimator); // fits sys(1)+latest(2) only

        assertThat(trimmed).hasSize(3);
        assertThat(trimmed.get(1).type()).isEqualTo("trim_marker");
        assertThat(session.getEntries()).hasSize(entriesBefore); // marker never entered the tree
        assertThat(session.getLeafId()).isEqualTo(leafBefore);
    }
}
