package com.nexzian.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nexzian.dto.AgencyRegistrationRequest;
import com.nexzian.dto.AgencyRegistrationResponse;
import com.nexzian.service.AgencyRegistrationService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
public class AgencyRegistrationController {

    private final AgencyRegistrationService agencyRegistrationService;

    public AgencyRegistrationController(
            AgencyRegistrationService agencyRegistrationService) {
        this.agencyRegistrationService = agencyRegistrationService;
    }

    // Public: register a new agency and its first OWNER account atomically.
    @PostMapping("/register-agency")
    public ResponseEntity<AgencyRegistrationResponse> registerAgency(
            @Valid @RequestBody AgencyRegistrationRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(agencyRegistrationService.registerAgency(request));
    }
}
