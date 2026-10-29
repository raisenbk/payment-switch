package com.paymentswitch.transaction;

import static com.paymentswitch.transaction.TransactionStatus.UNKNOWN;

import com.paymentswitch.supplier.SupplierGateways;
import com.paymentswitch.supplier.SupplierResult;
import com.paymentswitch.transaction.TransactionRepository.DueInquiry;
import com.paymentswitch.common.tracing.TraceContexts;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Asks suppliers about transactions whose outcome we do not know yet, with exponential backoff,
 * until they answer definitively or the max window passes (then MANUAL_REVIEW). Also runs the one
 * verification inquiry for transactions failed after being UNKNOWN. Safe on several instances: a
 * claimed inquiry is not handed out again before its next scheduled time.
 */
@Component
class InquiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(InquiryScheduler.class);

    private final TransactionRepository repository;
    private final SupplierGateways gateways;
    private final SupplierOutcomes outcomes;
    private final InquiryProperties properties;
    private final TraceContexts traces;
    private final MeterRegistry meters;

    InquiryScheduler(TransactionRepository repository, SupplierGateways gateways, SupplierOutcomes outcomes,
            InquiryProperties properties, TraceContexts traces, MeterRegistry meters) {
        this.repository = repository;
        this.gateways = gateways;
        this.outcomes = outcomes;
        this.properties = properties;
        this.traces = traces;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${payment-switch.inquiry.poll-interval:1s}")
    void poll() {
        List<DueInquiry> due = repository.claimDueInquiries(properties.batchSize(), properties.initialDelay(),
                properties.maxDelay());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            due.forEach(inquiry -> executor.execute(() -> traces.continueTrace(null, "inquiry", null,
                    Map.of("transaction.id", inquiry.id().toString(), "inquiry.attempt",
                            Integer.toString(inquiry.attempts())),
                    () -> inquire(inquiry))));
        }
    }

    private void inquire(DueInquiry due) {
        MDC.put("transactionId", due.id().toString());
        MDC.put("merchantId", due.merchantId().toString());
        try {
            SupplierResult result = gateways.forSupplier(due.supplierCode())
                    .map(gateway -> gateway.inquiry(due.id().toString()))
                    .orElseGet(() -> new SupplierResult.Unknown("SUPPLIER_NOT_CONFIGURED"));
            log.info("Inquiry {} for {} transaction: {}", due.attempts(), due.status(), result);
            meters.counter("paymentswitch.inquiries", "kind", due.status() == UNKNOWN ? "resolve" : "verify",
                    "result", result.getClass().getSimpleName().toLowerCase()).increment();
            if (due.status() == UNKNOWN) {
                outcomes.applyInquiryResult(due.id(), due.supplierCode(), result, due.deadlinePassed());
            } else {
                outcomes.applyVerificationResult(due.id(), due.supplierCode(), result, due.deadlinePassed());
            }
        } catch (RuntimeException e) {
            // The next attempt is already scheduled; it will try again.
            log.error("Inquiry for transaction {} failed", due.id(), e);
        } finally {
            MDC.remove("transactionId");
            MDC.remove("merchantId");
        }
    }
}
