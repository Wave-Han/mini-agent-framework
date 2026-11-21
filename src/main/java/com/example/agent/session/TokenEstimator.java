package com.example.agent.session;

/**
 * TokenEstimator —— pluggable token estimation for context trimming.
 *
 * The default heuristic (chars/4) needs no dependency; swap in a real
 * tokenizer (e.g. jtokkit) by supplying another implementation to
 * AgentSession.buildContext(maxTokens, estimator).
 */
public interface TokenEstimator {

    /** Estimated token count of the text; 0 for null/empty. */
    int estimate(String text);
}
