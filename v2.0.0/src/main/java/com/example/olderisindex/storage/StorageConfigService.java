package com.example.olderisindex.storage;

import com.example.olderisindex.config.AppProperties;
import com.example.olderisindex.config.ClusterRegistry;
import com.example.olderisindex.config.ClusterRegistry.ClusterContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class StorageConfigService {

    private static final Logger log = LoggerFactory.getLogger(StorageConfigService.class);

    private final ClusterRegistry registry;
    private final AppProperties props;
    private final ObjectMapper mapper;

    private final String loadSql;
    private final Map<String, String> fingerprintSqlByDialect;

    private final Map<String, ClusterState> states = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "storage-config-fingerprint");
                t.setDaemon(true);
                return t;
            });

    public StorageConfigService(
            ClusterRegistry registry,
            AppProperties props,
            ObjectMapper mapper,
            @Value("classpath:sql/storage-config/load.sql") Resource loadSqlResource,
            @Value("classpath:sql/storage-config/fingerprint.oracle.sql")   Resource fpOracle,
            @Value("classpath:sql/storage-config/fingerprint.postgres.sql") Resource fpPostgres) {
        this.registry = registry;
        this.props = props;
        this.mapper = mapper;
        this.loadSql = read(loadSqlResource);
        this.fingerprintSqlByDialect = Map.of(
                "oracle",   read(fpOracle),
                "postgres", read(fpPostgres),
                "h2",       "SELECT RAWTOHEX(HASH('SHA256', content)) FROM dcm4che_config WHERE path = ?");
    }

    private static String read(Resource r) {
        try (var in = r.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось загрузить SQL storage-config", e);
        }
    }

    public static class ClusterState {
        final AtomicReference<Map<StorageKey, String>> cache = new AtomicReference<>(Collections.emptyMap());
        volatile String currentFingerprint;
        volatile long version;
        volatile Instant lastLoadTime;
        volatile Instant lastFingerprintCheckTime;
        volatile boolean ready;
        volatile String lastError;
    }

    public static class ClusterStatus {
        public String name;
        public boolean ready;
        public int size;
        public long version;
        public String fingerprint;
        public Instant lastLoadTime;
        public Instant lastFingerprintCheckTime;
        public String lastError;
        public String path;
    }

    @PostConstruct
    public void init() {
        int readyCount = 0;
        for (ClusterContext ctx : registry.all()) {
            AppProperties.Cluster cfg = props.clusters().stream()
                    .filter(c -> c.name().equals(ctx.name())).findFirst().orElseThrow();
            ClusterState s = new ClusterState();
            states.put(ctx.name(), s);
            try {
                doFullReload(ctx, cfg, s);
                readyCount++;
                log.info("Storage config loaded: cluster={}, entries={}, version={}, fingerprint={}",
                        ctx.name(), s.cache.get().size(), s.version, shortFp(s.currentFingerprint));
            } catch (RuntimeException e) {
                s.lastError = e.getMessage();
                log.error("Storage config failed on startup: cluster={} — {}", ctx.name(), e.getMessage(), e);
                if (cfg.storageConfig().isInitialLoadFailFast()) {
                    throw new StorageConfigException(
                            "Storage config failed on startup for cluster " + ctx.name(), e);
                }
            }
        }

        String policy = props.clustersStartupPolicy() == null ? "require-any" : props.clustersStartupPolicy();
        int total = registry.all().size();
        switch (policy) {
            case "require-all" -> {
                if (readyCount < total) {
                    throw new StorageConfigException(
                            "clusters-startup-policy=require-all, но загрузились только "
                            + readyCount + "/" + total);
                }
            }
            case "require-any" -> {
                if (readyCount == 0) {
                    throw new StorageConfigException(
                            "clusters-startup-policy=require-any, но ни один кластер не загрузился");
                }
            }
            case "best-effort" -> {
                if (readyCount == 0) {
                    log.warn("clusters-startup-policy=best-effort: ни один кластер не загрузился");
                }
            }
            default -> throw new IllegalArgumentException(
                    "Неизвестное значение app.clusters-startup-policy: " + policy);
        }

        scheduleFingerprint();
    }

    private void scheduleFingerprint() {
        long minIntervalMs = Long.MAX_VALUE;
        for (ClusterContext ctx : registry.all()) {
            AppProperties.Cluster cfg = props.clusters().stream()
                    .filter(c -> c.name().equals(ctx.name())).findFirst().orElseThrow();
            Duration d = cfg.storageConfig().fingerprintInterval();
            if (cfg.storageConfig().isFingerprintEnabled()
                    && d != null && !d.isZero() && !d.isNegative()) {
                minIntervalMs = Math.min(minIntervalMs, d.toMillis());
            }
        }
        if (minIntervalMs == Long.MAX_VALUE) {
            log.info("Fingerprint polling disabled for all clusters");
            return;
        }
        long interval = minIntervalMs;
        scheduler.scheduleAtFixedRate(this::fingerprintTickAll, interval, interval, TimeUnit.MILLISECONDS);
        log.info("Fingerprint polling enabled: base interval {}", Duration.ofMillis(interval));
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private void fingerprintTickAll() {
        for (ClusterContext ctx : registry.all()) {
            AppProperties.Cluster cfg = props.clusters().stream()
                    .filter(c -> c.name().equals(ctx.name())).findFirst().orElseThrow();
            if (!cfg.storageConfig().isFingerprintEnabled()) continue;
            try {
                fingerprintCheck(ctx, cfg);
            } catch (RuntimeException e) {
                log.error("Fingerprint check failed: cluster={}, reason={}", ctx.name(), e.getMessage());
            }
        }
    }

    private void fingerprintCheck(ClusterContext ctx, AppProperties.Cluster cfg) {
        ClusterState s = states.get(ctx.name());
        if (s == null) return;
        String sql = fingerprintSqlFor(cfg.dialect());
        String newFp = ctx.jdbc().queryForObject(sql, String.class, cfg.storageConfig().path());
        s.lastFingerprintCheckTime = Instant.now();

        if (log.isDebugEnabled()) {
            log.debug("fingerprintCheck(): cluster={}, current={}, db={} → {}",
                    ctx.name(), shortFp(s.currentFingerprint), shortFp(newFp),
                    Objects.equals(newFp, s.currentFingerprint) ? "unchanged" : "changed");
        }
        if (Objects.equals(newFp, s.currentFingerprint)) return;

        log.info("Storage config: cluster={}, fingerprint changed ({} → {}), reloading",
                ctx.name(), shortFp(s.currentFingerprint), shortFp(newFp));
        refresh(ctx.name());
    }

    public synchronized void refresh(String clusterName) {
        ClusterContext ctx = registry.get(clusterName);
        if (ctx == null) throw new StorageConfigException("Unknown cluster: " + clusterName);
        AppProperties.Cluster cfg = props.clusters().stream()
                .filter(c -> c.name().equals(clusterName)).findFirst().orElseThrow();
        ClusterState s = states.get(clusterName);
        long before = s.version;
        try {
            doFullReload(ctx, cfg, s);
            log.info("Storage config refreshed: cluster={}, entries={}, version={}, fingerprint={}",
                    clusterName, s.cache.get().size(), s.version, shortFp(s.currentFingerprint));
        } catch (RuntimeException e) {
            log.error("Storage config reload failed: cluster={}, keeping version={}, reason={}",
                    clusterName, before, e.getMessage(), e);
        }
    }

    public void refreshAll() {
        for (String name : registry.names()) refresh(name);
    }

    private void doFullReload(ClusterContext ctx, AppProperties.Cluster cfg, ClusterState s) {
        String path = cfg.storageConfig().path();
        String json = ctx.jdbc().queryForObject(loadSql, String.class, path);
        if (json == null || json.isBlank()) {
            s.ready = false;
            throw new StorageConfigException("No config row for path " + path + " (cluster " + ctx.name() + ")");
        }
        String dbHash = ctx.jdbc().queryForObject(fingerprintSqlFor(cfg.dialect()), String.class, path);
        Map<StorageKey, String> newMap = parse(json);
        if (newMap.isEmpty()) {
            s.ready = false;
            throw new StorageConfigException("Empty storage config (cluster " + ctx.name()
                    + ", path " + path + ")");
        }
        s.cache.set(newMap);
        s.currentFingerprint = dbHash;
        s.lastLoadTime = Instant.now();
        s.version++;
        s.ready = true;
        s.lastError = null;
    }

    private String fingerprintSqlFor(String dialect) {
        String sql = fingerprintSqlByDialect.get(dialect == null ? "oracle" : dialect.toLowerCase());
        if (sql == null) throw new StorageConfigException("Unknown dialect: " + dialect);
        return sql;
    }

    private Map<StorageKey, String> parse(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode groups = root.path("deviceExtensions")
                    .path("StorageDeviceExtension")
                    .path("Storage System Groups");
            Map<StorageKey, String> map = new LinkedHashMap<>();
            if (groups.isArray()) {
                for (JsonNode group : groups) {
                    String groupId = group.path("dcmStorageSystemGroupID").asText(null);
                    JsonNode systems = group.path("Storage Systems");
                    if (groupId == null || !systems.isArray()) continue;
                    for (JsonNode sys : systems) {
                        String storageId = sys.path("dcmStorageSystemID").asText(null);
                        String path      = sys.path("dcmStorageSystemPath").asText(null);
                        if (storageId != null && path != null) {
                            map.put(new StorageKey(groupId, storageId), path);
                        }
                    }
                }
            }
            return map;
        } catch (Exception e) {
            throw new StorageConfigException("Failed to parse storage config", e);
        }
    }

    public Optional<String> ssPath(String clusterName, String groupId, String storageId) {
        if (clusterName == null || groupId == null || storageId == null) return Optional.empty();
        ClusterState s = states.get(clusterName);
        if (s == null) return Optional.empty();
        return Optional.ofNullable(s.cache.get().get(new StorageKey(groupId, storageId)));
    }

    public boolean isReady(String clusterName) {
        ClusterState s = states.get(clusterName);
        return s != null && s.ready;
    }

    public ClusterStatus status(String clusterName) {
        ClusterState s = states.get(clusterName);
        AppProperties.Cluster cfg = props.clusters().stream()
                .filter(c -> c.name().equals(clusterName)).findFirst().orElseThrow();
        ClusterStatus st = new ClusterStatus();
        st.name = clusterName;
        if (s != null) {
            st.ready = s.ready;
            st.size = s.cache.get().size();
            st.version = s.version;
            st.fingerprint = s.currentFingerprint;
            st.lastLoadTime = s.lastLoadTime;
            st.lastFingerprintCheckTime = s.lastFingerprintCheckTime;
            st.lastError = s.lastError;
        }
        st.path = cfg.storageConfig().path();
        return st;
    }

    public List<String> clusterNames() { return registry.names(); }

    private static String shortFp(String fp) {
        if (fp == null) return "null";
        return fp.length() > 8 ? fp.substring(0, 4) + "…" + fp.substring(fp.length() - 4) : fp;
    }
}
