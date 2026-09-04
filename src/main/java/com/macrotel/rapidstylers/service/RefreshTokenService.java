package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.entity.RefreshTokenEntity;
import com.macrotel.rapidstylers.repo.RefreshTokenRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RefreshTokenService {

    @Value("${app.jwt.refresh-ttl-days:7}")
    private long refreshTtlDays;

    @Value("${app.security.refresh-token-max-live-per-account:10}")
    private int maxLiveRefreshTokensPerAccount;

    private static final Logger LOG = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepo refreshTokenRepo;

    // Optional (may be null in unit tests): anchors the absolute session cap to the
    // moment a fresh login issues its refresh-token family.
    @Autowired(required = false)
    private SessionActivityService sessionActivityService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public RefreshTokenService(RefreshTokenRepo refreshTokenRepo) {
        this.refreshTokenRepo = refreshTokenRepo;
    }

    /** Issue a new refresh token for an account. Returns the raw token string. */
    public String issue(String accountId, String role) {
        String familyId = UUID.randomUUID().toString();
        String raw = createAndPersist(accountId, role, familyId);
        // A fresh family is a fresh login — record its start so the absolute
        // session cap (e.g. 8h for admins) has an anchor. Best-effort.
        if (sessionActivityService != null) {
            sessionActivityService.markLogin(accountId, role);
        }
        return raw;
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

    /**
     * Theft response for the refresh endpoint. A refresh token whose hash is
     * known but already revoked (rotated out or logged out) should never be
     * presented again — replaying one is the classic stolen-session signal, so
     * the entire family is burned to force the legitimate session to
     * re-authenticate too. Tokens that are unknown, or that merely expired
     * without ever being revoked, get no burn: they are plain rejections, not
     * theft evidence.
     *
     * @return true if a family was burned (the token was a known revoked token)
     */
    public boolean burnFamilyForReplayedToken(String rawToken) {
        String hash = sha256Hex(rawToken);
        RefreshTokenEntity known = refreshTokenRepo.findByTokenHash(hash).orElse(null);
        if (known != null && known.isRevoked()) {
            revokeFamily(known.getFamilyId());
            return true;
        }
        return false;
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

    /**
     * Periodic refresh-token hygiene:
     *
     *  1. Deletes every expired row (revoked or not) — an expired token can never
     *     validate again, and deleting it bounds table growth to roughly one refresh
     *     TTL of rotation/logout history.
     *  2. Caps live (unexpired, unrevoked) tokens per account. Every rotation
     *     replaces the live row with a newer one, so a genuinely active session
     *     always owns a recent row — a live row older than the account's newest
     *     {@code maxLivePerAccount} is an abandoned login (e.g. fresh sign-ins that
     *     were never logged out, which is how dozens of unrevoked admin tokens
     *     accumulate). Surplus rows are revoked so those stale sessions must
     *     re-authenticate, but kept as revoked replay-evidence until they expire
     *     naturally.
     *
     * @return {@code {deletedExpired, revokedSurplus}} for callers/tests
     */
    @Transactional
    public long[] housekeep(int maxLivePerAccount) {
        LocalDateTime now = LocalDateTime.now();
        long deletedExpired = refreshTokenRepo.deleteByExpiresAtBefore(now);

        long revokedSurplus = 0;
        Map<String, List<RefreshTokenEntity>> liveByAccount = refreshTokenRepo.findByRevokedFalse().stream()
                .collect(Collectors.groupingBy(RefreshTokenEntity::getAccountId));
        for (List<RefreshTokenEntity> accountRows : liveByAccount.values()) {
            if (accountRows.size() <= maxLivePerAccount) {
                continue;
            }
            // Oldest first, so the revoked surplus is the least-recently issued.
            accountRows.sort(Comparator.comparingLong(RefreshTokenEntity::getId));
            int surplus = accountRows.size() - maxLivePerAccount;
            for (int i = 0; i < surplus; i++) {
                RefreshTokenEntity stale = accountRows.get(i);
                stale.setRevoked(true);
                refreshTokenRepo.save(stale);
            }
            revokedSurplus += surplus;
        }

        if (deletedExpired > 0 || revokedSurplus > 0) {
            LOG.info("Refresh-token housekeeping: deleted {} expired, revoked {} surplus live tokens (cap {}/account)",
                    deletedExpired, revokedSurplus, maxLivePerAccount);
        }
        return new long[]{deletedExpired, revokedSurplus};
    }

    /**
     * Scheduled wrapper — deliberately never fires at boot: {@code initialDelay}
     * matches the interval, so the first run happens one full interval after
     * startup (never during short-lived {@code @SpringBootTest} contexts, which
     * share the dev database), then every interval thereafter.
     */
    @Scheduled(
            fixedDelayString = "${app.security.refresh-token-housekeep-delay-ms:3600000}",
            initialDelayString = "${app.security.refresh-token-housekeep-delay-ms:3600000}")
    public void scheduledHousekeeping() {
        housekeep(maxLiveRefreshTokensPerAccount);
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
