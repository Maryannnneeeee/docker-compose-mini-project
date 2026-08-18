package com.fx.orchestrator.ops;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness check, so compose can gate other services on this one being up. */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "fx-orchestrator");
    }
}
