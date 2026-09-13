package com.macrotel.rapidstylers.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Observability split (see docs/simplification-plan.md §3): stdout JSON is the
 * always-on log record, Better Stack is an optional token-gated HTTP sink, and
 * Sentry handles errors. These tests lock in the appender's fail-safe: without
 * LOG_TOKEN it never starts and never reaches the network, so a deployment
 * missing logging credentials can't lose its logs or its request path.
 */
class BetterStackLogbackAppenderTest {

    @Test
    void withoutTokenAppenderNeverStarts() {
        LoggerContext context = new LoggerContext();
        BetterStackLogbackAppender appender = new BetterStackLogbackAppender();
        appender.setContext(context);
        appender.setToken("");
        appender.start();

        assertFalse(appender.isStarted(), "appender must stay stopped without LOG_TOKEN");
        appender.stop();
    }

    @Test
    void withTokenAppenderStartsStaysNonBlockingAndStopsCleanly() {
        LoggerContext context = new LoggerContext();
        BetterStackLogbackAppender appender = new BetterStackLogbackAppender();
        appender.setContext(context);
        appender.setToken("test-token");
        // Loopback endpoint so the shipping path is exercisable without any
        // outbound network; connection refused is handled by the worker.
        appender.setEndpoint("http://127.0.0.1:1/");
        appender.start();

        assertTrue(appender.isStarted());
        Logger logger = context.getLogger("betterstack-test");
        appender.doAppend(new LoggingEvent("betterstack-test", logger, Level.INFO, "hello", null, null));

        appender.stop();
        assertFalse(appender.isStarted(), "stop() must leave the appender stopped");
    }
}
