-- 1. Add nullable user_id to turn_secret
ALTER TABLE turn_secret ADD COLUMN user_id VARCHAR(255) DEFAULT NULL;

-- 2. Drop the old "one active secret per realm" unique index
DROP INDEX uq_turn_secret_current;

-- 3. One active realm-level secret per realm (user_id IS NULL)
CREATE UNIQUE INDEX uq_turn_secret_current_realm
    ON turn_secret(realm)
    WHERE user_id IS NULL AND valid_until IS NULL;

-- 4. One active per-userId secret per (realm, user_id) pair
CREATE UNIQUE INDEX uq_turn_secret_current_user
    ON turn_secret(realm, user_id)
    WHERE user_id IS NOT NULL AND valid_until IS NULL;

-- 5. Index for efficient per-userId lookups
CREATE INDEX idx_turn_secret_user_id
    ON turn_secret(realm, user_id)
    WHERE user_id IS NOT NULL;

-- 6. New tenant_user registry
CREATE TABLE tenant_user (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id    VARCHAR(255) NOT NULL,
    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_user UNIQUE (tenant_id, user_id)
);

CREATE INDEX idx_tenant_user_tenant ON tenant_user(tenant_id);
