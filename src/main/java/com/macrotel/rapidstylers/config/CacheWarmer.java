package com.macrotel.rapidstylers.config;

import com.macrotel.rapidstylers.service.AppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Warms the read cache after a cold start (deploy, Redis flush) so real users
 * don't take the full DB load on the first hit.
 *
 * <p>Non-blocking and bounded: it kicks off {@link AppService#warmReadCaches()}
 * on a shared fork-join thread and returns immediately, so it never delays
 * startup or the readiness probe. The warm itself warms only the bounded catalog
 * keys and a capped set of approved stylist profiles, and swallows all failures —
 * the cache is a speed-up, never a dependency. Geo index rebuild is handled
 * separately at startup and on a 30-minute reconcile (see AppService).
 */
@Component
public class CacheWarmer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CacheWarmer.class);

    private final AppService appService;

    public CacheWarmer(AppService appService) {
        this.appService = appService;
    }

    @Override
    public void run(ApplicationArguments args) {
        CompletableFuture.runAsync(() -> {
            try {
                appService.warmReadCaches();
            } catch (Exception ex) {
                // Best-effort warm-up only; never let a failed warm impact the app.
                log.warn("Cache warm-up background task failed (best-effort): {}", ex.getMessage());
            }
        });
        log.info("Cache warmer scheduled in the background (non-blocking, bounded).");
    }
}