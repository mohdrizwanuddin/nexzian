package com.nexzian.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nexzian.dto.AgencyRegistrationRequest;
import com.nexzian.dto.AgencyRegistrationResponse;
import com.nexzian.security.ClientIp;
import com.nexzian.security.RegistrationRateLimiter;
import com.nexzian.service.AgencyRegistrationService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
public class AgencyRegistrationController {

    private final AgencyRegistrationService agencyRegistrationService;
    private final RegistrationRateLimiter registrationRateLimiter;

    public AgencyRegistrationController(
            AgencyRegistrationService agencyRegistrationService,
            RegistrationRateLimiter registrationRateLimiter) {
        this.agencyRegistrationService = agencyRegistrationService;
        this.registrationRateLimiter = registrationRateLimiter;
    }

    // Public: register a new agency and its first OWNER account atomically.
    @PostMapping("/register-agency")
    public ResponseEntity<AgencyRegistrationResponse> registerAgency(
            @Valid @RequestBody AgencyRegistrationRequest request,
            HttpServletRequest httpRequest) {

        // 5 attempts per client IP per hour. Requests that fail bean validation (400)
        // never reach this line and are not counted.
        registrationRateLimiter.checkAndRecord(ClientIp.of(httpRequest));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(agencyRegistrationService.registerAgency(request));
    }
}
