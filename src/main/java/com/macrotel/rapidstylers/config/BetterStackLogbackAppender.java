package com.macrotel.rapidstylers.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Logback appender that ships JSON log events to the Better Stack (logtail)
 * HTTP ingest endpoint.
 *
 * Enabled only when the <token> property (LOG_TOKEN env var) is set. When the
 * token is missing the appender never starts and the app logs to console as
 * usual — a deployment without logging credentials is unaffected. Shipping
 * happens on a daemon worker thread, so a slow endpoint never blocks request
 * handling.
 */
public class BetterStackLogbackAppender extends AppenderBase<ILoggingEvent> {

    private static final DateTimeFormatter ISO_INSTANT = DateTimeFormatter.ISO_INSTANT;

    private String token;
    private String endpoint = "https://in.logs.betterstack.com";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>(10_000);
    private volatile Thread worker;
    private volatile boolean running;

    public void setToken(String token) {
        this.token = token;
    }

    public void setEndpoint(String endpoint) {
        if (endpoint != null && !endpoint.isBlank()) {
            this.endpoint = endpoint;
        }
    }

    @Override
    public void start() {
        if (token == null || token.isBlank()) {
            addWarn("LOG_TOKEN not set — Better Stack log appender disabled");
            return;
        }
        super.start();
        running = true;
        worker = new Thread(this::drainLoop, "betterstack-log-shipper");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        super.stop();
    }

    @Override
    protected void append(ILoggingEvent event) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("dt", ISO_INSTANT.format(Instant.ofEpochMilli(event.getTimeStamp())));
        entry.put("level", event.getLevel().toString());
        entry.put("logger", event.getLoggerName());
        entry.put("thread", event.getThreadName());
        entry.put("message", event.getFormattedMessage());
        if (event.getMDCPropertyMap() != null && !event.getMDCPropertyMap().isEmpty()) {
            entry.put("mdc", event.getMDCPropertyMap());
        }
        if (!queue.offer(entry)) {
            queue.poll();
            queue.offer(entry);
        }
    }

    private void drainLoop() {
        List<Map<String, Object>> batch = new ArrayList<>(100);
        while (running) {
            try {
                Map<String, Object> first = queue.poll(2, TimeUnit.SECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, 99);
                post(batch);
                batch.clear();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                addWarn("Better Stack log post failed: " + ex.getMessage());
                batch.clear();
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void post(List<Map<String, Object>> batch) throws Exception {
        if (batch.isEmpty()) {
            return;
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        connection.setDoOutput(true);
        try (OutputStream os = connection.getOutputStream()) {
            os.write(objectMapper.writeValueAsBytes(batch));
        }
        int code = connection.getResponseCode();
        connection.disconnect();
        if (code < 200 || code >= 300) {
            throw new RuntimeException("HTTP " + code);
        }
    }
}
