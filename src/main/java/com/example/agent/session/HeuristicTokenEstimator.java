package com.example.agent.session;

/**
 * HeuristicTokenEstimator —— chars/4 ceiling estimate, dependency-free.
 *
 * English text averages ~4 chars per token; Chinese runs heavier per char,
 * so this under-estimates CJK — conservative trimming budgets should account
 * for that or plug in a real tokenizer.
 */
public final class HeuristicTokenEstimator implements TokenEstimator {

    @Override
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (text.length() + 3) / 4;
    }
}
