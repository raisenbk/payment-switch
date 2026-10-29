package com.paymentswitch.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} pollers (outbox, inquiries). With spring.threads.virtual enabled,
 * Spring Boot runs each scheduled task on a virtual thread.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
