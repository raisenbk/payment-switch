package com.paymentswitch.common.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param pollInterval      pause between polls
 * @param batchSize         events claimed per poll
 * @param lease             how long a claimed event is hidden from other pollers; must exceed the
 *                          slowest handler (a supplier call), or the event is handled twice
 * @param retryInitialDelay first retry delay after a handler failure, doubled per attempt
 * @param retryMaxDelay     upper bound for the retry delay
 */
@ConfigurationProperties("payment-switch.outbox")
public record OutboxProperties(
        @DefaultValue("250ms") Duration pollInterval,
        @DefaultValue("50") int batchSize,
        @DefaultValue("60s") Duration lease,
        @DefaultValue("1s") Duration retryInitialDelay,
        @DefaultValue("5m") Duration retryMaxDelay) {
}
