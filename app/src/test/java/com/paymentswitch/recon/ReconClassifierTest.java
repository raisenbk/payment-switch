package com.paymentswitch.recon;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentswitch.recon.ReconClassifier.LineToMatch;
import com.paymentswitch.recon.ReconClassifier.Result;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Reconciliation decision table for one settlement line. File supplier id is 7. */
class ReconClassifierTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "null", value = {
            // rule,                                       occurrence, ours?, status,   supplier, our cost, our ccy, billed, ccy,  expected,         diff
            "matching success,                               1, true,  SUCCESS,  7, 10000, IDR, 10000, IDR, MATCHED,          0",
            "billed more than our booked cost,               1, true,  SUCCESS,  7, 10000, IDR, 10100, IDR, AMOUNT_DIFF,      100",
            "billed less than our booked cost,               1, true,  SUCCESS,  7, 10000, IDR,  9900, IDR, AMOUNT_DIFF,      -100",
            "billed in another currency,                     1, true,  SUCCESS,  7, 10000, IDR, 10000, USD, AMOUNT_DIFF,      null",
            "reference we never created,                     1, false, null,  null,  null, null, 10000, IDR, MISSING_INTERNAL, null",
            "we failed it (late success at the supplier),    1, true,  FAILED,   7, 10000, IDR, 10000, IDR, MISSING_INTERNAL, 0",
            "still unknown on our side,                      1, true,  UNKNOWN,  7, 10000, IDR, 10000, IDR, MISSING_INTERNAL, 0",
            "we succeeded it at another supplier,            1, true,  SUCCESS,  9, 10000, IDR, 10000, IDR, MISSING_INTERNAL, 0",
            "second line with the same reference,            2, true,  SUCCESS,  7, 10000, IDR, 10000, IDR, DUPLICATE,        0",
            "duplicate of a reference we never created,      2, false, null,  null,  null, null, 10000, IDR, DUPLICATE,        null"})
    void classifies(String rule, int occurrence, boolean ours, String status, Long supplierId, Long ourCost,
            String ourCurrency, long billed, String currency, MatchStatus expected, Long diff) {
        LineToMatch line = new LineToMatch(3, "ref", "MS-1", billed, currency, occurrence,
                ours ? UUID.randomUUID() : null, status, supplierId, ourCost, ourCurrency);

        Result result = ReconClassifier.classify(line, 7);

        assertThat(result.status()).isEqualTo(expected);
        assertThat(result.diffAmount()).isEqualTo(diff);
        assertThat(result.supplierAmount()).isEqualTo(billed);
        assertThat(result.lineNo()).isEqualTo(3);
    }
}
