package com.paymentswitch.routing;

import com.paymentswitch.common.money.Money;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The routing decision as a pure function, so it can be tested as a decision table.
 *
 * <p>Eligible: supplier and mapping active, same currency as the price, cost not above the price,
 * not already tried for this transaction, and the supplier available (configured, circuit not
 * open). Ranked by:
 * <ol>
 *   <li>healthy first: success rate at or above {@code minSuccessRate};</li>
 *   <li>priority (lower first), the operator's preference;</li>
 *   <li>success rate (higher first);</li>
 *   <li>cost (lower first);</li>
 *   <li>supplier id, so the order is always deterministic.</li>
 * </ol>
 */
final class RoutingPolicy {

    private RoutingPolicy() {
    }

    record Candidate(long supplierId, String supplierCode, String supplierSku, Money cost, int priority,
            BigDecimal successRate, boolean supplierActive, boolean mappingActive) {
    }

    static List<Candidate> rank(List<Candidate> candidates, Money price, Set<Long> excludedSupplierIds,
            Predicate<String> available, BigDecimal minSuccessRate) {
        Comparator<Candidate> order = Comparator
                .comparing((Candidate c) -> c.successRate().compareTo(minSuccessRate) < 0)
                .thenComparingInt(Candidate::priority)
                .thenComparing(Candidate::successRate, Comparator.reverseOrder())
                .thenComparingLong(c -> c.cost().amount())
                .thenComparingLong(Candidate::supplierId);
        return candidates.stream()
                .filter(c -> c.supplierActive() && c.mappingActive())
                .filter(c -> c.cost().currency().equals(price.currency()))
                .filter(c -> c.cost().amount() <= price.amount())
                .filter(c -> !excludedSupplierIds.contains(c.supplierId()))
                .filter(c -> available.test(c.supplierCode()))
                .sorted(order)
                .toList();
    }
}
