# TURN Credential Platform

Multi-tenant TURN REST API credential issuance service. Single data
center, highly available within that DC (no multi-region/multi-DC).

## Local development
```bash
docker compose up -d --build
curl -X POST http://localhost:8080/v1/admin/tenants -H "Content-Type: application/json" \
  -d '{"name":"Acme Corp","realm":"acme.turn.yourplatform.com"}'
```

## Production topology
See `docker-compose.prod.yml`: 3-node etcd + 3-node Patroni-managed
Postgres (1 leader, 1 sync replica, 1 async replica) + HAProxy routing
all traffic to the current leader + Redis (rate limiting) + coturn +
N app instances. Postgres failover is automatic (~10-30s) via
Patroni/etcd — no manual DNS or config changes needed. Spec:
`docs/superpowers/specs/2026-08-20-turn-credential-platform-design.md`.

## Manual coturn verification
```bash
turnutils_uclient -u <username> -w <password> -p 3478 <turn-host>
```

## Manual failover test
```bash
docker compose -f docker-compose.prod.yml stop pg1
# confirm a different node becomes leader within ~30s and
# credential issuance keeps working through haproxy:5000
```
