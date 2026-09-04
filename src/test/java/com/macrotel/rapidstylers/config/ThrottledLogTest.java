package com.macrotel.rapidstylers.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThrottledLogTest {

    private final List<LogRecord> records = new ArrayList<>();
    private Logger logger;
    private Handler capture;

    @BeforeEach
    void setUp() {
        ThrottledLog.reset();
        logger = Logger.getLogger("throttled-log-test-" + System.nanoTime());
        logger.setLevel(Level.WARNING);
        logger.setUseParentHandlers(false);
        capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        logger.removeHandler(capture);
        ThrottledLog.reset();
    }

    @Test
    void firstOccurrenceLogsAndRepeatsWithinWindowAreSuppressed() {
        ThrottledLog.warnOncePerWindow(logger, "key-a", "first message");
        ThrottledLog.warnOncePerWindow(logger, "key-a", "second message");
        ThrottledLog.warnOncePerWindow(logger, "key-a", "third message");

        assertEquals(1, records.size(), "only the first occurrence in the window may log");
        assertEquals("first message", records.get(0).getMessage());
    }

    @Test
    void distinctKeysHaveIndependentWindows() {
        ThrottledLog.warnOncePerWindow(logger, "cache/read", "catalog read degraded");
        ThrottledLog.warnOncePerWindow(logger, "session/touch", "touch failed");

        assertEquals(2, records.size(), "one line per distinct key even in the same window");
    }

    @Test
    void windowExpiryAllowsTheNextOccurrenceToLog() throws InterruptedException {
        ThrottledLog.warnOncePerWindow(logger, "key-a", "first", 5L);
        ThrottledLog.warnOncePerWindow(logger, "key-a", "suppressed", 5L);
        Thread.sleep(80);
        ThrottledLog.warnOncePerWindow(logger, "key-a", "after window", 5L);

        assertEquals(2, records.size(), "a new window emits again after the old one elapses");
        assertTrue(records.get(1).getMessage().startsWith("after window"),
                "message must still be emitted after the window elapses: " + records.get(1).getMessage());
    }

    @Test
    void suppressedOccurrencesAreSummarizedOnTheNextEmission() throws InterruptedException {
        ThrottledLog.warnOncePerWindow(logger, "geo/index", "boom", 5L);
        ThrottledLog.warnOncePerWindow(logger, "geo/index", "boom", 5L);
        ThrottledLog.warnOncePerWindow(logger, "geo/index", "boom", 5L);
        Thread.sleep(80);
        ThrottledLog.warnOncePerWindow(logger, "geo/index", "boom", 5L);

        assertEquals(2, records.size());
        assertTrue(records.get(1).getMessage().contains("2 similar occurrences suppressed since previous log"),
                "the line opening the next window reports what was hidden: " + records.get(1).getMessage());
    }

    @Test
    void resetDropsWindowState() {
        ThrottledLog.warnOncePerWindow(logger, "key-a", "first");
        ThrottledLog.reset();
        ThrottledLog.warnOncePerWindow(logger, "key-a", "after reset");

        assertEquals(2, records.size(), "reset clears suppression state (test seam)");
    }
}
