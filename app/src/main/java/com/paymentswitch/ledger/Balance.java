package com.paymentswitch.ledger;

import com.paymentswitch.common.money.Money;

/**
 * A merchant's funds in one currency, derived from ledger entries.
 *
 * @param available can be spent (held) now
 * @param held      reserved for transactions still in flight
 */
public record Balance(Money available, Money held) {
}
