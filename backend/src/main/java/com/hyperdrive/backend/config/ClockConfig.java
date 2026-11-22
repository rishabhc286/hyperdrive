package com.hyperdrive.backend.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({AuthProperties.class, RateLimitProperties.class, StorageProperties.class})
public class ClockConfig {

    /** Single time source so expiry logic is testable; all timestamps are written from it. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
