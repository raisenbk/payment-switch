package com.paymentswitch.common.web;

import jakarta.servlet.http.HttpServletRequest;

public final class RequestPaths {

    private RequestPaths() {
    }

    /** The raw (not URL-decoded) request path plus query string, exactly as the client sent it. */
    public static String pathWithQuery(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }
}
