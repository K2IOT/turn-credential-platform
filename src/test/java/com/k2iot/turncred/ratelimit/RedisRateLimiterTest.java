package com.k2iot.turncred.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisRateLimiterTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    private RedisRateLimiter rateLimiter() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        return new RedisRateLimiter(template);
    }

    @Test
    void allowsRequestsUnderTheLimit() {
        RedisRateLimiter limiter = rateLimiter();
        UUID tenantId = UUID.randomUUID();

        boolean first = limiter.tryAcquire(tenantId, 3);
        boolean second = limiter.tryAcquire(tenantId, 3);
        boolean third = limiter.tryAcquire(tenantId, 3);

        assertThat(first).isTrue();
        assertThat(second).isTrue();
        assertThat(third).isTrue();
    }

    @Test
    void rejectsRequestsOverTheLimit() {
        RedisRateLimiter limiter = rateLimiter();
        UUID tenantId = UUID.randomUUID();

        limiter.tryAcquire(tenantId, 2);
        limiter.tryAcquire(tenantId, 2);
        boolean third = limiter.tryAcquire(tenantId, 2);

        assertThat(third).isFalse();
    }
}
