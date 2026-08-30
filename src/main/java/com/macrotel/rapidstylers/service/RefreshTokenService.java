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

    /**
     * Rotate: revoke old token, issue new one in same family. Returns null if the
     * old token is invalid/revoked or reuse/theft is detected.
     *
     * Theft detection: a token hash can only belong to one row (unique), so a
     * rotated-out token can no longer be found as "active" when replayed. Presenting
     * a known-but-already-revoked token is the classic stolen-session signal, so we
     * burn the entire family rather than just rejecting it. We also burn the family
     * if a rotation ever races such that two live tokens coexist in one family.
     */
    public String rotate(String oldRawToken) {
        String oldHash = sha256Hex(oldRawToken);
        RefreshTokenEntity active = refreshTokenRepo.findByTokenHashAndRevokedFalse(oldHash).orElse(null);
        if (active == null) {
            // A revoked/rotated token is being replayed — suspected theft. Revoke the
            // whole family so the legitimate session is forced to re-authenticate too.
            refreshTokenRepo.findByTokenHash(oldHash).ifPresent(t -> revokeFamily(t.getFamilyId()));
            return null;
        }
        if (active.getExpiresAt().isBefore(LocalDateTime.now())) return null;

        // Invariant: one live token per family. If a second active sibling exists,
        // rotation raced/duplicated — revoke the entire family.
        List<RefreshTokenEntity> family = refreshTokenRepo.findByFamilyId(active.getFamilyId());
        boolean siblingActive = family.stream()
                .anyMatch(t -> !t.getId().equals(active.getId()) && !t.isRevoked());
        if (siblingActive) {
            revokeFamily(active.getFamilyId());
            return null;
        }

        active.setRevoked(true);
        refreshTokenRepo.save(active);
        return createAndPersist(active.getAccountId(), active.getRole(), active.getFamilyId());
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
