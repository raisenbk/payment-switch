package com.paymentswitch.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import com.paymentswitch.ledger.Balance;
import com.paymentswitch.supplier.SupplierResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Ambiguous supplier results (ADR-005) against the real mock-supplier: the transaction becomes
 * UNKNOWN with the hold kept, then the inquiry scheduler resolves it, escalates it to
 * MANUAL_REVIEW after the max window, or flags a supplier answer that contradicts our final status.
 */
class UnknownResolutionIntegrationTest extends AbstractTransactionIntegrationTest {

    @Autowired
    SupplierOutcomes outcomes;

    @ParameterizedTest(name = "destination {0}: UNKNOWN ({1}) -> inquiry -> SUCCESS")
    @CsvSource({
            "081234560022, READ_TIMEOUT",
            "081234560044, SUPPLIER_PENDING",
            "081234560055, SUPPLIER_HTTP_500"})
    void inquiryConfirmsSuccessAndCapturesTheHold(String destination, String reason) {
        UUID id = idOf(create(destination));

        String done = awaitStatus(id, "SUCCESS");

        assertThat(JsonPath.<String>read(done, "$.supplierRef")).startsWith("MS-");
        assertThat(transitions(id)).containsExactly(
                "->PENDING", "PENDING->PROCESSING", "PROCESSING->UNKNOWN", "UNKNOWN->SUCCESS");
        assertThat(eventPayload(id, "UNKNOWN")).contains("\"reason\": \"" + reason + "\"");
        assertThat(eventPayload(id, "SUCCESS")).contains("\"via\": \"inquiry\"");
        assertThat(balance()).isEqualTo(new Balance(idr(STARTING_BALANCE - PRICE), idr(0)));
        assertThat(ledgerEntries(id, "CAPTURE")).hasSize(3);
        assertThat(inquiryScheduled(id)).isFalse();
    }

    @Test
    void inquiryConfirmsFailureAndReleasesTheHold() {
        UUID id = idOf(create("081234560033"));

        String done = awaitStatus(id, "FAILED");

        assertThat(JsonPath.<String>read(done, "$.failureCode")).isEqualTo("INVALID_DESTINATION");
        assertThat(transitions(id)).containsExactly(
                "->PENDING", "PENDING->PROCESSING", "PROCESSING->UNKNOWN", "UNKNOWN->FAILED");
        assertThat(balance()).isEqualTo(new Balance(idr(STARTING_BALANCE), idr(0)));
    }

    @Test
    void unresolvedBeyondTheMaxWindowGoesToManualReviewWithTheHoldKept() {
        UUID id = idOf(create("081234560077"));

        awaitStatus(id, "MANUAL_REVIEW");

        assertThat(transitions(id)).containsExactly(
                "->PENDING", "PENDING->PROCESSING", "PROCESSING->UNKNOWN", "UNKNOWN->MANUAL_REVIEW");
        assertThat(eventPayload(id, "MANUAL_REVIEW")).contains("\"reason\": \"SUPPLIER_PENDING\"");
        // Never refunded on a guess: the money stays held until someone knows the outcome.
        assertThat(balance()).isEqualTo(new Balance(idr(STARTING_BALANCE - PRICE), idr(PRICE)));
        // Backoff: test config is 300ms doubling to 1s within a 6s window, so a handful of attempts.
        assertThat(inquiryAttempts(id)).isBetween(4, 10);
        assertThat(inquiryScheduled(id)).isFalse();
    }

    @Test
    void lateSuccessAfterWeFailedIsFlaggedNotIgnored() {
        UUID id = idOf(create("081234560088"));

        // The supplier answers 404 while still processing, so the inquiry fails the transaction...
        String failed = awaitStatus(id, "FAILED");
        assertThat(JsonPath.<String>read(failed, "$.failureCode")).isEqualTo("NOT_RECEIVED_BY_SUPPLIER");

        // ...and the verification inquiry later finds the supplier did top up the customer.
        await().atMost(MAX_WAIT).until(() -> discrepancies(id).contains("LATE_SUCCESS FAILED MOCKA"));
        assertThat(supplierRefOfDiscrepancy(id)).startsWith("MS-");
        // Flagged for an operator; nothing is changed automatically.
        assertThat(transitions(id)).last().isEqualTo("UNKNOWN->FAILED");
        assertThat(balance()).isEqualTo(new Balance(idr(STARTING_BALANCE), idr(0)));
        assertThat(inquiryScheduled(id)).isFalse();
    }

    @Test
    void contradictingAnswerForAFinalTransactionIsFlagged() {
        UUID failed = idOf(create("081234560011"));
        UUID succeeded = idOf(create("081234560000"));
        awaitStatus(failed, "FAILED");
        awaitStatus(succeeded, "SUCCESS");

        outcomes.applySubmitResult(failed, "MOCKA", new SupplierResult.Success("MS-LATE0001"), false);
        outcomes.applySubmitResult(succeeded, "MOCKA", new SupplierResult.Failed("INVALID_DESTINATION"), false);
        outcomes.applySubmitResult(succeeded, "MOCKA", new SupplierResult.Success("MS-SAME0001"), false);

        assertThat(discrepancies(failed)).containsExactly("LATE_SUCCESS FAILED MOCKA");
        assertThat(discrepancies(succeeded)).containsExactly("LATE_FAILURE SUCCESS MOCKA");
        assertThat(transitions(failed)).hasSize(3);
        assertThat(transitions(succeeded)).hasSize(3);
    }

    private List<String> discrepancies(UUID id) {
        return jdbc.sql("""
                        SELECT kind || ' ' || our_status || ' ' || supplier_code
                          FROM transaction_discrepancies WHERE transaction_id = :id ORDER BY id""")
                .param("id", id)
                .query(String.class)
                .list();
    }

    private String supplierRefOfDiscrepancy(UUID id) {
        return jdbc.sql("SELECT supplier_ref FROM transaction_discrepancies WHERE transaction_id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private int inquiryAttempts(UUID id) {
        return jdbc.sql("SELECT inquiry_attempts FROM transactions WHERE id = :id")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    private boolean inquiryScheduled(UUID id) {
        return jdbc.sql("SELECT next_inquiry_at IS NOT NULL FROM transactions WHERE id = :id")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }
}
