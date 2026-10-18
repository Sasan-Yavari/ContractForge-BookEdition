package io.contractforge.backend.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/v1/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
