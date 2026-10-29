package com.paymentswitch.common.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.security.config.observation.SecurityObservationSettings;

/**
 * Keeps traces about business work. The pollers run every few hundred milliseconds and Prometheus
 * scrapes every few seconds; observing those would bury every real trace. Pollers start their own
 * spans when they actually handle something (see {@code TraceContexts}).
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    ObservationPredicate ignoreSchedulerTicksAndActuator() {
        return (name, context) -> {
            if (name.equals("tasks.scheduled.execution")) {
                return false;
            }
            return !(context instanceof ServerRequestObservationContext request
                    && request.getCarrier().getRequestURI().startsWith("/actuator"));
        };
    }

    /**
     * Spring Security's own filter-chain observations add a span per filter phase and, for dropped
     * requests (scrapes, health checks), would become traces of their own. The HTTP span is enough.
     */
    @Bean
    SecurityObservationSettings noSpringSecurityObservations() {
        return SecurityObservationSettings.noObservations();
    }
}
