package com.paymentswitch.merchant.auth;

import com.paymentswitch.common.crypto.SecretCipher;
import com.paymentswitch.common.error.ErrorResponseWriter;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Two stateless chains:
 * <ol>
 *   <li>{@code /v1/**}: merchant API, HMAC-signed requests only.</li>
 *   <li>Everything else: health/info are public, all other routes are denied by default, so a
 *       new endpoint outside {@code /v1} stays closed until it gets its own chain.</li>
 * </ol>
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
class SecurityConfig {

    @Bean
    AuthenticationEntryPoint jsonAuthenticationEntryPoint(ErrorResponseWriter errorWriter) {
        return (request, response, exception) -> errorWriter.write(response, HttpStatus.UNAUTHORIZED,
                "AUTHENTICATION_FAILED", "Missing or invalid request authentication");
    }

    @Bean
    @Order(1)
    SecurityFilterChain merchantApiChain(HttpSecurity http, ApiCredentialRepository credentials,
            SecretCipher cipher, AuthenticationEntryPoint entryPoint, ErrorResponseWriter errorWriter,
            Clock clock, AuthProperties properties) throws Exception {
        HmacAuthenticationFilter hmacFilter =
                new HmacAuthenticationFilter(credentials, cipher, entryPoint, errorWriter, clock, properties);
        return stateless(http, entryPoint)
                .securityMatcher("/v1/**")
                .addFilterBefore(hmacFilter, AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain defaultChain(HttpSecurity http, AuthenticationEntryPoint entryPoint) throws Exception {
        return stateless(http, entryPoint)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/error").permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    private static HttpSecurity stateless(HttpSecurity http, AuthenticationEntryPoint entryPoint) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint));
    }
}
