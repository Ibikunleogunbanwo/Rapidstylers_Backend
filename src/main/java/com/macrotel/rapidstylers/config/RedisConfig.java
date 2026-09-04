package com.macrotel.rapidstylers.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

/**
 * Redis configuration for JSON-typed caching.
 *
 * The value serializer is GenericJackson2JsonRedisSerializer (keys stay plain
 * strings), which is required ONLY by the read cache's typed payloads
 * (ReadCacheService). Everything that stores plain strings — rate limiting,
 * idempotency claims, session activity, notification dedup, and the geo index
 * (styler IDs) — deliberately uses Boot's StringRedisTemplate instead: sending
 * plain-string data through this Jackson serializer would quote every value on
 * the wire and break plain-string readers (the rate-limiter class of bug).
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
