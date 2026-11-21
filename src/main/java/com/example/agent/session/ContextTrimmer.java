package com.example.agent.session;

import java.util.ArrayList;
import java.util.List;

/**
 * ContextTrimmer —— head+tail token-window policy for session context.
 *
 * Policy (see docs/superpowers/specs/2026-11-21-streaming-and-token-trimming-design.md):
 *   - Leading system messages are always kept; their cost always counts.
 *   - Remaining entries are grouped into blocks; a tool_result entry is atomic
 *     with its immediately preceding entry (an orphaned tool_result would make
 *     the API reject the request).
 *   - Blocks are admitted newest-first while the running total stays within
 *     maxTokens; the first block that overflows stops selection.
 *   - The newest entry is always present, even when its block alone exceeds
 *     the budget — the result is never empty.
 *   - Dropped middle entries are summarized by one trim_marker placeholder,
 *     which is NOT counted against the budget and never enters the tree.
 */
public final class ContextTrimmer {

    private ContextTrimmer() {
    }

    /** Trims a root→leaf path to fit maxTokens under the head+tail policy. */
    public static List<AgentSession.SessionEntry> trim(List<AgentSession.SessionEntry> path,
                                                       int maxTokens,
                                                       TokenEstimator estimator) {
        if (path.isEmpty()) {
            return List.of();
        }

        int systemEnd = 0;
        while (systemEnd < path.size() && isSystem(path.get(systemEnd))) {
            systemEnd++;
        }
        List<AgentSession.SessionEntry> systemPrefix = path.subList(0, systemEnd);
        int used = cost(systemPrefix, estimator);

        // Partition the rest into blocks; tool_result attaches to its predecessor
        List<List<AgentSession.SessionEntry>> blocks = new ArrayList<>();
        for (int i = systemEnd; i < path.size(); i++) {
            AgentSession.SessionEntry entry = path.get(i);
            if ("tool_result".equals(entry.type()) && !blocks.isEmpty()) {
                blocks.get(blocks.size() - 1).add(entry);
            } else {
                blocks.add(new ArrayList<>(List.of(entry)));
            }
        }

        // Admit blocks newest-first; the newest block is always admitted
        List<List<AgentSession.SessionEntry>> selected = new ArrayList<>();
        int droppedEntries = 0;
        for (int b = blocks.size() - 1; b >= 0; b--) {
            List<AgentSession.SessionEntry> block = blocks.get(b);
            int blockCost = cost(block, estimator);
            boolean newest = selected.isEmpty() && b == blocks.size() - 1;
            if (newest || used + blockCost <= maxTokens) {
                selected.add(block);
                used += blockCost;
            } else {
                // first overflow stops selection — every older block is dropped too
                for (int older = b; older >= 0; older--) {
                    droppedEntries += blocks.get(older).size();
                }
                break;
            }
        }

        List<AgentSession.SessionEntry> out = new ArrayList<>(systemPrefix);
        if (droppedEntries > 0) {
            out.add(new AgentSession.SessionEntry(
                "trim", null, "", "trim_marker", "[... " + droppedEntries + " entries trimmed ...]"));
        }
        for (int i = selected.size() - 1; i >= 0; i--) {
            out.addAll(selected.get(i));
        }
        return List.copyOf(out);
    }

    private static boolean isSystem(AgentSession.SessionEntry entry) {
        return "message".equals(entry.type())
            && entry.data() instanceof AgentSession.SessionMessage m
            && m.role() == AgentSession.MessageRole.SYSTEM;
    }

    private static int cost(List<AgentSession.SessionEntry> entries, TokenEstimator estimator) {
        int total = 0;
        for (AgentSession.SessionEntry entry : entries) {
            total += estimator.estimate(String.valueOf(entry.data()));
        }
        return total;
    }
}
