package com.nexzian.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.nexzian.entity.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, java.util.UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
}
