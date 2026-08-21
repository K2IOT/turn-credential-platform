CREATE TABLE credential_issuance_log (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES tenants(id),
    user_id     VARCHAR(255) NOT NULL,
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    ttl_sec     INT NOT NULL
);

CREATE INDEX idx_issuance_tenant_time ON credential_issuance_log (tenant_id, issued_at);
