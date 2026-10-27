package com.example.olderisindex.api.admin;

import com.example.olderisindex.storage.StorageConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/storage-config")
public class StorageConfigController {
    private final StorageConfigService service;
    public StorageConfigController(StorageConfigService service) { this.service = service; }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh() {
        long before = service.getVersion();
        service.refresh();
        long after = service.getVersion();
        Map<String, Object> body = state();
        if (after == before) {
            body.put("error", "Full reload failed (keeping version " + before + "). См. application.log.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
        return ResponseEntity.ok(body);
    }
    @GetMapping
    public ResponseEntity<Map<String, Object>> get() { return ResponseEntity.ok(state()); }

    private Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("size", service.size());
        m.put("version", service.getVersion());
        m.put("lastLoadTime", service.getLastLoadTime());
        m.put("path", service.getConfigPath());
        m.put("fingerprint", service.getCurrentFingerprint());
        m.put("lastFingerprintCheckTime", service.getLastFingerprintCheckTime());
        return m;
    }
}
