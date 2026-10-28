package com.paymentswitch.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Enables {@code @Async}; Spring Boot's task executor runs on virtual threads (spring.threads.virtual). */
@Configuration
@EnableAsync
public class AsyncConfig {
}
