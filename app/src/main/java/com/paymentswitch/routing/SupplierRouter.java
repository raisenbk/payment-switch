package com.paymentswitch.routing;

import com.paymentswitch.common.money.Money;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Picks the supplier for a product. Phase 4 keeps it minimal: the active mapping with the lowest
 * priority value, then the lowest cost. Failover and circuit breaking arrive in Phase 7.
 */
@Service
public class SupplierRouter {

    private final JdbcClient jdbc;

    SupplierRouter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Only mappings in the product's currency that cost no more than its price are eligible. */
    public Optional<Route> route(long productId, Money price) {
        return jdbc.sql("""
                        SELECT s.id, s.code, sp.supplier_sku, sp.cost_price
                          FROM supplier_products sp
                          JOIN suppliers s ON s.id = sp.supplier_id
                         WHERE sp.product_id = :productId
                           AND sp.is_active AND s.is_active
                           AND sp.currency = :currency
                           AND sp.cost_price <= :price
                         ORDER BY sp.priority, sp.cost_price, s.id
                         LIMIT 1""")
                .param("productId", productId)
                .param("currency", price.currency())
                .param("price", price.amount())
                .query((rs, row) -> new Route(rs.getLong("id"), rs.getString("code"), rs.getString("supplier_sku"),
                        Money.of(rs.getLong("cost_price"), price.currency())))
                .optional();
    }

    public record Route(long supplierId, String supplierCode, String supplierSku, Money cost) {
    }
}
