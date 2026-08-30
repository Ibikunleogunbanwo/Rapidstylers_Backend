package com.macrotel.rapidstylers.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisStartupMonitorTest {

    private RedisStartupMonitor monitor(RedisConnectionFactory factory) {
        RedisStartupMonitor m = new RedisStartupMonitor(factory);
        ReflectionTestUtils.setField(m, "host", "redis-test");
        ReflectionTestUtils.setField(m, "port", 6379);
        return m;
    }

    @Test
    void logsConnectedWhenPingSucceeds() {
        RedisConnection conn = mock(RedisConnection.class);
        when(conn.ping()).thenReturn("PONG");
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(conn);

        RedisStartupMonitor m = monitor(factory);
        ReflectionTestUtils.setField(m, "password", "supersecret");
        // must not throw
        m.run(null);
    }

    @Test
    void logsWarningAndDoesNotThrowWhenConnectionFails() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenThrow(new RuntimeException("Connection refused: localhost:6379"));

        RedisStartupMonitor m = monitor(factory);
        ReflectionTestUtils.setField(m, "password", null);
        // a failing probe must never blow up startup
        m.run(null);
    }

    @Test
    void handlesNullPongWithoutThrowing() {
        RedisConnection conn = mock(RedisConnection.class);
        when(conn.ping()).thenReturn(null);
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(conn);

        RedisStartupMonitor m = monitor(factory);
        ReflectionTestUtils.setField(m, "password", "");
        m.run(null);
    }
}