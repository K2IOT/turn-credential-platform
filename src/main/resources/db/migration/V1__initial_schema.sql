-- V1: Initial schema baseline consolidating V1-V5 migrations
-- Clean, idempotent-as-practical baseline for fresh database setup

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1. tenants table
CREATE TABLE tenants (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(255) NOT NULL,
    realm               VARCHAR(255) NOT NULL UNIQUE,
    api_key_hash        VARCHAR(255) NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    credential_ttl_sec  INT          NOT NULL DEFAULT 3600,
    rate_limit_per_min  INT          NOT NULL DEFAULT 600,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_tenants_api_key_hash ON tenants (api_key_hash);

-- 2. turn_secret table (multi-row per realm)
CREATE TABLE turn_secret (
    realm       VARCHAR(255) NOT NULL,
    value       VARCHAR(255) NOT NULL,
    valid_until TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    PRIMARY KEY (realm, value),

    CONSTRAINT fk_turn_secret_tenant
        FOREIGN KEY (realm)
        REFERENCES tenants(realm)
        ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_turn_secret_current
    ON turn_secret(realm)
    WHERE valid_until IS NULL;

CREATE INDEX idx_turn_secret_valid_until
    ON turn_secret(valid_until)
    WHERE valid_until IS NOT NULL;

-- 3. credential_issuance_log table
CREATE TABLE credential_issuance_log (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   UUID         NOT NULL REFERENCES tenants(id),
    user_id     VARCHAR(255) NOT NULL,
    issued_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ttl_sec     INT          NOT NULL
);

CREATE INDEX idx_issuance_tenant_time ON credential_issuance_log (tenant_id, issued_at);
