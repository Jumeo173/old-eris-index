package com.example.olderisindex.api;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/research")
public class ResearchController {
    private static final Logger log = LoggerFactory.getLogger(ResearchController.class);
    private final ResearchService service;
    public ResearchController(ResearchService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ResearchResponse> process(@Valid @RequestBody ResearchRequest req) {
        if (log.isDebugEnabled())
            log.debug("POST /api/v1/research: an='{}', suid='{}'", req.getAn(), req.getSuid());
        return ResponseEntity.ok(service.process(req));
    }
}
