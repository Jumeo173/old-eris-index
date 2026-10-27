package com.example.olderisindex.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class StorageConfigService {
    private static final Logger log = LoggerFactory.getLogger(StorageConfigService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String configPath;
    private final boolean fingerprintEnabled;
    private final boolean initialLoadFailFast;
    private final Duration fingerprintInterval;
    private final String loadSql;
    private final String fingerprintSql;

    private final AtomicReference<Map<StorageKey, String>> cache =
            new AtomicReference<>(Collections.emptyMap());

    private volatile String currentFingerprint;
    private volatile long version;
    private volatile Instant lastLoadTime;
    private volatile Instant lastFingerprintCheckTime;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "storage-config-fingerprint");
                t.setDaemon(true); return t;
            });

    public StorageConfigService(
            JdbcTemplate jdbc, ObjectMapper mapper,
            @Value("classpath:sql/storage-config/load.sql") Resource loadSqlResource,
            @Value("classpath:sql/storage-config/fingerprint.${app.db.dialect}.sql") Resource fpResource,
            @Value("${app.storage-config.path}") String configPath,
            @Value("${app.storage-config.fingerprint-enabled:true}") boolean fingerprintEnabled,
            @Value("${app.storage-config.initial-load-fail-fast:true}") boolean initialLoadFailFast,
            @Value("${app.storage-config.fingerprint-interval:1h}") Duration fingerprintInterval) {
        this.jdbc = jdbc; this.mapper = mapper; this.configPath = configPath;
        this.fingerprintEnabled = fingerprintEnabled;
        this.initialLoadFailFast = initialLoadFailFast;
        this.fingerprintInterval = fingerprintInterval;
        this.loadSql = read(loadSqlResource);
        this.fingerprintSql = read(fpResource);
    }

    private static String read(Resource r) {
        try (var in = r.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось загрузить SQL storage-config", e);
        }
    }

    @PostConstruct
    public void init() {
        try {
            doFullReload();
            log.info("Storage config loaded on startup: {} entries, version {}, fingerprint={}",
                    size(), version, shortFp(currentFingerprint));
        } catch (RuntimeException e) {
            if (initialLoadFailFast) {
                log.error("Failed to load storage config on startup (fail-fast)", e);
                throw new StorageConfigException("Failed to load storage config on startup", e);
            } else {
                log.error("Failed to load storage config on startup (empty cache)", e);
            }
        }
        if (fingerprintEnabled && !fingerprintInterval.isZero() && !fingerprintInterval.isNegative()) {
            long ms = fingerprintInterval.toMillis();
            scheduler.scheduleAtFixedRate(this::fingerprintCheck, ms, ms, TimeUnit.MILLISECONDS);
            log.info("Fingerprint polling enabled: every {}", fingerprintInterval);
        } else {
            log.info("Fingerprint polling disabled (enabled={}, interval={})",
                    fingerprintEnabled, fingerprintInterval);
        }
    }

    @PreDestroy public void shutdown() { scheduler.shutdownNow(); }

    private void fingerprintCheck() {
        try {
            String newFp = jdbc.queryForObject(fingerprintSql, String.class, configPath);
            lastFingerprintCheckTime = Instant.now();
            if (log.isDebugEnabled()) {
                log.debug("fingerprintCheck(): current={}, db={} → {}",
                        shortFp(currentFingerprint), shortFp(newFp),
                        Objects.equals(newFp, currentFingerprint) ? "unchanged" : "changed");
            }
            if (Objects.equals(newFp, currentFingerprint)) return;
            log.info("Storage config: fingerprint changed ({} → {}), starting full reload",
                    shortFp(currentFingerprint), shortFp(newFp));
            refresh();
        } catch (RuntimeException e) {
            log.error("Fingerprint check failed (keeping version {}): {}", version, e.getMessage());
        }
    }

    public synchronized void refresh() {
        try {
            doFullReload();
            log.info("Storage config refreshed: {} entries, version {}, fingerprint={}",
                    size(), version, shortFp(currentFingerprint));
        } catch (RuntimeException e) {
            log.error("Full reload failed (keeping version {}): {}", version, e.getMessage(), e);
        }
    }

    private void doFullReload() {
        String json = jdbc.queryForObject(loadSql, String.class, configPath);
        if (json == null || json.isBlank())
            throw new StorageConfigException("No config row for path " + configPath);
        String dbHash = jdbc.queryForObject(fingerprintSql, String.class, configPath);
        Map<StorageKey, String> newMap = parse(json);
        if (newMap.isEmpty())
            throw new StorageConfigException("Empty storage config (path " + configPath + ")");
        cache.set(newMap);
        currentFingerprint = dbHash;
        lastLoadTime = Instant.now();
        version++;
    }

    private Map<StorageKey, String> parse(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode groups = root.path("deviceExtensions")
                    .path("StorageDeviceExtension").path("Storage System Groups");
            Map<StorageKey, String> map = new LinkedHashMap<>();
            if (groups.isArray()) {
                for (JsonNode group : groups) {
                    String groupId = group.path("dcmStorageSystemGroupID").asText(null);
                    JsonNode systems = group.path("Storage Systems");
                    if (groupId == null || !systems.isArray()) continue;
                    for (JsonNode sys : systems) {
                        String storageId = sys.path("dcmStorageSystemID").asText(null);
                        String path = sys.path("dcmStorageSystemPath").asText(null);
                        if (storageId != null && path != null)
                            map.put(new StorageKey(groupId, storageId), path);
                    }
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("parse(): groups={}, keys={}",
                        groups.isArray() ? groups.size() : 0, map.size());
            }
            return map;
        } catch (Exception e) {
            throw new StorageConfigException("Failed to parse storage config", e);
        }
    }

    public Optional<String> ssPath(String groupId, String storageId) {
        if (groupId == null || storageId == null) return Optional.empty();
        return Optional.ofNullable(cache.get().get(new StorageKey(groupId, storageId)));
    }

    public int size()                            { return cache.get().size(); }
    public long getVersion()                     { return version; }
    public String getCurrentFingerprint()        { return currentFingerprint; }
    public Instant getLastLoadTime()             { return lastLoadTime; }
    public Instant getLastFingerprintCheckTime() { return lastFingerprintCheckTime; }
    public String getConfigPath()                { return configPath; }

    private static String shortFp(String fp) {
        if (fp == null) return "null";
        return fp.length() > 8 ? fp.substring(0, 4) + "…" + fp.substring(fp.length() - 4) : fp;
    }
}
