package com.k2iot.turncred.secret;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class TurnSecretId implements Serializable {

    private String realm;
    private String value;

    public TurnSecretId() {}

    public TurnSecretId(String realm, String value) {
        this.realm = realm;
        this.value = value;
    }

    public String getRealm() { return realm; }
    public void setRealm(String realm) { this.realm = realm; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TurnSecretId that)) return false;
        return Objects.equals(realm, that.realm) && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(realm, value);
    }
}
