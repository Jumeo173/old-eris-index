package com.example.olderisindex.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        List<Cluster> clusters,
        Research research,
        String clustersStartupPolicy
) {

    public record Cluster(
            String name,
            String dialect,
            String jdbcUrl,
            String username,
            String password,
            String driverClassName,
            int maxPoolSize,
            StorageConfig storageConfig
    ) {}

    public record StorageConfig(
            String path,
            Duration fingerprintInterval,
            Boolean fingerprintEnabled,
            Boolean initialLoadFailFast
    ) {
        public StorageConfig {
            if (fingerprintInterval == null) fingerprintInterval = Duration.ofHours(1);
            if (fingerprintEnabled == null) fingerprintEnabled = Boolean.TRUE;
            if (initialLoadFailFast == null) initialLoadFailFast = Boolean.TRUE;
        }
        public boolean isFingerprintEnabled() { return Boolean.TRUE.equals(fingerprintEnabled); }
        public boolean isInitialLoadFailFast() { return Boolean.TRUE.equals(initialLoadFailFast); }
    }

    public record Research(
            long dbTimeoutSeconds,
            int executorCore,
            int executorMax,
            int executorQueue
    ) {
        public Research {
            if (dbTimeoutSeconds == 0) dbTimeoutSeconds = 20;
            if (executorCore    == 0) executorCore    = 8;
            if (executorMax     == 0) executorMax     = 16;
            if (executorQueue   == 0) executorQueue   = 100;
        }
    }
}
