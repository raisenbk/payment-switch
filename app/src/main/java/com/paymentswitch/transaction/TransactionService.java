package com.paymentswitch.transaction;

import com.paymentswitch.catalog.CatalogService;
import com.paymentswitch.catalog.CatalogService.Product;
import com.paymentswitch.common.error.ApiException;
import com.paymentswitch.ledger.LedgerService;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionService {

    private final TransactionRepository repository;
    private final TransactionStateMachine stateMachine;
    private final CatalogService catalog;
    private final LedgerService ledger;
    private final ApplicationEventPublisher events;

    TransactionService(TransactionRepository repository, TransactionStateMachine stateMachine,
            CatalogService catalog, LedgerService ledger, ApplicationEventPublisher events) {
        this.repository = repository;
        this.stateMachine = stateMachine;
        this.catalog = catalog;
        this.ledger = ledger;
        this.events = events;
    }

    /**
     * Accepts a transaction: PENDING row, its first event and the ledger hold are written in one
     * database transaction (the caller's, when wrapped in idempotency). Supplier processing starts
     * only after that commits.
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
        events.publishEvent(new TransactionAccepted(id));
        return TransactionView.of(repository.findById(id).orElseThrow());
    }

    @Transactional(readOnly = true)
    public TransactionView find(UUID merchantId, UUID transactionId) {
        return repository.findForMerchant(transactionId, merchantId)
                .map(TransactionView::of)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND",
                        "Transaction not found"));
    }

    /** Published inside the creating transaction; listeners act after it commits. */
    public record TransactionAccepted(UUID transactionId) {
    }
}
