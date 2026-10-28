package com.paymentswitch.common.error;

import org.springframework.http.HttpStatus;

/**
 * Base class for domain errors that should reach the client with a specific status and code.
 * Modules extend this instead of throwing framework exceptions.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
