package com.paymentswitch.transaction;

import com.paymentswitch.catalog.CatalogService;
import com.paymentswitch.catalog.CatalogService.Product;
import com.paymentswitch.common.error.ApiException;
import com.paymentswitch.common.outbox.OutboxPublisher;
import com.paymentswitch.ledger.LedgerService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionService {

    private final TransactionRepository repository;
    private final TransactionStateMachine stateMachine;
    private final CatalogService catalog;
    private final LedgerService ledger;
    private final OutboxPublisher outbox;

    TransactionService(TransactionRepository repository, TransactionStateMachine stateMachine,
            CatalogService catalog, LedgerService ledger, OutboxPublisher outbox) {
        this.repository = repository;
        this.stateMachine = stateMachine;
        this.catalog = catalog;
        this.ledger = ledger;
        this.outbox = outbox;
    }

    /**
     * Accepts a transaction: the PENDING row, its first event, the ledger hold and the outbox event
     * that triggers supplier processing are written in one database transaction (the caller's, when
     * wrapped in idempotency). Either all of it survives a crash or none of it does (ADR-006).
     */
    @Transactional
    public TransactionView create(UUID merchantId, String merchantRef, String productCode, String destination) {
        Product product = catalog.findActiveProduct(productCode)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "PRODUCT_NOT_AVAILABLE",
                        "Unknown or inactive product"));
        UUID id = UUID.randomUUID();
        if (!repository.insertPending(id, merchantId, merchantRef, product.id(), destination,
                product.price().amount(), product.price().currency())) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_MERCHANT_REF",
                    "merchantRef was already used for another transaction");
        }
        stateMachine.recordCreated(id, Map.of("productCode", product.code(), "amount", product.price().amount()));
        ledger.hold(merchantId, id, product.price());
        outbox.publish(TransactionAcceptedHandler.EVENT_TYPE, id, Map.of());
        return TransactionView.of(repository.findById(id).orElseThrow());
    }

    @Transactional(readOnly = true)
    public TransactionView find(UUID merchantId, UUID transactionId) {
        return repository.findForMerchant(transactionId, merchantId)
                .map(TransactionView::of)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND",
                        "Transaction not found"));
    }
}
