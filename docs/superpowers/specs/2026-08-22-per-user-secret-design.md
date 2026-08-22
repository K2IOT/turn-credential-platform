# Per-UserId Secret — Feature Design Spec

- **Status:** Approved, pending implementation plan
- **Date:** 2026-08-22
- **Repo:** https://github.com/K2IOT/turn-credential-platform
- **Author:** Antigravity (brainstorming session)

---

## 1. Goal

Extend the platform from **1 tenant → N realm-level secrets** to **1 tenant → N userIds → each userId has its own dedicated TURN secret**.

The admin pre-registers specific `userId` values for a tenant via API. Each registered `userId` receives an auto-generated HMAC secret. Credential issuance for an unregistered `userId` is rejected with `403 Forbidden`. Coturn validates credentials using the userId-scoped secret via an extended `psql-userdb` query.

---

## 2. Non-goals (this change)

- Tenant self-service userId management (admin-only operation).
- Per-userId rate limiting (tenant-level rate limit unchanged).
- Automatic userId registration on first credential issuance.
- Multi-DC or multi-region concerns (out of scope per original design).

---

## 3. Current model vs. new model

| | Current | New |
|---|---|---|
| Secret scope | Per realm (1 tenant = 1 realm = N secrets) | Per userId (1 tenant = N userIds = N×M secrets) |
| Who creates secrets | Admin creates tenant; realm secret auto-generated | Admin registers userId; userId secret auto-generated |
| Credential issuance | Any userId works; signed with realm secret | Only registered userIds; signed with userId secret |
| Unregistered userId | Allowed (any string works) | **403 Forbidden** |
| Coturn validation | Queries `turn_secret WHERE realm = $1` | Queries with `user_id IS NULL OR user_id = split_part($2,':',2)` |

---

## 4. Data Model

### 4.1 `turn_secret` table — schema change

Add a nullable `user_id` column. `user_id = NULL` means a realm-level secret (existing behaviour). `user_id = 'alice'` means a secret scoped to that userId within the realm.

```sql
-- V2 migration
ALTER TABLE turn_secret ADD COLUMN user_id VARCHAR(255) DEFAULT NULL;

-- Drop old "one active per realm" constraint (realm-level only)
DROP INDEX uq_turn_secret_current;

-- One active secret per realm (realm-level secrets only)
CREATE UNIQUE INDEX uq_turn_secret_current_realm
    ON turn_secret(realm)
    WHERE user_id IS NULL AND valid_until IS NULL;

-- One active secret per (realm, userId) pair
CREATE UNIQUE INDEX uq_turn_secret_current_user
    ON turn_secret(realm, user_id)
    WHERE user_id IS NOT NULL AND valid_until IS NULL;

-- Index for userId-scoped lookups
CREATE INDEX idx_turn_secret_user_id
    ON turn_secret(realm, user_id)
    WHERE user_id IS NOT NULL;
```

**Primary key** remains `(realm, value)`. The `user_id` column is not part of the PK — uniqueness of the active secret per user is enforced by the partial unique index.

### 4.2 New `tenant_user` table

Tracks which userIds have been pre-registered for a tenant.

```sql
CREATE TABLE tenant_user (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id    VARCHAR(255) NOT NULL,
    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_user UNIQUE (tenant_id, user_id)
);

CREATE INDEX idx_tenant_user_tenant ON tenant_user(tenant_id);
```

**`status` values:** `ACTIVE`, `SUSPENDED`.

---

## 5. API Changes

### 5.1 New admin endpoints

#### Register a userId
```
POST /v1/admin/tenants/{tenantId}/users
Content-Type: application/json
X-Admin-Api-Key: <admin-key>

{ "userId": "alice" }
```
- Creates a `tenant_user` row with `status = ACTIVE`.
- Auto-generates an initial secret in `turn_secret(realm, user_id='alice', value=<generated>, valid_until=NULL)`.
- **201 Created:** `{ "userId": "alice", "tenantId": "..." }`
- **409 Conflict:** userId already registered.

#### Rotate a userId's secret
```
POST /v1/admin/tenants/{tenantId}/users/{userId}/rotate-secret
X-Admin-Api-Key: <admin-key>
```
- Deletes expired grace-period rows for `(realm, userId)`.
- Sets `valid_until = now() + 15m` on the current active secret.
- Inserts a new secret with `valid_until = NULL`.
- **204 No Content**
- **404 Not Found:** userId not registered.

#### Deregister (suspend) a userId
```
DELETE /v1/admin/tenants/{tenantId}/users/{userId}
X-Admin-Api-Key: <admin-key>
```
- Sets `tenant_user.status = SUSPENDED`.
- Does **not** delete existing secrets (retained for audit).
- **204 No Content**
- **404 Not Found:** userId not registered.

### 5.2 Changed — `POST /v1/turn-credentials`

| Scenario | Response |
|---|---|
| `userId` registered and `ACTIVE` | `200 OK` — signed with **userId's current secret** |
| `userId` not registered | `403 Forbidden` — `"User not registered for this tenant"` |
| `userId` registered but `SUSPENDED` | `403 Forbidden` — `"User is suspended"` |
| `userId` omitted (random UUID assigned) | `403 Forbidden` (random UUID won't be registered) |

> **Breaking change:** Previously any `userId` (including omitted/random) worked. After this change, only pre-registered userIds succeed.

---

## 6. Service Layer

### 6.1 New components

| Component | Package | Responsibility |
|---|---|---|
| `TenantUser` | `tenant` | JPA entity for `tenant_user` table |
| `TenantUserRepository` | `tenant` | `findByTenantIdAndUserId`, status check |
| `UserSecretRotationService` | `secret` | `registerUser(tenantId, realm, userId)`, `rotateUserSecret(realm, userId)` |
| `TenantUserAdminController` | `admin` | Handles 3 new admin endpoints |

### 6.2 Changed components

| Component | Change |
|---|---|
| `TurnSecretId` | Add nullable `userId` field; new constructor `(realm, userId, value)` |
| `TurnSecret` | Expose `getUserId()` / `setUserId()` |
| `TurnSecretRepository` | Add `findCurrentByRealmAndUserId(realm, userId)` and `deleteExpiredForRealmAndUserId(realm, userId)` |
| `TurnCredentialService` | Add userId validation gate (look up `TenantUser`, throw 403 if missing/suspended); use `findCurrentByRealmAndUserId` |

### 6.3 Credential issuance flow (updated)

```
POST /v1/turn-credentials (X-Api-Key, body: { userId })
  │
  ├─ TenantAuthInterceptor → resolve Tenant            [unchanged]
  │
  └─ TurnCredentialService.issueCredential(tenant, userId)
        │
        ├─ rateLimiter.tryAcquire()                    [unchanged]
        │
        ├─ tenantUserRepo.findByTenantIdAndUserId()
        │     ├─ NOT FOUND   → throw UserNotRegisteredException (403)
        │     └─ SUSPENDED   → throw UserSuspendedException (403)
        │
        ├─ secretRepo.findCurrentByRealmAndUserId(realm, userId)
        │     └─ NOT FOUND   → throw IllegalStateException (500, data inconsistency)
        │
        ├─ expiry = now + ttl; username = "expiry:userId"
        ├─ password = HMAC-SHA1(userSecret.value, username)
        ├─ log CredentialIssuanceLog
        └─ return TurnCredential
```

---

## 7. Coturn Configuration Change

### Current query
```
userdb-user-secret-query="SELECT value FROM turn_secret WHERE realm = $1 AND (valid_until IS NULL OR valid_until > NOW())"
```

### Updated query
```
userdb-user-secret-query="SELECT value FROM turn_secret WHERE realm = $1 AND (user_id IS NULL OR user_id = substring($2 from position(':' in $2) + 1)) AND (valid_until IS NULL OR valid_until > NOW())"
```

- `$1` = realm (existing Coturn parameter)
- `$2` = full username string (`"expiry:userId"`)
- `substring($2 from position(':' in $2) + 1)` extracts everything after the **first** colon — correctly handles userIds that themselves contain colons (e.g., `"user:alice"` → extracts `"user:alice"` from `"1755700000:user:alice"`)
- Realm-level secrets (`user_id IS NULL`) always included — backward compatible
- For a registered userId, Coturn receives both the userId-scoped secret and realm secrets; HMAC validation succeeds only against the right one

---

## 8. Error Handling

| Scenario | HTTP | Body |
|---|---|---|
| `userId` not registered | 403 | `{ "error": "User not registered for this tenant" }` |
| `userId` is SUSPENDED | 403 | `{ "error": "User is suspended" }` |
| No active secret for registered userId | 500 | `{ "error": "No TURN secret configured for user <userId>" }` |
| Admin: register duplicate userId | 409 | `{ "error": "User already registered" }` |
| Admin: rotate/deregister unknown userId | 404 | `{ "error": "User not found" }` |

---

## 9. Testing Plan

### Unit tests
- `TurnCredentialService`: 403 on unregistered userId; 403 on suspended userId; credential signed with userId secret not realm secret.
- `UserSecretRotationService`: `registerUser` creates tenant_user row + initial secret; `rotateUserSecret` expires current with 15-min grace and inserts new; deregister sets SUSPENDED.

### Repository tests
- `TurnSecretRepository.findCurrentByRealmAndUserId`: returns correct per-userId secret; does not return realm-level secret for userId queries; null when only grace-period secrets exist.
- `TurnSecretRepository.deleteExpiredForRealmAndUserId`: removes only expired rows for that user.
- Existing realm-level queries (`findCurrentByRealm`, `findValidByRealm`) unaffected by the new `user_id` column.

### Integration tests
- `POST /v1/admin/tenants/{id}/users` → 201; duplicate → 409.
- `POST /v1/admin/tenants/{id}/users/{userId}/rotate-secret` → 204; grace period observed.
- `DELETE /v1/admin/tenants/{id}/users/{userId}` → 204; subsequent issuance → 403.
- `POST /v1/turn-credentials` with registered userId → 200, HMAC valid against userId secret.
- `POST /v1/turn-credentials` with unregistered userId → 403.
- `POST /v1/turn-credentials` with suspended userId → 403.
- Coturn SQL query (verified against test DB): returns userId secret + realm secrets for given realm/username.

---

## 10. Migration & Backward Compatibility

- Existing tenants and their realm-level secrets are **fully unaffected**. `user_id IS NULL` rows continue to work as before.
- The Coturn query change is backward compatible: for tenants with no per-userId secrets, `split_part($2, ':', 2)` extracts a userId with no matching rows — only `user_id IS NULL` rows are returned, identical to current behaviour.
- Per-userId enforcement is **opt-in by tenant**: a tenant only gets per-userId enforcement after the admin registers at least one userId for it.
