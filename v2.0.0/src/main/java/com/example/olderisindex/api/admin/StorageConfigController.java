package com.example.olderisindex.api.admin;

import com.example.olderisindex.storage.StorageConfigService;
import com.example.olderisindex.storage.StorageConfigService.ClusterStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/storage-config")
public class StorageConfigController {

    private final StorageConfigService service;

    public StorageConfigController(StorageConfigService service) {
        this.service = service;
    }

    @GetMapping
    public List<ClusterStatus> all() {
        List<ClusterStatus> list = new ArrayList<>();
        for (String name : service.clusterNames()) list.add(service.status(name));
        return list;
    }

    @GetMapping("/{cluster}")
    public ResponseEntity<ClusterStatus> one(@PathVariable String cluster) {
        if (!service.clusterNames().contains(cluster)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(service.status(cluster));
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refreshAll() {
        service.refreshAll();
        return resultEnvelope();
    }

    @PostMapping("/{cluster}/refresh")
    public ResponseEntity<Map<String, Object>> refreshOne(@PathVariable String cluster) {
        if (!service.clusterNames().contains(cluster)) return ResponseEntity.notFound().build();
        long before = service.status(cluster).version;
        service.refresh(cluster);
        long after = service.status(cluster).version;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cluster", cluster);
        body.put("refreshed", after > before);
        body.put("status", service.status(cluster));
        if (after == before) {
            body.put("error", "Reload failed (version unchanged). See application.log.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> resultEnvelope() {
        List<ClusterStatus> statuses = new ArrayList<>();
        for (String name : service.clusterNames()) statuses.add(service.status(name));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clusters", statuses);
        boolean allReady = statuses.stream().allMatch(s -> s.ready);
        body.put("allReady", allReady);
        return ResponseEntity.status(allReady ? HttpStatus.OK : HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
