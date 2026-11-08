package com.nexzian.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nexzian.dto.AdminAgencyResponse;
import com.nexzian.service.PlatformAdminService;

@RestController
@RequestMapping("/api/v1/admin")
public class PlatformAdminController {
    private final PlatformAdminService platformAdminService;
    public PlatformAdminController(PlatformAdminService platformAdminService) { this.platformAdminService = platformAdminService; }
    @GetMapping("/agencies")
    public ResponseEntity<List<AdminAgencyResponse>> getAgencies() {
        return ResponseEntity.ok(platformAdminService.getAgencies());
    }
}
