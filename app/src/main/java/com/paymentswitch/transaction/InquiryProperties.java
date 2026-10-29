package com.paymentswitch.transaction;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Inquiry schedule for UNKNOWN transactions (ADR-005).
 *
 * @param pollInterval pause between polls for due inquiries
 * @param batchSize    inquiries claimed per poll
 * @param initialDelay first inquiry after the transaction became UNKNOWN; doubled per attempt
 * @param maxDelay     upper bound for the delay between inquiries
 * @param maxWindow    how long to keep asking before escalating to MANUAL_REVIEW
 * @param verifyAfter  when to re-check a transaction that was failed after being UNKNOWN, to catch a
 *                     late supplier success
 */
@ConfigurationProperties("payment-switch.inquiry")
record InquiryProperties(
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("50") int batchSize,
        @DefaultValue("30s") Duration initialDelay,
        @DefaultValue("30m") Duration maxDelay,
        @DefaultValue("24h") Duration maxWindow,
        @DefaultValue("1h") Duration verifyAfter) {
}
