package com.k2iot.turncred.credential;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "credential_issuance_log")
public class CredentialIssuanceLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Instant.now();

    @Column(name = "ttl_sec", nullable = false)
    private int ttlSec;

    public CredentialIssuanceLog() {}

    public CredentialIssuanceLog(UUID tenantId, String userId, int ttlSec) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.ttlSec = ttlSec;
    }

    public Long getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getUserId() { return userId; }
    public Instant getIssuedAt() { return issuedAt; }
    public int getTtlSec() { return ttlSec; }
}
