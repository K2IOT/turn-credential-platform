CREATE TABLE turn_secret (
    realm                  VARCHAR(255) PRIMARY KEY REFERENCES tenants(realm),
    value                  VARCHAR(255) NOT NULL,
    previous_value         VARCHAR(255),
    previous_valid_until   TIMESTAMPTZ,
    rotated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
