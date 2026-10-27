package com.example.olderisindex.api;

import com.example.olderisindex.api.error.ResearchDatabaseException;
import com.example.olderisindex.api.error.ResearchTimeoutException;
import com.example.olderisindex.api.error.StudyNotFoundException;
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
import java.util.HashSet;
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

    private final StudyRepository repo;
    private final StorageConfigService storageConfig;
    private final ThreadPoolTaskExecutor executor;
    private final long dbTimeoutSeconds;

    public ResearchService(StudyRepository repo, StorageConfigService storageConfig,
                           @Qualifier("dbExecutor") ThreadPoolTaskExecutor executor,
                           @Value("${app.research.db-timeout-seconds:20}") long dbTimeoutSeconds) {
        this.repo = repo; this.storageConfig = storageConfig;
        this.executor = executor; this.dbTimeoutSeconds = dbTimeoutSeconds;
    }

    @Cacheable(value = "studies",
               key = "(#req.an != null ? #req.an : '') + '|' + (#req.suid != null ? #req.suid : '')")
    public ResearchResponse process(ResearchRequest req) {
        String an = req.getAn();
        String suid = req.getSuid();
        long t0 = System.nanoTime();

        log.info("research miss: {}", criteria(an, suid));
        if (log.isDebugEnabled()) {
            log.debug("process(): AN='{}', SUID='{}', cacheKey='{}'",
                    an, suid, (an != null ? an : "") + "|" + (suid != null ? suid : ""));
        }
        List<StudyRow> rows;
        try {
            rows = CompletableFuture
                    .supplyAsync(() -> fetchAndMaybeIntersect(an, suid), executor)
                    .get(dbTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            if (log.isDebugEnabled()) log.debug("process(): DB timeout {}s for {}", dbTimeoutSeconds, criteria(an, suid));
            throw new ResearchTimeoutException(an, suid, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResearchDatabaseException(an, suid, e);
        } catch (ExecutionException e) {
            if (log.isDebugEnabled()) log.debug("process(): DB error for {}: {}", criteria(an, suid), e.getCause().getMessage());
            throw new ResearchDatabaseException(an, suid, e.getCause());
        }

        List<StudyResult> studies = buildStudies(rows);
        if (studies.isEmpty()) {
            if (log.isDebugEnabled())
                log.debug("process(): empty result for {} ({} мс)",
                        criteria(an, suid), (System.nanoTime() - t0) / 1_000_000);
            throw new StudyNotFoundException(an, suid);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info("research OK: {}, studies={}, {} мс", criteria(an, suid), studies.size(), ms);
        return ResearchResponse.ok(an, suid, studies);
    }

    private List<StudyRow> fetchAndMaybeIntersect(String an, String suid) {
        boolean hasAn = an != null && !an.isBlank();
        boolean hasSuid = suid != null && !suid.isBlank();
        if (hasAn && hasSuid) {
            List<StudyRow> byAn = repo.findByAn(an);
            List<StudyRow> bySuid = repo.findBySuid(suid);
            if (log.isDebugEnabled())
                log.debug("fetchAndMaybeIntersect(): byAn={} rows, bySuid={} rows", byAn.size(), bySuid.size());
            return intersect(byAn, bySuid);
        }
        if (hasAn) return repo.findByAn(an);
        return repo.findBySuid(suid);
    }

    private List<StudyRow> intersect(List<StudyRow> byAn, List<StudyRow> bySuid) {
        Set<String> suidKeys = new HashSet<>();
        for (StudyRow row : bySuid) {
            Optional<String> fp = fullPath(row);
            if (fp.isPresent()) suidKeys.add(key(row.fileName(), fp.get()));
        }
        if (log.isDebugEnabled()) log.debug("intersect(): keys in bySuid = {}", suidKeys.size());
        if (suidKeys.isEmpty()) return List.of();
        List<StudyRow> result = new ArrayList<>();
        for (StudyRow row : byAn) {
            Optional<String> fp = fullPath(row);
            if (fp.isPresent() && suidKeys.contains(key(row.fileName(), fp.get()))) result.add(row);
        }
        if (log.isDebugEnabled()) log.debug("intersect(): result = {} rows", result.size());
        return result;
    }

    private List<StudyResult> buildStudies(List<StudyRow> rows) {
        Map<String, List<StudyRow>> byStudy = new LinkedHashMap<>();
        for (StudyRow r : rows) byStudy.computeIfAbsent(r.studyIuid(), k -> new ArrayList<>()).add(r);

        List<StudyResult> result = new ArrayList<>();
        int droppedRows = 0;
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
                    Optional<String> fp = fullPath(row);
                    if (fp.isEmpty()) {
                        droppedRows++;
                        if (log.isDebugEnabled())
                            log.debug("buildStudies(): row dropped — no ss_path for ({}, {})",
                                    row.storageGroupId(), row.storageId());
                        continue;
                    }
                    if (seen.add(key(row.fileName(), fp.get())))
                        instances.add(new InstanceResult(row.fileName(), fp.get()));
                }
                if (!instances.isEmpty())
                    seriesList.add(new SeriesResult(seriesEntry.getKey(), modality, instances));
            }
            if (!seriesList.isEmpty())
                result.add(new StudyResult(studyEntry.getKey(), seriesList));
        }
        if (log.isDebugEnabled()) {
            int studies = result.size();
            int series = result.stream().mapToInt(s -> s.series().size()).sum();
            int instances = result.stream().flatMap(s -> s.series().stream())
                    .mapToInt(ss -> ss.instances().size()).sum();
            log.debug("buildStudies(): rows={}, studies={}, series={}, instances={}, dropped={}",
                    rows.size(), studies, series, instances, droppedRows);
        }
        return result;
    }

    private Optional<String> fullPath(StudyRow row) {
        Optional<String> ssPath = storageConfig.ssPath(row.storageGroupId(), row.storageId());
        if (ssPath.isEmpty()) return Optional.empty();
        String joined = ssPath.get() + "/" + row.storagePath().replace('/', '\\');
        if (log.isDebugEnabled())
            log.debug("resolve(): ({}, {}) → {}", row.storageGroupId(), row.storageId(), ssPath.get());
        return Optional.of(joined);
    }

    private static String key(String fileName, String fullPath) { return fileName + "|" + fullPath; }
    private static String criteria(String an, String suid) {
        if (an != null && suid != null) return "AN=" + an + ", SUID=" + suid;
        return an != null ? "AN=" + an : "SUID=" + suid;
    }
}
