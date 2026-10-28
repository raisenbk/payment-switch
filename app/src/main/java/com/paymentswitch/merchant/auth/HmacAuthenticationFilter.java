package com.paymentswitch.merchant.auth;

import com.paymentswitch.common.crypto.SecretCipher;
import com.paymentswitch.common.error.ErrorResponseWriter;
import com.paymentswitch.common.web.CachedBodyHttpServletRequest;
import com.paymentswitch.common.web.CachedBodyHttpServletRequest.BodyTooLargeException;
import com.paymentswitch.common.web.RequestPaths;
import com.paymentswitch.merchant.auth.ApiCredentialRepository.ActiveCredential;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code /v1/**} requests from the {@code X-Api-Key}, {@code X-Timestamp} and
 * {@code X-Signature} headers (see {@link RequestSignature}).
 *
 * <p>Every rejection produces the same generic 401 so callers cannot probe which check failed; the
 * reason is logged at debug level only. Signatures, secrets and keys are never logged.
 */
class HmacAuthenticationFilter extends OncePerRequestFilter {

    static final String API_KEY_HEADER = "X-Api-Key";
    static final String TIMESTAMP_HEADER = "X-Timestamp";
    static final String SIGNATURE_HEADER = "X-Signature";
    static final String MDC_MERCHANT_ID = "merchantId";

    private static final Logger log = LoggerFactory.getLogger(HmacAuthenticationFilter.class);

    private final ApiCredentialRepository credentials;
    private final SecretCipher cipher;
    private final AuthenticationEntryPoint entryPoint;
    private final ErrorResponseWriter errorWriter;
    private final Clock clock;
    private final Duration tolerance;
    private final int maxBodyBytes;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    HmacAuthenticationFilter(ApiCredentialRepository credentials, SecretCipher cipher,
            AuthenticationEntryPoint entryPoint, ErrorResponseWriter errorWriter, Clock clock,
            AuthProperties properties) {
        this.credentials = credentials;
        this.cipher = cipher;
        this.entryPoint = entryPoint;
        this.errorWriter = errorWriter;
        this.clock = clock;
        this.tolerance = properties.timestampTolerance();
        this.maxBodyBytes = Math.toIntExact(properties.maxBodySize().toBytes());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String apiKey = request.getHeader(API_KEY_HEADER);
        String timestampHeader = request.getHeader(TIMESTAMP_HEADER);
        String signature = request.getHeader(SIGNATURE_HEADER);
        if (isBlank(apiKey) || isBlank(timestampHeader) || isBlank(signature)) {
            reject(request, response, "missing authentication headers");
            return;
        }

        Optional<Long> timestamp = parseEpochSeconds(timestampHeader);
        if (timestamp.isEmpty()) {
            reject(request, response, "malformed timestamp");
            return;
        }
        Duration skew = Duration.between(Instant.ofEpochSecond(timestamp.get()), clock.instant()).abs();
        if (skew.compareTo(tolerance) > 0) {
            reject(request, response, "timestamp outside tolerance window");
            return;
        }

        CachedBodyHttpServletRequest cached;
        try {
            cached = CachedBodyHttpServletRequest.of(request, maxBodyBytes);
        } catch (BodyTooLargeException e) {
            errorWriter.write(response, HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                    "Request body exceeds " + maxBodyBytes + " bytes");
            return;
        }

        Optional<ActiveCredential> credential = credentials.findActive(apiKey);
        if (credential.isEmpty()) {
            reject(request, response, "unknown or inactive API key");
            return;
        }

        String canonical = RequestSignature.canonicalString(
                request.getMethod(), RequestPaths.pathWithQuery(request), timestamp.get(), cached.body());
        String secret = cipher.decrypt(credential.get().secretCiphertext());
        if (!RequestSignature.matches(secret, canonical, signature)) {
            reject(request, response, "signature mismatch");
            return;
        }

        MerchantPrincipal principal = new MerchantPrincipal(credential.get().merchantId());
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        contextHolder.setContext(context);
        MDC.put(MDC_MERCHANT_ID, principal.merchantId().toString());
        try {
            chain.doFilter(cached, response);
        } finally {
            MDC.remove(MDC_MERCHANT_ID);
            contextHolder.clearContext();
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String reason)
            throws IOException, ServletException {
        log.debug("Rejected {} {}: {}", request.getMethod(), request.getRequestURI(), reason);
        contextHolder.clearContext();
        entryPoint.commence(request, response, new BadCredentialsException(reason));
    }

    private static Optional<Long> parseEpochSeconds(String value) {
        try {
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
