package com.k2iot.turncred.secret;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "turn_secret")
public class TurnSecret {

    @Id
    private String realm;

    @Column(nullable = false)
    private String value;

    @Column(name = "previous_value")
    private String previousValue;

    @Column(name = "previous_valid_until")
    private Instant previousValidUntil;

    @Column(name = "rotated_at", nullable = false)
    private Instant rotatedAt = Instant.now();

    public String getRealm() { return realm; }
    public void setRealm(String realm) { this.realm = realm; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getPreviousValue() { return previousValue; }
    public void setPreviousValue(String previousValue) { this.previousValue = previousValue; }
    public Instant getPreviousValidUntil() { return previousValidUntil; }
    public void setPreviousValidUntil(Instant previousValidUntil) { this.previousValidUntil = previousValidUntil; }
    public Instant getRotatedAt() { return rotatedAt; }
    public void setRotatedAt(Instant rotatedAt) { this.rotatedAt = rotatedAt; }
}
