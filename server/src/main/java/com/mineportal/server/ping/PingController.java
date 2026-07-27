package com.mineportal.server.ping;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class PingController {

    private final PingService pingService;

    public PingController(PingService pingService) {
        this.pingService = pingService;
    }

    @GetMapping("/api/ping")
    public ResponseEntity<?> ping(@RequestParam String host, @RequestParam int port) {
        if (host.isBlank() || port < 1 || port > 65535) {
            return ResponseEntity.badRequest().body(Map.of("error", "host and a valid port are required"));
        }
        if (pingService.isPrivateHost(host)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "cannot ping private/internal hosts"));
        }
        return ResponseEntity.ok(pingService.ping(host, port));
    }

}
