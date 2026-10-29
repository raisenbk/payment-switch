package com.paymentswitch.routing;

import com.paymentswitch.common.money.Money;
import com.paymentswitch.routing.RoutingPolicy.Candidate;
import com.paymentswitch.supplier.SupplierGateways;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Picks the supplier for a product: see {@link RoutingPolicy} for the rules. */
@Service
@EnableConfigurationProperties(RoutingProperties.class)
public class SupplierRouter {

    private final JdbcClient jdbc;
    private final SupplierGateways gateways;
    private final RoutingProperties properties;

    SupplierRouter(JdbcClient jdbc, SupplierGateways gateways, RoutingProperties properties) {
        this.jdbc = jdbc;
        this.gateways = gateways;
        this.properties = properties;
    }

    public Optional<Route> route(long productId, Money price) {
        return route(productId, price, Set.of());
    }

    /** The best eligible supplier that is not in {@code excludedSupplierIds} (already tried). */
    public Optional<Route> route(long productId, Money price, Set<Long> excludedSupplierIds) {
        var candidates = jdbc.sql("""
                        SELECT s.id, s.code, sp.supplier_sku, sp.cost_price, sp.currency, sp.priority,
                               sp.success_rate, s.is_active AS supplier_active, sp.is_active AS mapping_active
                          FROM supplier_products sp
                          JOIN suppliers s ON s.id = sp.supplier_id
                         WHERE sp.product_id = :productId""")
                .param("productId", productId)
                .query((rs, row) -> new Candidate(rs.getLong("id"), rs.getString("code"),
                        rs.getString("supplier_sku"), Money.of(rs.getLong("cost_price"), rs.getString("currency")),
                        rs.getInt("priority"), rs.getBigDecimal("success_rate"), rs.getBoolean("supplier_active"),
                        rs.getBoolean("mapping_active")))
                .list();
        return RoutingPolicy.rank(candidates, price, excludedSupplierIds, gateways::isAvailable,
                        properties.minSuccessRate())
                .stream()
                .findFirst()
                .map(c -> new Route(c.supplierId(), c.supplierCode(), c.supplierSku(), c.cost()));
    }

    /** Whether a definitive failure with this code may be retried at another supplier (ADR-008). */
    public boolean mayFailOver(String failureCode, int suppliersTriedSoFar) {
        return suppliersTriedSoFar < properties.maxSuppliersPerTransaction()
                && !properties.nonRetryableFailures().contains(failureCode);
    }

    public record Route(long supplierId, String supplierCode, String supplierSku, Money cost) {
    }
}
