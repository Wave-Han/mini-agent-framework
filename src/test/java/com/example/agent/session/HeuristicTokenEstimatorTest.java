package com.example.agent.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicTokenEstimatorTest {

    @Test
    void heuristic_isCeilOfCharsOverFour() {
        var e = new HeuristicTokenEstimator();
        assertThat(e.estimate(null)).isZero();
        assertThat(e.estimate("")).isZero();
        assertThat(e.estimate("abcd")).isEqualTo(1);   // 4 chars
        assertThat(e.estimate("abcde")).isEqualTo(2);  // 5 chars -> ceil
    }
}
