package com.k2iot.turncred.secret;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "turn_secret")
public class TurnSecret {

    @EmbeddedId
    private TurnSecretId id;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public TurnSecret() {}

    public TurnSecret(TurnSecretId id) {
        this.id = id;
        if (id != null) {
            this.userId = id.getUserId();
        }
    }

    public TurnSecretId getId() {
        if (id != null && id.getUserId() == null && userId != null) {
            id.setUserId(userId);
        }
        return id;
    }

    public void setId(TurnSecretId id) {
        this.id = id;
        if (id != null) {
            this.userId = id.getUserId();
        }
    }

    public String getRealm() { return id != null ? id.getRealm() : null; }
    public String getValue() { return id != null ? id.getValue() : null; }
    public String getUserId() { return userId; } // null for realm-level secrets
    public void setUserId(String userId) {
        this.userId = userId;
        if (this.id != null) {
            this.id.setUserId(userId);
        }
    }

    public Instant getValidUntil() { return validUntil; }
    public void setValidUntil(Instant validUntil) { this.validUntil = validUntil; }

    public Instant getCreatedAt() { return createdAt; }
}
