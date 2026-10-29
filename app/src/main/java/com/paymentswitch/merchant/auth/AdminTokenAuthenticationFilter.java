package com.paymentswitch.merchant.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates operators on {@code /admin/**} with a shared token. A missing or wrong token (or no
 * token configured at all) is rejected with the uniform JSON 401. Built by {@link SecurityConfig}.
 */
class AdminTokenAuthenticationFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Admin-Token";

    private final byte[] expectedDigest;
    private final AuthenticationEntryPoint entryPoint;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    AdminTokenAuthenticationFilter(AdminProperties properties, AuthenticationEntryPoint entryPoint) {
        this.expectedDigest = properties.token().isBlank() ? null : sha256(properties.token());
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        // Comparing fixed-length digests in constant time leaks neither content nor length.
        if (expectedDigest == null || token == null || !MessageDigest.isEqual(expectedDigest, sha256(token))) {
            entryPoint.commence(request, response, new BadCredentialsException("invalid admin token"));
            return;
        }
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        contextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            contextHolder.clearContext();
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
