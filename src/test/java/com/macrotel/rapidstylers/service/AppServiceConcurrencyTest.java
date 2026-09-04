package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.dto.UserAccountDTO;
import com.macrotel.rapidstylers.entity.UserEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.pojo.SignInData;
import com.macrotel.rapidstylers.repo.UserRepo;
import com.macrotel.rapidstylers.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the shared-mutable-BaseResponse bug.
 *
 * AppService used to hold a single {@code BaseResponse} field that every
 * request mutated in place, so a JWT set by one call could bleed into the next
 * call's response (e.g. {@code create_styler} returning the previous customer
 * sign-in's token) and concurrent requests could interleave each other's
 * error/success state.
 *
 * {@link #concurrentCallsNeverBleedStateBetweenResponses()} hammers the real
 * AppService from many threads and asserts no response ever carries another
 * call's token or state. {@link #legacySharedFieldPatternIsDetectedByTheHarness()}
 * runs the identical harness against a replica of the OLD shared-field pattern
 * and asserts that bleed IS detected — proving the harness can actually fail.
 */
class AppServiceConcurrencyTest {

    private static final int THREADS = 8;
    private static final int ITERATIONS = 300;
    private static final String VALID_EMAIL = "concurrent@rapidstylers.test";
    private static final String INVALID_EMAIL = "nobody@rapidstylers.test";

    /** Marker for the currently-executing worker thread; drives the per-thread JWT value. */
    private static final ThreadLocal<String> THREAD_MARKER = new ThreadLocal<>();

    /** The three call shapes the harness exercises concurrently. */
    interface Calls {
        BaseResponse successOnly();   // must never carry a token
        BaseResponse successWithToken(); // must carry exactly THIS thread's token
        BaseResponse failingCall();   // must carry its own error and no token
    }

    private AppService appService;

    @BeforeEach
    void setUp() {
        appService = new AppService();

        UserRepo userRepo = mock(UserRepo.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        LoginAttemptService loginAttemptService = mock(LoginAttemptService.class);
        JwtUtil jwtUtil = mock(JwtUtil.class);
        DTOService dtoService = mock(DTOService.class);
        AppUtils appUtils = mock(AppUtils.class);

        UserEntity validUser = new UserEntity();
        validUser.setUserId("U-CONCURRENT");
        validUser.setEmailAddress(VALID_EMAIL);
        validUser.setStatus("0");
        validUser.setPassword("Passw0rd!"); // not 32-hex → skips the legacy-MD5 upgrade branch

        // Known email → existing account; any other email → unknown account.
        when(userRepo.findByEmailAddress(anyString())).thenAnswer(invocation -> {
            String email = invocation.getArgument(0);
            return VALID_EMAIL.equals(email) ? Optional.of(validUser) : Optional.empty();
        });
        when(rateLimiterService.isBlocked(anyString(), anyInt(), anyInt())).thenReturn(false);
        when(appUtils.passwordMatches(anyString(), anyString())).thenReturn(true);
        // Every thread gets a JWT that encodes its own marker.
        when(jwtUtil.generateToken(anyString(), anyString()))
                .thenAnswer(invocation -> "TOKEN_" + THREAD_MARKER.get());
        when(dtoService.userAccountDTO(any())).thenAnswer(invocation -> {
            UserAccountDTO dto = new UserAccountDTO();
            dto.setUserId("U-CONCURRENT");
            dto.setEmailAddress(VALID_EMAIL);
            return dto;
        });

        // /user_sign_in now issues a refresh token on success, so the harness
        // must wire the service (leaving it null would NPE into the catch block).
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        when(refreshTokenService.issue(anyString(), anyString()))
                .thenAnswer(invocation -> "RT_" + THREAD_MARKER.get());

        // Package-private fields — the test lives in the same package.
        appService.userRepo = userRepo;
        appService.rateLimiterService = rateLimiterService;
        appService.loginAttemptService = loginAttemptService;
        appService.jwtUtil = jwtUtil;
        appService.dtoService = dtoService;
        appService.appUtils = appUtils;
        appService.refreshTokenService = refreshTokenService;
    }

    @Test
    void concurrentCallsNeverBleedStateBetweenResponses() throws Exception {
        ConcurrentLinkedQueue<String> failures = hammer(new Calls() {
            @Override
            public BaseResponse successOnly() {
                return appService.testing();
            }

            @Override
            public BaseResponse successWithToken() {
                return appService.userSignIn(signIn(VALID_EMAIL));
            }

            @Override
            public BaseResponse failingCall() {
                return appService.userSignIn(signIn(INVALID_EMAIL));
            }
        });
        assertTrue(failures.isEmpty(),
                () -> "cross-request state bleed detected (" + failures.size() + "):\n"
                        + String.join("\n", failures));
    }

    /**
     * Proves the harness detects the bug class: the OLD pattern — one shared
     * BaseResponse field mutated in place, with error paths that never clear
     * the token — must produce detectable bleed (e.g. a failed sign-in that
     * still carries the previous sign-in's token).
     */
    @Test
    void legacySharedFieldPatternIsDetectedByTheHarness() throws Exception {
        LegacySharedFieldService legacy = new LegacySharedFieldService();
        ConcurrentLinkedQueue<String> failures = hammer(new Calls() {
            @Override
            public BaseResponse successOnly() {
                return legacy.testing();
            }

            @Override
            public BaseResponse successWithToken() {
                return legacy.userSignIn(true);
            }

            @Override
            public BaseResponse failingCall() {
                return legacy.userSignIn(false);
            }
        });
        assertFalse(failures.isEmpty(),
                "harness should have detected bleed in the old shared-field pattern");
    }

    /** Runs the same concurrent hammer against any {@link Calls} implementation. */
    private ConcurrentLinkedQueue<String> hammer(Calls calls) throws InterruptedException {
        CyclicBarrier start = new CyclicBarrier(THREADS);
        CountDownLatch done = new CountDownLatch(THREADS);
        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            pool.submit(() -> {
                THREAD_MARKER.set("T" + threadId);
                try {
                    start.await(); // release all workers at once → maximum interleaving
                    for (int i = 0; i < ITERATIONS; i++) {
                        // 1) Success-only call — must never carry a token or foreign state.
                        BaseResponse ping = calls.successOnly();
                        if (ping.getToken() != null) {
                            failures.add(threadId + "/" + i + ": success-only returned foreign token " + ping.getToken());
                        }
                        if (!"200".equals(ping.getStatusCode()) || !"API is working well".equals(ping.getMessage())) {
                            failures.add(threadId + "/" + i + ": success-only state corrupted: "
                                    + ping.getStatusCode() + "/" + ping.getMessage());
                        }

                        // 2) Success + token call — token must be THIS thread's own.
                        BaseResponse ok = calls.successWithToken();
                        if (!"200".equals(ok.getStatusCode())) {
                            failures.add(threadId + "/" + i + ": token call failed unexpectedly: " + ok.getMessage());
                        } else if (!("TOKEN_T" + threadId).equals(ok.getToken())) {
                            failures.add(threadId + "/" + i + ": token call returned foreign token "
                                    + ok.getToken() + " (expected TOKEN_T" + threadId + ")");
                        }

                        // 3) Failing call — must carry its own error and never a token.
                        BaseResponse bad = calls.failingCall();
                        if (bad.getToken() != null) {
                            failures.add(threadId + "/" + i + ": failing call returned foreign token " + bad.getToken());
                        }
                        if (!"400".equals(bad.getStatusCode())
                                || !"Invalid Email Address or Password".equals(bad.getMessage())) {
                            failures.add(threadId + "/" + i + ": failing call state corrupted: "
                                    + bad.getStatusCode() + "/" + bad.getMessage());
                        }
                    }
                } catch (Throwable ex) {
                    failures.add(threadId + ": worker crashed: " + ex);
                } finally {
                    THREAD_MARKER.remove();
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(120, TimeUnit.SECONDS), "workers did not finish in time");
        pool.shutdownNow();
        return failures;
    }

    private SignInData signIn(String emailAddress) {
        SignInData data = new SignInData();
        data.setEmailAddress(emailAddress);
        data.setPassword("Passw0rd!");
        return data;
    }

    /**
     * Faithful replica of the OLD AppService pattern: a single shared mutable
     * BaseResponse field, mutated in place, with error paths that never clear
     * the token. This is the bug the regression test guards against.
     */
    static class LegacySharedFieldService {
        final BaseResponse baseResponse = new BaseResponse(true); // THE BUG

        BaseResponse testing() {
            baseResponse.setStatusCode("200");
            baseResponse.setMessage("API is working well");
            baseResponse.setData(new Object[0]);
            return baseResponse;
        }

        BaseResponse userSignIn(boolean valid) {
            if (valid) {
                baseResponse.setStatusCode("200");
                baseResponse.setMessage("Successful");
                baseResponse.setData(new Object[0]);
                baseResponse.setToken("TOKEN_" + THREAD_MARKER.get());
            } else {
                baseResponse.setStatusCode("400");
                baseResponse.setMessage("Invalid Email Address or Password");
                baseResponse.setData(new Object[0]);
                // Old behaviour: token from a previous call is never cleared.
            }
            return baseResponse;
        }
    }
}
