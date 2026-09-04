package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.entity.RefreshTokenEntity;
import com.macrotel.rapidstylers.repo.RefreshTokenRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the refresh-token lifecycle that backs logout/revocation
 * and theft detection. A small in-memory store stands in for the JPA repo so
 * the reuse-detection and family-revocation paths run against real mutations.
 */
class RefreshTokenServiceTest {

    private RefreshTokenRepo repo;
    private RefreshTokenService service;
    private List<RefreshTokenEntity> store;
    private AtomicLong seq;

    @BeforeEach
    void setUp() {
        repo = mock(RefreshTokenRepo.class);
        store = new ArrayList<>();
        seq = new AtomicLong(1);

        when(repo.save(any(RefreshTokenEntity.class))).thenAnswer(inv -> {
            RefreshTokenEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(seq.getAndIncrement());
            }
            store.removeIf(t -> t.getId().equals(e.getId()));
            store.add(e);
            return e;
        });
        when(repo.findByTokenHashAndRevokedFalse(anyString())).thenAnswer(inv -> {
            String hash = inv.getArgument(0);
            return store.stream()
                    .filter(t -> !t.isRevoked() && hash.equals(t.getTokenHash()))
                    .findFirst();
        });
        when(repo.findByTokenHash(anyString())).thenAnswer(inv -> {
            String hash = inv.getArgument(0);
            return store.stream().filter(t -> hash.equals(t.getTokenHash())).findFirst();
        });
        when(repo.findByFamilyId(anyString())).thenAnswer(inv -> {
            String family = inv.getArgument(0);
            return store.stream().filter(t -> family.equals(t.getFamilyId())).toList();
        });
        doAnswer(inv -> {
            String accountId = inv.getArgument(0);
            String role = inv.getArgument(1);
            store.removeIf(t -> accountId.equals(t.getAccountId()) && role.equals(t.getRole()));
            return null;
        }).when(repo).deleteByAccountIdAndRole(anyString(), anyString());
        when(repo.findByRevokedFalse()).thenAnswer(inv ->
                store.stream().filter(t -> !t.isRevoked()).toList());
        doAnswer(inv -> {
            LocalDateTime cutoff = inv.getArgument(0);
            long removed = store.stream().filter(t -> t.getExpiresAt().isBefore(cutoff)).count();
            store.removeIf(t -> t.getExpiresAt().isBefore(cutoff));
            return removed;
        }).when(repo).deleteByExpiresAtBefore(any(LocalDateTime.class));

        service = new RefreshTokenService(repo);
        ReflectionTestUtils.setField(service, "refreshTtlDays", 7L);
    }

    @Test
    void issuePersistsARawTokenThatValidates() {
        String raw = service.issue("ACCOUNT-1", "CUSTOMER");

        assertNotNull(raw);
        assertFalse(raw.isBlank());
        assertEquals(1, store.size(), "one token row persisted");
        RefreshTokenEntity persisted = store.get(0);
        assertEquals("ACCOUNT-1", persisted.getAccountId());
        assertEquals("CUSTOMER", persisted.getRole());
        assertFalse(persisted.isRevoked());
        // Raw token is stored only as a SHA-256 hash, never in plaintext.
        assertFalse(persisted.getTokenHash().equals(raw));

        RefreshTokenEntity validated = service.validate(raw);
        assertNotNull(validated, "issued token must validate");
    }

    @Test
    void rotateRevokesOldTokenInSameFamily() {
        String first = service.issue("ACCOUNT-1", "CUSTOMER");
        String familyBefore = store.get(0).getFamilyId();

        String second = service.rotate(first);

        assertNotNull(second, "rotation must succeed");
        assertFalse(second.equals(first), "rotated token must differ from the old one");
        assertEquals(2, store.size());
        RefreshTokenEntity old = store.stream()
                .filter(t -> t.getTokenHash().equals(hash(first))).findFirst().orElseThrow();
        assertTrue(old.isRevoked(), "old token must be revoked after rotation");
        // Same family, so the session stays continuous.
        assertEquals(familyBefore, store.stream().filter(t -> !t.isRevoked()).findFirst().orElseThrow().getFamilyId());
        // Old token no longer validates; new one does.
        assertNull(service.validate(first), "revoked old token must not validate");
        assertNotNull(service.validate(second), "new token must validate");
    }

    @Test
    void rotatingAnAlreadyRevokedTokenFails() {
        String first = service.issue("ACCOUNT-1", "CUSTOMER");
        service.rotate(first); // revokes `first`

        assertNull(service.rotate(first), "re-rotating a revoked token must fail");
    }

    @Test
    void reusingAnOldTokenAfterRotationRevokesTheWholeFamily() {
        String first = service.issue("ACCOUNT-1", "CUSTOMER");
        String second = service.rotate(first); // `first` now revoked, `second` active
        assertNotNull(second);

        // An attacker replays the already-consumed `first` while `second` is still active.
        String replay = service.rotate(first);

        assertNull(replay, "reuse of a rotated-out token must be refused");
        assertTrue(store.stream().allMatch(RefreshTokenEntity::isRevoked),
                "reuse detection must revoke the entire family (theft response)");
    }

    @Test
    void replayedRevokedTokenBurnsTheWholeFamily() {
        String first = service.issue("ACCOUNT-1", "CUSTOMER");
        String second = service.rotate(first); // `first` now revoked, `second` active
        assertNotNull(second);

        // The refresh endpoint calls this after validate() rejects a revoked token.
        boolean burned = service.burnFamilyForReplayedToken(first);

        assertTrue(burned, "replay of a known revoked token must report a family burn");
        assertTrue(store.stream().allMatch(RefreshTokenEntity::isRevoked),
                "replayed revoked token must revoke the entire family (theft response)");
        assertNull(service.validate(second), "the legitimately rotated token must die with the family");
    }

    @Test
    void burnFamilyForReplayedTokenIgnoresUnknownTokens() {
        service.issue("ACCOUNT-1", "CUSTOMER");

        boolean burned = service.burnFamilyForReplayedToken("never-issued-token");

        assertFalse(burned, "an unknown token is a plain rejection, not theft evidence");
        assertTrue(store.stream().noneMatch(RefreshTokenEntity::isRevoked),
                "an unknown token must not revoke anything");
    }

    @Test
    void burnFamilyForReplayedTokenDoesNotBurnAMerelyExpiredToken() {
        String raw = service.issue("ACCOUNT-1", "CUSTOMER");
        store.get(0).setExpiresAt(LocalDateTime.now().minusMinutes(1)); // expired, never revoked

        boolean burned = service.burnFamilyForReplayedToken(raw);

        assertFalse(burned, "an expired-but-never-revoked token is a stale session, not a replay");
        assertFalse(store.get(0).isRevoked(), "the expired token must not be marked revoked");
    }

    @Test
    void revokeKillsTheTokenSoItNoLongerValidates() {
        String raw = service.issue("ACCOUNT-1", "CUSTOMER");

        service.revoke(raw);

        assertNull(service.validate(raw), "revoked token must not validate");
    }

    @Test
    void expiredTokenDoesNotValidate() {
        String raw = service.issue("ACCOUNT-1", "CUSTOMER");
        store.get(0).setExpiresAt(LocalDateTime.now().minusMinutes(1));

        assertNull(service.validate(raw), "expired token must be rejected");
    }

    @Test
    void logoutRevokesAllTokensForTheAccount() {
        service.issue("ACCOUNT-1", "CUSTOMER");
        service.issue("ACCOUNT-1", "CUSTOMER");

        service.revokeAllForAccount("ACCOUNT-1", "CUSTOMER");

        assertTrue(store.isEmpty(), "logout must clear every refresh token for the account");
    }

    @Test
    void housekeepDeletesExpiredTokens() {
        service.issue("ACCOUNT-1", "CUSTOMER");
        service.issue("ACCOUNT-2", "STYLER");
        LocalDateTime past = LocalDateTime.now().minusDays(8); // past the 7-day TTL
        store.forEach(t -> t.setExpiresAt(past));

        long[] result = service.housekeep(10);

        assertEquals(2L, result[0], "every expired row (revoked or not) must be deleted");
        assertEquals(0L, result[1]);
        assertTrue(store.isEmpty(), "expired tokens must be purged from the table");
    }

    @Test
    void housekeepRevokesLiveTokensBeyondTheCapKeepingTheNewest() {
        String oldest = service.issue("ACCOUNT-1", "CUSTOMER");
        String middle = service.issue("ACCOUNT-1", "CUSTOMER");
        String newest = service.issue("ACCOUNT-1", "CUSTOMER");

        long[] result = service.housekeep(1); // keep only the single newest live token

        assertEquals(0L, result[0]);
        assertEquals(2L, result[1], "two surplus live tokens must be revoked");
        assertTrue(row(oldest).isRevoked(), "oldest live token must be revoked");
        assertTrue(row(middle).isRevoked(), "second-oldest live token must be revoked");
        assertFalse(row(newest).isRevoked(), "the newest live token must be kept");
        assertNull(service.validate(oldest), "revoked surplus must stop refreshing");
        assertNotNull(service.validate(newest), "the kept token must stay usable");
    }

    @Test
    void housekeepCapsEachAccountIndependently() {
        service.issue("ACCOUNT-A", "CUSTOMER");
        service.issue("ACCOUNT-A", "CUSTOMER");
        service.issue("ACCOUNT-B", "STYLER");
        service.issue("ACCOUNT-B", "STYLER");
        service.issue("ACCOUNT-B", "STYLER");
        service.issue("ACCOUNT-B", "STYLER");

        long[] result = service.housekeep(2);

        assertEquals(2L, result[1], "A loses nothing (2 live <= 2), B loses 2 of its 4 live");
        assertEquals(2L, store.stream()
                .filter(t -> t.getAccountId().equals("ACCOUNT-A") && !t.isRevoked()).count());
        assertEquals(2L, store.stream()
                .filter(t -> t.getAccountId().equals("ACCOUNT-B") && !t.isRevoked()).count());
    }

    @Test
    void housekeepUnderTheCapIsANoOpAndSparesRevokedEvidence() {
        String first = service.issue("ACCOUNT-1", "CUSTOMER");
        String second = service.rotate(first); // `first` revoked but unexpired — replay evidence

        long[] result = service.housekeep(10);

        assertEquals(0L, result[0]);
        assertEquals(0L, result[1], "under the cap nothing may be revoked");
        assertEquals(2, store.size(), "revoked-but-unexpired evidence rows must not be deleted");
        assertTrue(row(first).isRevoked());
        assertNotNull(service.validate(second), "live rotated token must survive an under-cap run");
    }

    private RefreshTokenEntity row(String raw) {
        String rawHash = hash(raw);
        return store.stream().filter(t -> rawHash.equals(t.getTokenHash())).findFirst().orElseThrow();
    }

    private String hash(String raw) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}