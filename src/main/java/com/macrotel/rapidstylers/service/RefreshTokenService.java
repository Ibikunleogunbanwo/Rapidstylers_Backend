package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.entity.RefreshTokenEntity;
import com.macrotel.rapidstylers.repo.RefreshTokenRepo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class RefreshTokenService {

    @Value("${app.jwt.refresh-ttl-days:7}")
    private long refreshTtlDays;

    private final RefreshTokenRepo refreshTokenRepo;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public RefreshTokenService(RefreshTokenRepo refreshTokenRepo) {
        this.refreshTokenRepo = refreshTokenRepo;
    }

    /** Issue a new refresh token for an account. Returns the raw token string. */
    public String issue(String accountId, String role) {
        String familyId = UUID.randomUUID().toString();
        return createAndPersist(accountId, role, familyId);
    }

    /** Rotate: revoke old token, issue new one in same family. Returns null if the old token is invalid/revoked. */
    public String rotate(String oldRawToken) {
        String oldHash = sha256Hex(oldRawToken);
        RefreshTokenEntity oldToken = refreshTokenRepo.findByTokenHashAndRevokedFalse(oldHash).orElse(null);
        if (oldToken == null) return null;
        if (oldToken.getExpiresAt().isBefore(LocalDateTime.now())) return null;

        // Reuse detection: if we find another non-revoked token in the same family,
        // someone reused an old token after rotation — revoke the entire family.
        List<RefreshTokenEntity> family = refreshTokenRepo.findByFamilyId(oldToken.getFamilyId());
        boolean reuseDetected = false;
        for (RefreshTokenEntity t : family) {
            if (!t.getId().equals(oldToken.getId()) && !t.isRevoked() && t.getTokenHash().equals(oldHash)) {
                reuseDetected = true;
                break;
            }
        }
        if (reuseDetected) {
            revokeFamily(oldToken.getFamilyId());
            return null;
        }

        oldToken.setRevoked(true);
        refreshTokenRepo.save(oldToken);

        return createAndPersist(oldToken.getAccountId(), oldToken.getRole(), oldToken.getFamilyId());
    }

    /** Validate a refresh token. Returns the entity if valid, null otherwise. */
    public RefreshTokenEntity validate(String rawToken) {
        String hash = sha256Hex(rawToken);
        RefreshTokenEntity token = refreshTokenRepo.findByTokenHashAndRevokedFalse(hash).orElse(null);
        if (token == null) return null;
        if (token.getExpiresAt().isBefore(LocalDateTime.now())) return null;
        return token;
    }

    /** Revoke a single token. */
    public void revoke(String rawToken) {
        String hash = sha256Hex(rawToken);
        refreshTokenRepo.findByTokenHashAndRevokedFalse(hash).ifPresent(t -> {
            t.setRevoked(true);
            refreshTokenRepo.save(t);
        });
    }

    /** Revoke all tokens in a family. */
    public void revokeFamily(String familyId) {
        refreshTokenRepo.findByFamilyId(familyId).forEach(t -> {
            if (!t.isRevoked()) {
                t.setRevoked(true);
                refreshTokenRepo.save(t);
            }
        });
    }

    /** Revoke all tokens for an account (used on logout). */
    public void revokeAllForAccount(String accountId, String role) {
        refreshTokenRepo.deleteByAccountIdAndRole(accountId, role);
    }

    private String createAndPersist(String accountId, String role, String familyId) {
        String rawToken = UUID.randomUUID().toString();
        String hash = sha256Hex(rawToken);

        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.setAccountId(accountId);
        entity.setRole(role);
        entity.setTokenHash(hash);
        entity.setFamilyId(familyId);
        entity.setExpiresAt(LocalDateTime.now().plusDays(refreshTtlDays));
        refreshTokenRepo.save(entity);

        return rawToken;
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
