# TURN Credential Platform

Multi-tenant TURN REST API credential issuance service. Single data
center, highly available within that DC (no multi-region/multi-DC).

---

## API Usage Flow

Below is the complete end-to-end API workflow for onboarding tenants, issuing TURN credentials, rotating secrets, and connecting to coturn.

```mermaid
sequenceDiagram
    autonumber
    actor Admin
    actor Client as Tenant App Client
    participant API as Credential Service (:8080)
    participant DB as Postgres (turncred)
    participant Coturn as Coturn Server (:3478)

    Note over Admin, DB: Step 1: Admin Tenant Onboarding
    Admin->>API: POST /v1/admin/tenants
    API->>DB: Save tenant (api_key_hash) & initial turn_secret
    API-->>Admin: 201 Created (returns raw X-Api-Key)

    Note over Client, Coturn: Step 2: Credential Issuance
    Client->>API: POST /v1/turn-credentials (X-Api-Key: <key>, userId: "user-123")
    API->>DB: Validate API key hash & fetch tenant secret
    API->>API: Sign HMAC-SHA1(secret, "expiry:user-123")
    API->>DB: Write credential_issuance_log
    API-->>Client: 200 OK (username, password, ttlSeconds, uris)

    Note over Client, Coturn: Step 3: Media Connection
    Client->>Coturn: STUN/TURN allocate request (username, password)
    Coturn->>DB: Read turn_secret (psql-userdb)
    Coturn-->>Client: 200 OK (TURN Session Established)

    Note over Admin, DB: Step 4: Secret Rotation (Grace Period)
    Admin->>API: POST /v1/admin/tenants/{realm}/rotate-secret
    API->>DB: Move active secret to previous_value (15m grace), write new value
    API-->>Admin: 204 No Content
```

### 1. Provision a Tenant (Admin Endpoint)

Provision a new customer ("tenant"). The raw API key is returned **only once** upon creation.

**Request:**
```bash
curl -i -X POST http://localhost:8080/v1/admin/tenants \
  -H "Content-Type: application/json" \
  -d '{
        "name": "Acme Corp",
        "realm": "acme.turn.yourplatform.com"
      }'
```

**Response (`201 Created`):**
```json
{
  "tenantId": "c4a3b2a1-1234-4567-89ab-cdef01234567",
  "realm": "acme.turn.yourplatform.com",
  "apiKey": "tcp_A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6"
}
```

> **Note:** Save the `apiKey` (`tcp_...`). The platform only stores the SHA-256 hash of this key.

---

### 2. Issue TURN Credentials (Tenant Endpoint)

Tenant applications use their API key to request ephemeral TURN REST API credentials for end users.

**Request:**
```bash
curl -i -X POST http://localhost:8080/v1/turn-credentials \
  -H "X-Api-Key: tcp_A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6" \
  -H "Content-Type: application/json" \
  -d '{
        "userId": "user-42"
      }'
```
*(Note: `userId` is optional. If omitted, a random UUID will be assigned automatically.)*

**Response (`200 OK`):**
```json
{
  "username": "1755700000:user-42",
  "password": "dGhpcyBpcyBhIHZhbGlkIGhtYWMgc2lnbmF0dXJl=",
  "ttlSeconds": 3600,
  "uris": [
    "turn:acme.turn.yourplatform.com:3478?transport=udp",
    "turns:acme.turn.yourplatform.com:5349?transport=tcp"
  ]
}
```

**HTTP Status Codes:**
- `200 OK`: Credential successfully generated and logged.
- `401 Unauthorized`: Missing or invalid `X-Api-Key`, or tenant status is `SUSPENDED`.
- `429 Too Many Requests`: Tenant exceeded its configured per-minute rate limit.

---

### 3. Rotate Tenant TURN Secret (Admin Endpoint)

Rotate a tenant's HMAC secret. To prevent disrupting in-flight sessions, the old secret remains valid for a 15-minute grace period (`previous_value` and `previous_valid_until`).

**Request:**
```bash
curl -i -X POST http://localhost:8080/v1/admin/tenants/acme.turn.yourplatform.com/rotate-secret
```

**Response (`204 No Content`)**

---

### 4. Connect to Coturn or WebRTC Client

#### A. WebRTC Client (`RTCPeerConnection` configuration)
```javascript
const turnResponse = await fetch('https://api.yourplatform.com/v1/turn-credentials', {
  method: 'POST',
  headers: {
    'X-Api-Key': 'tcp_A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6',
    'Content-Type': 'application/json'
  },
  body: JSON.stringify({ userId: 'user-42' })
}).then(res => res.json());

const pc = new RTCPeerConnection({
  iceServers: [
    {
      urls: turnResponse.uris,
      username: turnResponse.username,
      credential: turnResponse.password
    }
  ]
});
```

#### B. Manual Verification via `turnutils_uclient`
```bash
turnutils_uclient -u "1755700000:user-42" -w "dGhpcyBpcyBhIHZhbGlk..." -p 3478 localhost
```

---

## Local Development

Start Postgres, Redis, Coturn, and the Spring Boot application locally via Docker Compose:

```bash
mvn clean package -DskipTests
docker compose up -d --build
```

---

## Production Topology

See `docker-compose.prod.yml`:
- 3-node etcd cluster (Patroni DCS)
- 3-node Patroni-managed Postgres cluster (1 leader, 1 sync replica, 1 async replica)
- HAProxy fronting Postgres (routes writes & reads to current Patroni leader via `/leader` healthcheck on port 8008)
- Redis 7 (rate limiting)
- Coturn cluster (`psql-userdb` reading Postgres through HAProxy)
- N Credential Service Spring Boot instances (Java 21 virtual threads)

**Manual Failover Test:**
```bash
docker compose -f docker-compose.prod.yml stop pg1
# Confirm another node is promoted to leader within ~10-30s
# and credential issuance continues through haproxy:5000
```
