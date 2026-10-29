package com.paymentswitch.support;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Creates isolated IDR products, mapped to supplier MOCKA (the mock-supplier container). */
@TestComponent
public class TestCatalog {

    public static final String SUPPLIER_CODE = "MOCKA";

    private final JdbcClient jdbc;

    public TestCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public TestProduct productSuppliedByMockA(long price, long supplierCost) {
        TestProduct product = productWithoutSupplier(price);
        jdbc.sql("""
                        INSERT INTO supplier_products (supplier_id, product_id, supplier_sku, cost_price, currency, priority)
                        VALUES (:supplierId, :productId, :sku, :cost, 'IDR', 1)""")
                .param("supplierId", supplierId(SUPPLIER_CODE))
                .param("productId", product.id())
                .param("sku", "SKU-" + product.code())
                .param("cost", supplierCost)
                .update();
        return product;
    }

    /** A product mapped to several suppliers (created on first use), e.g. for failover tests. */
    public TestProduct productSuppliedBy(long price, Mapping... mappings) {
        TestProduct product = productWithoutSupplier(price);
        for (Mapping mapping : mappings) {
            jdbc.sql("""
                            INSERT INTO supplier_products
                                (supplier_id, product_id, supplier_sku, cost_price, currency, priority, success_rate)
                            VALUES (:supplierId, :productId, :sku, :cost, 'IDR', :priority, :successRate)""")
                    .param("supplierId", supplierId(mapping.supplierCode()))
                    .param("productId", product.id())
                    .param("sku", mapping.supplierCode() + "-" + product.code())
                    .param("cost", mapping.cost())
                    .param("priority", mapping.priority())
                    .param("successRate", mapping.successRate())
                    .update();
        }
        return product;
    }

    public TestProduct productWithoutSupplier(long price) {
        String code = "P-" + UUID.randomUUID().toString().substring(0, 8);
        long id = jdbc.sql("""
                        INSERT INTO products (code, name, category, base_price, currency)
                        VALUES (:code, :name, 'PREPAID', :price, 'IDR') RETURNING id""")
                .param("code", code)
                .param("name", "Test product " + code)
                .param("price", price)
                .query(Long.class)
                .single();
        return new TestProduct(id, code, price);
    }

    public long supplierId(String code) {
        jdbc.sql("INSERT INTO suppliers (code, name) VALUES (:code, :code) ON CONFLICT (code) DO NOTHING")
                .param("code", code)
                .update();
        return jdbc.sql("SELECT id FROM suppliers WHERE code = :code").param("code", code).query(Long.class).single();
    }

    public record TestProduct(long id, String code, long price) {
    }

    public record Mapping(String supplierCode, long cost, int priority, BigDecimal successRate) {

        public static Mapping of(String supplierCode, long cost, int priority) {
            return new Mapping(supplierCode, cost, priority, BigDecimal.ONE);
        }
    }
}
