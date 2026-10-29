package com.paymentswitch.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentswitch.common.money.Money;
import com.paymentswitch.routing.RoutingPolicy.Candidate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Routing decision table: which supplier is chosen (first of the ranking), if any. */
class RoutingPolicyTest {

    private static final Money PRICE = Money.of(10_500, "IDR");
    private static final BigDecimal MIN_SUCCESS_RATE = new BigDecimal("0.80");

    static Stream<Arguments> decisionTable() {
        return Stream.of(
                row("lower priority value wins",
                        List.of(c(1, "A", 10_000, 2, "1.0"), c(2, "B", 10_000, 1, "1.0")), Set.of(), Set.of(), "B"),
                row("same priority: higher success rate wins",
                        List.of(c(1, "A", 10_000, 1, "0.90"), c(2, "B", 10_000, 1, "0.99")), Set.of(), Set.of(), "B"),
                row("same priority and rate: cheaper wins",
                        List.of(c(1, "A", 10_200, 1, "1.0"), c(2, "B", 10_000, 1, "1.0")), Set.of(), Set.of(), "B"),
                row("full tie: lowest supplier id",
                        List.of(c(2, "B", 10_000, 1, "1.0"), c(1, "A", 10_000, 1, "1.0")), Set.of(), Set.of(), "A"),
                row("inactive supplier is skipped",
                        List.of(inactiveSupplier(c(1, "A", 10_000, 1, "1.0")), c(2, "B", 10_000, 2, "1.0")),
                        Set.of(), Set.of(), "B"),
                row("inactive mapping is skipped",
                        List.of(inactiveMapping(c(1, "A", 10_000, 1, "1.0")), c(2, "B", 10_000, 2, "1.0")),
                        Set.of(), Set.of(), "B"),
                row("cost above the price is skipped (would sell at a loss)",
                        List.of(c(1, "A", 10_600, 1, "1.0"), c(2, "B", 10_400, 2, "1.0")), Set.of(), Set.of(), "B"),
                row("cost equal to the price is allowed",
                        List.of(c(1, "A", 10_500, 1, "1.0")), Set.of(), Set.of(), "A"),
                row("other currency is skipped",
                        List.of(usd(c(1, "A", 10_000, 1, "1.0")), c(2, "B", 10_000, 2, "1.0")), Set.of(), Set.of(), "B"),
                row("failover: supplier already tried is skipped",
                        List.of(c(1, "A", 10_000, 1, "1.0"), c(2, "B", 10_000, 2, "1.0")), Set.of(1L), Set.of(), "B"),
                row("circuit open (unavailable) is skipped",
                        List.of(c(1, "A", 10_000, 1, "1.0"), c(2, "B", 10_000, 2, "1.0")), Set.of(), Set.of("A"), "B"),
                row("unhealthy success rate ranks after healthy ones, whatever the priority",
                        List.of(c(1, "A", 10_000, 1, "0.50"), c(2, "B", 10_000, 5, "0.95")), Set.of(), Set.of(), "B"),
                row("exactly the minimum success rate counts as healthy",
                        List.of(c(1, "A", 10_000, 1, "0.80"), c(2, "B", 10_000, 5, "0.95")), Set.of(), Set.of(), "A"),
                row("only unhealthy suppliers left: still used",
                        List.of(c(1, "A", 10_000, 1, "0.50"), c(2, "B", 10_000, 2, "0.40")), Set.of(), Set.of(), "A"),
                row("all tried or unavailable: no route",
                        List.of(c(1, "A", 10_000, 1, "1.0"), c(2, "B", 10_000, 2, "1.0")), Set.of(1L), Set.of("B"),
                        null),
                row("no mapping at all: no route", List.of(), Set.of(), Set.of(), null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("decisionTable")
    void choosesTheExpectedSupplier(String rule, List<Candidate> candidates, Set<Long> tried, Set<String> unavailable,
            String expected) {
        List<Candidate> ranked = RoutingPolicy.rank(candidates, PRICE, tried, code -> !unavailable.contains(code),
                MIN_SUCCESS_RATE);

        assertThat(ranked.stream().findFirst().map(Candidate::supplierCode).orElse(null)).isEqualTo(expected);
    }

    private static Arguments row(String rule, List<Candidate> candidates, Set<Long> tried, Set<String> unavailable,
            String expected) {
        return Arguments.of(rule, candidates, tried, unavailable, expected);
    }

    private static Candidate c(long id, String code, long cost, int priority, String successRate) {
        return new Candidate(id, code, "SKU-" + code, Money.of(cost, "IDR"), priority, new BigDecimal(successRate),
                true, true);
    }

    private static Candidate inactiveSupplier(Candidate c) {
        return new Candidate(c.supplierId(), c.supplierCode(), c.supplierSku(), c.cost(), c.priority(),
                c.successRate(), false, true);
    }

    private static Candidate inactiveMapping(Candidate c) {
        return new Candidate(c.supplierId(), c.supplierCode(), c.supplierSku(), c.cost(), c.priority(),
                c.successRate(), true, false);
    }

    private static Candidate usd(Candidate c) {
        return new Candidate(c.supplierId(), c.supplierCode(), c.supplierSku(), Money.of(c.cost().amount(), "USD"),
                c.priority(), c.successRate(), true, true);
    }
}
