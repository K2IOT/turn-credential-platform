package com.k2iot.turncred.secret;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class TurnSecretId implements Serializable {

    private String realm;
    private String value;

    @Transient
    private String userId; // nullable — null means realm-level secret

    public TurnSecretId() {}

    /** Realm-level secret (userId = null). */
    public TurnSecretId(String realm, String value) {
        this.realm = realm;
        this.value = value;
        this.userId = null;
    }

    /** Per-userId secret. */
    public TurnSecretId(String realm, String userId, String value) {
        this.realm = realm;
        this.userId = userId;
        this.value = value;
    }

    public String getRealm() { return realm; }
    public void setRealm(String realm) { this.realm = realm; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TurnSecretId that)) return false;
        return Objects.equals(realm, that.realm)
                && Objects.equals(value, that.value)
                && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(realm, value, userId);
    }
}
