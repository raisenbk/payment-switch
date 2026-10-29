package com.paymentswitch.transaction;

import static com.paymentswitch.transaction.TransactionStatus.FAILED;
import static com.paymentswitch.transaction.TransactionStatus.MANUAL_REVIEW;
import static com.paymentswitch.transaction.TransactionStatus.PROCESSING;
import static com.paymentswitch.transaction.TransactionStatus.SUCCESS;
import static com.paymentswitch.transaction.TransactionStatus.UNKNOWN;

import com.paymentswitch.common.money.Money;
import com.paymentswitch.ledger.LedgerService;
import com.paymentswitch.supplier.SupplierResult;
import com.paymentswitch.supplier.SupplierResult.Failed;
import com.paymentswitch.supplier.SupplierResult.Success;
import com.paymentswitch.supplier.SupplierResult.Unknown;
import com.paymentswitch.transaction.TransactionRepository.StatusChange;
import com.paymentswitch.transaction.TransactionRepository.TransactionRow;
import com.paymentswitch.transaction.TransactionStateMachine.ConcurrentTransitionException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies a supplier answer to a transaction, each in its own database transaction:
 * <ul>
 *   <li>SUCCESS captures the hold, FAILED releases it;</li>
 *   <li>UNKNOWN keeps the hold and starts (or continues) the inquiry schedule, escalating to
 *       MANUAL_REVIEW once the max window has passed;</li>
 *   <li>a definitive answer that contradicts a final status we already set is recorded as a
 *       discrepancy. It is never applied automatically and never silently dropped.</li>
 * </ul>
 */
@Component
@EnableConfigurationProperties(InquiryProperties.class)
class SupplierOutcomes {

    private static final Logger log = LoggerFactory.getLogger(SupplierOutcomes.class);

    static final String LATE_SUCCESS = "LATE_SUCCESS";
    static final String LATE_FAILURE = "LATE_FAILURE";

    private final TransactionRepository repository;
    private final TransactionStateMachine stateMachine;
    private final LedgerService ledger;
    private final InquiryProperties inquiry;
    private final TransactionTemplate transaction;

    SupplierOutcomes(TransactionRepository repository, TransactionStateMachine stateMachine, LedgerService ledger,
            InquiryProperties inquiry, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.stateMachine = stateMachine;
        this.ledger = ledger;
        this.inquiry = inquiry;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** The answer to a submit, for a transaction that is PROCESSING. */
    void applySubmitResult(UUID id, String supplierCode, SupplierResult result, boolean resubmitted) {
        apply(id, PROCESSING, supplierCode, result, false, resubmitted ? "resubmit" : "submit");
    }

    /** The answer to an inquiry, for a transaction that is UNKNOWN. */
    void applyInquiryResult(UUID id, String supplierCode, SupplierResult result, boolean deadlinePassed) {
        apply(id, UNKNOWN, supplierCode, result, deadlinePassed, "inquiry");
    }

    /**
     * The answer to the verification inquiry for a transaction we failed after it was UNKNOWN
     * (e.g. the supplier answered 404 while it was in fact still processing).
     */
    void applyVerificationResult(UUID id, String supplierCode, SupplierResult result, boolean deadlinePassed) {
        transaction.executeWithoutResult(status -> {
            switch (result) {
                case Success success -> {
                    flag(id, LATE_SUCCESS, FAILED, success.supplierRef(), supplierCode);
                    repository.clearInquiry(id);
                }
                case Failed failed -> repository.clearInquiry(id);
                case Unknown unknown -> {
                    if (deadlinePassed) {
                        log.warn("Verification of failed transaction {} stays inconclusive ({}); giving up",
                                id, unknown.reason());
                        repository.clearInquiry(id);
                    }
                }
            }
        });
    }

    private void apply(UUID id, TransactionStatus from, String supplierCode, SupplierResult result,
            boolean deadlinePassed, String via) {
        try {
            transaction.executeWithoutResult(status -> {
                switch (result) {
                    case Success success -> succeed(id, from, supplierCode, success, via);
                    case Failed failed -> fail(id, from, supplierCode, failed, via);
                    case Unknown unknown -> stayUnknown(id, from, supplierCode, unknown, deadlinePassed);
                }
            });
        } catch (ConcurrentTransitionException e) {
            // Someone else settled the transaction first. Fine, unless their outcome contradicts this answer.
            transaction.executeWithoutResult(status -> flagIfContradicting(id, supplierCode, result));
        }
    }

    private void succeed(UUID id, TransactionStatus from, String supplierCode, Success success, String via) {
        TransactionRow row = repository.findById(id).orElseThrow();
        stateMachine.transition(id, from, SUCCESS, StatusChange.succeeded(success.supplierRef()),
                Map.of("supplier", supplierCode, "via", via));
        ledger.capture(id, row.supplierId(), Money.of(row.supplierCost(), row.currency()));
        repository.clearInquiry(id);
    }

    private void fail(UUID id, TransactionStatus from, String supplierCode, Failed failed, String via) {
        stateMachine.transition(id, from, FAILED, StatusChange.failed(failed.code()),
                Map.of("supplier", supplierCode, "code", failed.code(), "via", via));
        ledger.release(id);
        if (from == UNKNOWN) {
            // We were unsure once; check again later in case the supplier did top up after all.
            repository.scheduleInquiry(id, inquiry.verifyAfter(), inquiry.verifyAfter().plus(inquiry.maxWindow()));
        }
    }

    private void stayUnknown(UUID id, TransactionStatus from, String supplierCode, Unknown unknown,
            boolean deadlinePassed) {
        if (from == PROCESSING) {
            // Timeout != failure: keep the hold until an inquiry gives a definitive answer (ADR-005).
            stateMachine.transition(id, PROCESSING, UNKNOWN, StatusChange.NONE,
                    Map.of("supplier", supplierCode, "reason", unknown.reason()));
            repository.scheduleInquiry(id, inquiry.initialDelay(), inquiry.maxWindow());
        } else if (deadlinePassed) {
            stateMachine.transition(id, UNKNOWN, MANUAL_REVIEW, StatusChange.NONE,
                    Map.of("supplier", supplierCode, "reason", unknown.reason()));
            repository.clearInquiry(id);
            log.warn("Transaction {} still unknown after {}; escalated to MANUAL_REVIEW, hold kept",
                    id, inquiry.maxWindow());
        }
        // Otherwise the next inquiry is already scheduled (see TransactionRepository#claimDueInquiries).
    }

    private void flagIfContradicting(UUID id, String supplierCode, SupplierResult result) {
        TransactionStatus current = repository.findById(id).orElseThrow().status();
        if (result instanceof Success success && current == FAILED) {
            flag(id, LATE_SUCCESS, current, success.supplierRef(), supplierCode);
        } else if (result instanceof Failed && current == SUCCESS) {
            flag(id, LATE_FAILURE, current, null, supplierCode);
        } else {
            log.info("Transaction {} already {}; supplier answer {} needs no action", id, current, result);
        }
    }

    private void flag(UUID id, String kind, TransactionStatus ourStatus, String supplierRef, String supplierCode) {
        if (repository.insertDiscrepancy(id, kind, ourStatus, supplierRef, supplierCode)) {
            log.warn("DISCREPANCY {}: supplier {} contradicts our final status {} for transaction {} (supplierRef {})",
                    kind, supplierCode, ourStatus, id, supplierRef);
        }
    }
}
