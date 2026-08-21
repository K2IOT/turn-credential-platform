package com.k2iot.turncred.secret;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "turn_secret")
public class TurnSecret {

    @EmbeddedId
    private TurnSecretId id;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public TurnSecret() {}

    public TurnSecret(TurnSecretId id) {
        this.id = id;
    }

    public TurnSecretId getId() { return id; }
    public void setId(TurnSecretId id) { this.id = id; }

    public String getRealm() { return id.getRealm(); }
    public String getValue() { return id.getValue(); }

    public Instant getValidUntil() { return validUntil; }
    public void setValidUntil(Instant validUntil) { this.validUntil = validUntil; }

    public Instant getCreatedAt() { return createdAt; }
}
