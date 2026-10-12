package com.jengabank.payments.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables the end-of-day reconciliation cron job. */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    Object schedulingPlaceholder() {
        return new Object();
    }
}
