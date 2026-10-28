package com.paymentswitch.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** Inject this instead of calling {@code Instant.now()} so time-dependent logic stays testable. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
