package com.paymentswitch.common.money;

import java.util.Currency;

/**
 * An amount in integer minor units (e.g. cents; for IDR, rupiah) with an explicit ISO-4217 currency.
 * Never use floating point for money.
 */
public record Money(long amount, String currency) {

    public Money {
        if (amount < 0) {
            throw new IllegalArgumentException("Money amount must not be negative");
        }
        Currency.getInstance(currency); // rejects unknown codes
    }

    public static Money of(long amount, String currency) {
        return new Money(amount, currency);
    }

    public static Money zero(String currency) {
        return new Money(0, currency);
    }

    public Money requirePositive() {
        if (amount <= 0) {
            throw new IllegalArgumentException("Money amount must be positive");
        }
        return this;
    }

    public void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
