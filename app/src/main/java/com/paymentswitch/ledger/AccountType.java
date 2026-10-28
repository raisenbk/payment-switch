package com.paymentswitch.ledger;

/**
 * Ledger account types and their normal balance side. Seen from the switch: merchant funds,
 * supplier payables and revenue are credit-normal (we owe or earned them); cash is debit-normal.
 */
enum AccountType {
    MERCHANT_BALANCE(OwnerType.MERCHANT, Direction.CREDIT),
    MERCHANT_HOLD(OwnerType.MERCHANT, Direction.CREDIT),
    SUPPLIER_PAYABLE(OwnerType.SUPPLIER, Direction.CREDIT),
    REVENUE(OwnerType.SYSTEM, Direction.CREDIT),
    CASH(OwnerType.SYSTEM, Direction.DEBIT);

    final OwnerType ownerType;
    final Direction normalSide;

    AccountType(OwnerType ownerType, Direction normalSide) {
        this.ownerType = ownerType;
        this.normalSide = normalSide;
    }

    enum OwnerType {
        MERCHANT, SUPPLIER, SYSTEM
    }

    enum Direction {
        DEBIT, CREDIT
    }
}
