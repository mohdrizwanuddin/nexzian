package com.nexzian.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping
    public Map<String, String> getApplicationStatus() {

        return Map.of(
                "application", "Nexzian",
                "message", "Welcome to Nexzian Real Estate SaaS",
                "version", "1.0.0",
                "status", "RUNNING"
        );
    }
}

