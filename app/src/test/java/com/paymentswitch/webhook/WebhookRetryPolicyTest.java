package com.paymentswitch.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.stream.IntStream;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class WebhookRetryPolicyTest {

    private final WebhookRetryPolicy policy =
            new WebhookRetryPolicy(Duration.ofSeconds(30), Duration.ofHours(6), 10);

    @RepeatedTest(20)
    void delayDoublesPerAttemptWithJitterInTheUpperHalf() {
        assertThat(policy.delayAfter(1)).isBetween(Duration.ofSeconds(15), Duration.ofSeconds(30));
        assertThat(policy.delayAfter(2)).isBetween(Duration.ofSeconds(30), Duration.ofSeconds(60));
        assertThat(policy.delayAfter(5)).isBetween(Duration.ofSeconds(240), Duration.ofSeconds(480));
    }

    @Test
    void delayIsCappedAndNeverOverflows() {
        assertThat(policy.delayAfter(30)).isBetween(Duration.ofHours(3), Duration.ofHours(6));
        assertThat(policy.delayAfter(Integer.MAX_VALUE)).isBetween(Duration.ofHours(3), Duration.ofHours(6));
    }

    @Test
    void jitterSpreadsRetriesOfTheSameAttempt() {
        long distinct = IntStream.range(0, 50).mapToObj(i -> policy.delayAfter(3)).distinct().count();
        assertThat(distinct).isGreaterThan(1);
    }

    @Test
    void givesUpAfterMaxAttempts() {
        assertThat(policy.exhausted(9)).isFalse();
        assertThat(policy.exhausted(10)).isTrue();
    }
}
