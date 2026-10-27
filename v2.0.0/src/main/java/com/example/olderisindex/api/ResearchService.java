package com.example.olderisindex.api;

import com.example.olderisindex.api.error.ResearchDatabaseException;
import com.example.olderisindex.api.error.ResearchTimeoutException;
import com.example.olderisindex.api.error.StudyNotFoundException;
import com.example.olderisindex.config.ClusterRegistry;
import com.example.olderisindex.config.ClusterRegistry.ClusterContext;
import com.example.olderisindex.data.StudyRepository;
import com.example.olderisindex.data.StudyRow;
import com.example.olderisindex.storage.StorageConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class ResearchService {

    private static final Logger log = LoggerFactory.getLogger(ResearchService.class);

    private final ClusterRegistry registry;
    private final StudyRepository repo;
    private final StorageConfigService storageConfig;
    private final ThreadPoolTaskExecutor executor;
    private final long dbTimeoutSeconds;

    public ResearchService(ClusterRegistry registry,
                           StudyRepository repo,
                           StorageConfigService storageConfig,
                           @Qualifier("dbExecutor") ThreadPoolTaskExecutor executor,
                           @Value("${app.research.db-timeout-seconds:20}") long dbTimeoutSeconds) {
        this.registry = registry;
        this.repo = repo;
        this.storageConfig = storageConfig;
        this.executor = executor;
        this.dbTimeoutSeconds = dbTimeoutSeconds;
    }

    @Cacheable(value = "studies",
               key = "(#req.an != null ? #req.an : '') + '|' + (#req.suid != null ? #req.suid : '')")
    public ResearchResponse process(ResearchRequest req) {
        String an   = req.getAn();
        String suid = req.getSuid();
        long t0 = System.nanoTime();
        log.info("research miss: {}", criteria(an, suid));

        Map<String, ClusterResult> results = callAllClusters(an, suid);

        List<String> okClusters = new ArrayList<>();
        List<String> failedClusters = new ArrayList<>();
        List<Throwable> causes = new ArrayList<>();
        for (var e : results.entrySet()) {
            if (e.getValue().ok()) okClusters.add(e.getKey());
            else { failedClusters.add(e.getKey()); causes.add(e.getValue().cause()); }
        }

        if (okClusters.isEmpty()) {
            boolean allTimeouts = !causes.isEmpty()
                    && causes.stream().allMatch(c -> c instanceof TimeoutException);
            String msg = "All clusters failed for " + criteria(an, suid)
                    + ", clusters=" + failedClusters + ", causes=" + summarizeCauses(causes);
            log.error("research FAILED: " + msg);
            if (allTimeouts) throw new ResearchTimeoutException(an, suid, causes.get(0));
            throw new ResearchDatabaseException(an, suid, causes.get(0));
        }

        if (!failedClusters.isEmpty()) {
            log.warn("research PARTIAL: {}, clusters_ok={}, clusters_failed={}, causes={}. "
                    + "Результат может быть неполным: в недоступном кластере могли быть "
                    + "дополнительные серии/инстансы для этого исследования.",
                    criteria(an, suid), okClusters, failedClusters, summarizeCauses(causes));
        }

        List<StudyRow> merged = merge(results, an, suid);
        List<StudyResult> studies = buildStudies(merged);

        if (studies.isEmpty()) {
            if (failedClusters.isEmpty()) {
                log.info("research NOT_FOUND: {}, clusters_ok={}", criteria(an, suid), okClusters);
            } else {
                log.warn("research NOT_FOUND (partial): {}, clusters_ok={}, clusters_failed={}. "
                        + "Исследование может существовать в недоступном кластере.",
                        criteria(an, suid), okClusters, failedClusters);
            }
            throw new StudyNotFoundException(an, suid);
        }

        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info("research OK: {}, studies={}, clusters_ok={}, clusters_failed={}, {} мс",
                criteria(an, suid), studies.size(), okClusters, failedClusters, ms);
        return ResearchResponse.ok(an, suid, studies);
    }

    private record ClusterResult(
            String cluster,
            boolean ok,
            List<StudyRow> byAn,
            List<StudyRow> bySuid,
            Throwable cause
    ) {
        static ClusterResult ok(String c, List<StudyRow> byAn, List<StudyRow> bySuid) {
            return new ClusterResult(c, true, byAn, bySuid, null);
        }
        static ClusterResult failed(String c, Throwable t) {
            return new ClusterResult(c, false, null, null, t);
        }
    }

    private Map<String, ClusterResult> callAllClusters(String an, String suid) {
        boolean hasAn   = an   != null && !an.isBlank();
        boolean hasSuid = suid != null && !suid.isBlank();

        Map<String, CompletableFuture<ClusterResult>> futures = new LinkedHashMap<>();
        for (ClusterContext ctx : registry.all()) {
            futures.put(ctx.name(), CompletableFuture.supplyAsync(() -> {
                try {
                    List<StudyRow> byAn   = hasAn   ? repo.findByAn(ctx, an)     : null;
                    List<StudyRow> bySuid = hasSuid ? repo.findBySuid(ctx, suid) : null;
                    if (log.isDebugEnabled()) {
                        log.debug("callAllClusters(): cluster={} ok, byAn={}, bySuid={}",
                                ctx.name(),
                                byAn   == null ? "-" : byAn.size(),
                                bySuid == null ? "-" : bySuid.size());
                    }
                    return ClusterResult.ok(ctx.name(), byAn, bySuid);
                } catch (RuntimeException e) {
                    if (log.isDebugEnabled()) {
                        log.debug("callAllClusters(): cluster={} failed: {}", ctx.name(), e.getMessage());
                    }
                    return ClusterResult.failed(ctx.name(), e);
                }
            }, executor));
        }

        Map<String, ClusterResult> results = new LinkedHashMap<>();
        long deadline = System.currentTimeMillis() + dbTimeoutSeconds * 1000;
        for (var e : futures.entrySet()) {
            long remaining = Math.max(1, deadline - System.currentTimeMillis());
            try {
                results.put(e.getKey(), e.getValue().get(remaining, TimeUnit.MILLISECONDS));
            } catch (TimeoutException ex) {
                results.put(e.getKey(), ClusterResult.failed(e.getKey(), ex));
            } catch (ExecutionException ex) {
                results.put(e.getKey(), ClusterResult.failed(e.getKey(),
                        ex.getCause() != null ? ex.getCause() : ex));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                results.put(e.getKey(), ClusterResult.failed(e.getKey(), ex));
            }
        }
        return results;
    }

    private List<StudyRow> merge(Map<String, ClusterResult> results, String an, String suid) {
        boolean hasAn   = an   != null && !an.isBlank();
        boolean hasSuid = suid != null && !suid.isBlank();

        if (hasAn && hasSuid) {
            Map<String, StudyRow> unionAn   = new LinkedHashMap<>();
            Map<String, StudyRow> unionSuid = new LinkedHashMap<>();
            for (ClusterResult r : results.values()) {
                if (!r.ok()) continue;
                if (r.byAn()   != null) for (StudyRow row : r.byAn())   unionAn.putIfAbsent(row.fileName(), row);
                if (r.bySuid() != null) for (StudyRow row : r.bySuid()) unionSuid.putIfAbsent(row.fileName(), row);
            }
            List<StudyRow> out = new ArrayList<>();
            for (var e : unionAn.entrySet()) {
                if (unionSuid.containsKey(e.getKey())) out.add(e.getValue());
            }
            return out;
        }

        Map<String, StudyRow> union = new LinkedHashMap<>();
        for (ClusterResult r : results.values()) {
            if (!r.ok()) continue;
            List<StudyRow> rows = hasAn ? r.byAn() : r.bySuid();
            if (rows == null) continue;
            for (StudyRow row : rows) union.putIfAbsent(row.fileName(), row);
        }
        return new ArrayList<>(union.values());
    }

    private List<StudyResult> buildStudies(List<StudyRow> rows) {
        Map<String, List<StudyRow>> byStudy = new LinkedHashMap<>();
        for (StudyRow r : rows) byStudy.computeIfAbsent(r.studyIuid(), k -> new ArrayList<>()).add(r);

        List<StudyResult> result = new ArrayList<>();
        int dropped = 0;

        for (var studyEntry : byStudy.entrySet()) {
            Map<String, List<StudyRow>> bySeries = new LinkedHashMap<>();
            for (StudyRow r : studyEntry.getValue())
                bySeries.computeIfAbsent(r.seriesIuid(), k -> new ArrayList<>()).add(r);

            List<SeriesResult> seriesList = new ArrayList<>();
            for (var seriesEntry : bySeries.entrySet()) {
                String modality = seriesEntry.getValue().get(0).modality();
                Set<String> seen = new LinkedHashSet<>();
                List<InstanceResult> instances = new ArrayList<>();
                for (StudyRow row : seriesEntry.getValue()) {
                    if (!seen.add(row.fileName())) continue;
                    Optional<String> fp = fullPath(row);
                    if (fp.isEmpty()) { dropped++; continue; }
                    instances.add(new InstanceResult(row.fileName(), fp.get()));
                }
                if (!instances.isEmpty())
                    seriesList.add(new SeriesResult(seriesEntry.getKey(), modality, instances));
            }
            if (!seriesList.isEmpty())
                result.add(new StudyResult(studyEntry.getKey(), seriesList));
        }

        if (log.isDebugEnabled()) {
            int studies   = result.size();
            int series    = result.stream().mapToInt(s -> s.series().size()).sum();
            int instances = result.stream().flatMap(s -> s.series().stream())
                    .mapToInt(ss -> ss.instances().size()).sum();
            log.debug("buildStudies(): rows={}, studies={}, series={}, instances={}, dropped={}",
                    rows.size(), studies, series, instances, dropped);
        }
        return result;
    }

    private Optional<String> fullPath(StudyRow row) {
        Optional<String> ssPath = storageConfig.ssPath(row.cluster(), row.storageGroupId(), row.storageId());
        if (ssPath.isEmpty()) return Optional.empty();
        return Optional.of(ssPath.get() + "/" + row.storagePath().replace('/', '\\'));
    }

    private static String criteria(String an, String suid) {
        if (an != null && suid != null) return "AN=" + an + ", SUID=" + suid;
        return an != null ? "AN=" + an : "SUID=" + suid;
    }

    private static String summarizeCauses(List<Throwable> causes) {
        if (causes.isEmpty()) return "[]";
        return causes.stream()
                .map(c -> c == null ? "null" : c.getClass().getSimpleName() + ": " + c.getMessage())
                .toList().toString();
    }
}
