
package com.nexzian.security;

import java.security.Key;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.nexzian.entity.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiration-ms}")
    private long jwtExpirationMs;

    // Generate signing key from Base64 secret
    private Key getSigningKey() {

        byte[] keyBytes = Decoders.BASE64.decode(jwtSecret);

        SecretKey key = Keys.hmacShaKeyFor(keyBytes);

        return key;
    }

    // Generate JWT token after successful login
    public String generateToken(User user) {

        Date issuedAt = new Date();

        Date expiration = new Date(
                issuedAt.getTime() + jwtExpirationMs);

        return Jwts.builder()
                .subject(user.getEmail())
                .claim("tenantId", user.getTenantId().toString())
                .claim("role", user.getRole())
                .issuedAt(issuedAt)
                .expiration(expiration)
                .signWith(getSigningKey())
                .compact();
    }

    // Extract email from JWT token
    public String extractUsername(String token) {

        return extractAllClaims(token).getSubject();
    }

    // Validate JWT token against email
    public boolean isTokenValidForEmail(
            String token, String email) {

        Claims claims = extractAllClaims(token);

        return email.equals(claims.getSubject())
                && claims.getExpiration().after(new Date());
    }

    // Extract all claims after verifying token signature
    private Claims extractAllClaims(String token) {

        return Jwts.parser()
                .verifyWith((SecretKey) getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
