package kr.hhplus.be.server.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "queue")
public class QueueConfig {
    private int maxActiveUsers = 100;
    private int tokenTtlMinutes = 10;
    private SchedulerConfig scheduler = new SchedulerConfig();

    // Getters and Setters

    public static class SchedulerConfig {
        private boolean enabled = true;
        private int processIntervalMs = 1000;

        // Getters and Setters
    }
}