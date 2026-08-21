-- V5: Refactor turn_secret to multi-row-per-realm model
-- All DDL is transactional in PostgreSQL; Flyway wraps this in a single transaction.

-- 1. Drop the active view (V4) -- no application code queries it
DROP VIEW IF EXISTS turn_secret_active;

-- 2. Rename old table to preserve data during migration
ALTER TABLE turn_secret RENAME TO turn_secret_old;

-- 3. Create new schema
CREATE TABLE turn_secret (
    realm       VARCHAR(127) NOT NULL,
    value       VARCHAR(256) NOT NULL,
    valid_until TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (realm, value),

    CONSTRAINT fk_turn_secret_tenant
        FOREIGN KEY (realm)
        REFERENCES tenants(realm)
        ON DELETE CASCADE
);

-- 4. Migrate current secrets (valid_until = NULL)
INSERT INTO turn_secret (realm, value, valid_until, created_at)
SELECT realm, value, NULL, rotated_at
FROM turn_secret_old;

-- 5. Migrate still-valid previous secrets
INSERT INTO turn_secret (realm, value, valid_until, created_at)
SELECT realm, previous_value, previous_valid_until, rotated_at
FROM turn_secret_old
WHERE previous_value IS NOT NULL
  AND previous_valid_until IS NOT NULL
  AND previous_valid_until > now();

-- 6. Indexes
CREATE UNIQUE INDEX uq_turn_secret_current
    ON turn_secret(realm)
    WHERE valid_until IS NULL;

CREATE INDEX idx_turn_secret_valid_until
    ON turn_secret(valid_until)
    WHERE valid_until IS NOT NULL;

-- 7. Drop old table
DROP TABLE turn_secret_old;
