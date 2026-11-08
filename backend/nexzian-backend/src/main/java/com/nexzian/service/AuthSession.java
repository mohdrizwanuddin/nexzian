package com.nexzian.service;

import com.nexzian.dto.LoginResponse;

public record AuthSession(LoginResponse response, String refreshToken) { }
