package com.k2iot.turncred.credential;

import java.util.UUID;

public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(UUID tenantId) {
        super("Rate limit exceeded for tenant " + tenantId);
    }
}
