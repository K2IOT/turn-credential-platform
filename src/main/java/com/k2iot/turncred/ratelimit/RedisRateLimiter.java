package com.k2iot.turncred.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
public class RedisRateLimiter {

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean tryAcquire(UUID tenantId, int limitPerMinute) {
        long currentMinuteBucket = Instant.now().getEpochSecond() / 60;
        String key = "ratelimit:%s:%d".formatted(tenantId, currentMinuteBucket);

        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofSeconds(90));
        }
        return count != null && count <= limitPerMinute;
    }
}
