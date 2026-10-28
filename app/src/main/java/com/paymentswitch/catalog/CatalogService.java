package com.paymentswitch.catalog;

import com.paymentswitch.common.money.Money;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Public API of the catalog module. */
@Service
public class CatalogService {

    private final JdbcClient jdbc;

    CatalogService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Product> findActiveProduct(String code) {
        return jdbc.sql("SELECT id, code, base_price, currency FROM products WHERE code = :code AND is_active")
                .param("code", code)
                .query((rs, row) -> new Product(rs.getLong("id"), rs.getString("code"),
                        Money.of(rs.getLong("base_price"), rs.getString("currency"))))
                .optional();
    }

    /** @param price what the merchant pays */
    public record Product(long id, String code, Money price) {
    }
}
