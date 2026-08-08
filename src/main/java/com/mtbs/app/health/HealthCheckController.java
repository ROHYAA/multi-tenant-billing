package com.mtbs.app.health;

import com.mtbs.shared.dto.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/${api.version}/health")
@Tag(name = "Health", description = "Health check endpoint")
public class HealthCheckController {

    @GetMapping
    @Operation(summary = "Health check", description = "Returns the health status of the application.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> healthCheck() {
        Map<String, Object> health = Map.of(
                "status", "UP",
                "service", "Multi-Tenant Billing System",
                "version", "1.0.0",
                "timestamp", Instant.now().toString());
        return ResponseEntity.ok(ApiResponse.success(health));
    }
}
