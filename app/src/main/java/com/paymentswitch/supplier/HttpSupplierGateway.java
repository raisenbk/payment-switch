package com.paymentswitch.supplier;

import com.paymentswitch.supplier.SupplierResult.Failed;
import com.paymentswitch.supplier.SupplierResult.Success;
import com.paymentswitch.supplier.SupplierResult.Unknown;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;

/**
 * Talks to a supplier over HTTP/JSON and maps every outcome to a {@link SupplierResult}.
 * The mapping rules (see ADR-005):
 *
 * <table>
 *   <tr><th>Outcome</th><th>Submit</th><th>Inquiry</th></tr>
 *   <tr><td>2xx / 409 with SUCCESS | FAILED body</td><td>Success | Failed</td><td>Success | Failed</td></tr>
 *   <tr><td>2xx with PENDING or unreadable body</td><td>Unknown</td><td>Unknown</td></tr>
 *   <tr><td>other 4xx (request rejected)</td><td>Failed</td><td>Unknown (404: Failed)</td></tr>
 *   <tr><td>5xx</td><td>Unknown</td><td>Unknown</td></tr>
 *   <tr><td>connection refused / connect timeout</td><td>Failed (nothing was sent)</td><td>Unknown</td></tr>
 *   <tr><td>read timeout, other I/O error</td><td>Unknown</td><td>Unknown</td></tr>
 * </table>
 */
class HttpSupplierGateway implements SupplierGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpSupplierGateway.class);

    /** Failure code when no connection could be made: nothing was sent. */
    static final String UNREACHABLE = "SUPPLIER_UNREACHABLE";

    private final String supplierCode;
    private final RestClient client;

    HttpSupplierGateway(String supplierCode, SupplierProperties.Endpoint endpoint) {
        this.supplierCode = supplierCode;
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(endpoint.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(endpoint.readTimeout());
        this.client = RestClient.builder()
                .baseUrl(endpoint.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public SupplierResult submit(SupplierRequest request) {
        long started = System.nanoTime();
        SupplierResult result;
        try {
            result = client.post().uri("/transactions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((req, response) -> mapSubmit(response.getStatusCode(), readBody(response)));
        } catch (ResourceAccessException e) {
            result = connectionNeverEstablished(e)
                    ? new Failed(UNREACHABLE)
                    : new Unknown(hasCause(e, HttpTimeoutException.class) ? "READ_TIMEOUT" : "IO_ERROR");
        } catch (RuntimeException e) {
            log.warn("Unexpected error submitting to supplier {}", supplierCode, e);
            result = new Unknown("UNEXPECTED_ERROR");
        }
        log.info("Supplier {} submit {} -> {} in {} ms", supplierCode, request.reference(), result, elapsedMs(started));
        return result;
    }

    @Override
    public SupplierResult inquiry(String reference) {
        long started = System.nanoTime();
        SupplierResult result;
        try {
            result = client.get().uri("/transactions/{reference}", reference)
                    .exchange((req, response) -> mapInquiry(response.getStatusCode(), readBody(response)));
        } catch (RuntimeException e) {
            // We cannot reach the supplier, but the original submit may well have arrived.
            result = new Unknown(hasCause(e, HttpTimeoutException.class) ? "READ_TIMEOUT" : "IO_ERROR");
        }
        log.info("Supplier {} inquiry {} -> {} in {} ms", supplierCode, reference, result, elapsedMs(started));
        return result;
    }

    static SupplierResult mapSubmit(HttpStatusCode status, SupplierResponse body) {
        if (status.is2xxSuccessful() || status.value() == HttpStatus.CONFLICT.value()) {
            // 409: the supplier already has our reference and reports its stored outcome.
            return mapBody(body);
        }
        if (status.is4xxClientError()) {
            return new Failed(body != null && body.errorCode() != null
                    ? body.errorCode() : "SUPPLIER_REJECTED_" + status.value());
        }
        return new Unknown("SUPPLIER_HTTP_" + status.value());
    }

    static SupplierResult mapInquiry(HttpStatusCode status, SupplierResponse body) {
        if (status.is2xxSuccessful()) {
            return mapBody(body);
        }
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            // Supplier contract: every accepted submit is queryable, so "not found" means never received.
            return new Failed("NOT_RECEIVED_BY_SUPPLIER");
        }
        return new Unknown("SUPPLIER_HTTP_" + status.value());
    }

    private static SupplierResult mapBody(SupplierResponse body) {
        if (body == null || body.status() == null) {
            return new Unknown("UNREADABLE_RESPONSE");
        }
        return switch (body.status()) {
            case "SUCCESS" -> new Success(body.supplierRef());
            case "FAILED" -> new Failed(body.errorCode() != null ? body.errorCode() : "SUPPLIER_FAILED");
            case "PENDING" -> new Unknown("SUPPLIER_PENDING");
            default -> new Unknown("UNRECOGNIZED_STATUS");
        };
    }

    private static SupplierResponse readBody(ConvertibleClientHttpResponse response) {
        try {
            return response.bodyTo(SupplierResponse.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Connection refused or connect timeout: not a single byte of the request reached the supplier. */
    private static boolean connectionNeverEstablished(Throwable e) {
        return hasCause(e, HttpConnectTimeoutException.class) || hasCause(e, ConnectException.class);
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    record SupplierResponse(String reference, String status, String supplierRef, String errorCode) {
    }
}
