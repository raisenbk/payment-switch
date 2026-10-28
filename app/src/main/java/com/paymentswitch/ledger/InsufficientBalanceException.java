package com.paymentswitch.ledger;

import com.paymentswitch.common.error.ApiException;
import org.springframework.http.HttpStatus;

public class InsufficientBalanceException extends ApiException {

    InsufficientBalanceException() {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_BALANCE", "Insufficient available balance");
    }
}
