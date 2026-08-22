#!/usr/bin/env bash
set -euo pipefail

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.yml}"
ADMIN_KEY="dev-admin-key"
BASE_URL="http://localhost:8080"
TEST_USER="user-script-e2e"

echo -e "${BLUE}====================================================${NC}"
echo -e "${BLUE}  TURN Credential Platform — Docker E2E Verification  ${NC}"
echo -e "${BLUE}====================================================${NC}"
echo -e "Using Compose File: ${YELLOW}${COMPOSE_FILE}${NC}"

# Step 1: Package JAR
echo -e "\n${BLUE}[1/5] Building application JAR...${NC}"
if [ -f "./mvnw" ]; then
    ./mvnw clean package -DskipTests
else
    mvn clean package -DskipTests
fi

# Step 2: Start Docker Compose
echo -e "\n${BLUE}[2/5] Starting Docker Compose stack (${COMPOSE_FILE})...${NC}"
docker compose -f "${COMPOSE_FILE}" up -d --build

# Step 3: Wait for Health Check
echo -e "\n${BLUE}[3/5] Waiting for application health check at ${BASE_URL}/actuator/health...${NC}"
MAX_RETRIES=30
RETRY_COUNT=0
HEALTHY=false

while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/actuator/health" || true)
    if [ "$HTTP_CODE" -eq 200 ]; then
        HEALTHY=true
        break
    fi
    RETRY_COUNT=$((RETRY_COUNT + 1))
    echo "  Waiting for app container... (attempt $RETRY_COUNT/$MAX_RETRIES, status: $HTTP_CODE)"
    sleep 2
done

if [ "$HEALTHY" = false ]; then
    echo -e "${RED}ERROR: App container failed to become healthy within timeout.${NC}"
    docker compose -f "${COMPOSE_FILE}" logs app --tail 50
    exit 1
fi
echo -e "${GREEN}✓ Application is UP and healthy!${NC}"

# Step 4: Run E2E Verification Scenario
echo -e "\n${BLUE}[4/5] Running End-to-End API Verification Flow...${NC}"

RANDOM_SUFFIX=$(date +%s)
REALM="e2e-script-${RANDOM_SUFFIX}.turn.yourplatform.com"

# 4.1 Onboard Tenant
echo -n "  1. Admin Onboard Tenant (${REALM})... "
CREATE_RES=$(curl -s -i -X POST "${BASE_URL}/v1/admin/tenants" \
  -H "X-Admin-Api-Key: ${ADMIN_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"Script E2E Corp\",\"realm\":\"${REALM}\"}")

HTTP_STATUS=$(echo "$CREATE_RES" | grep -i "^HTTP" | head -1 | awk '{print $2}')
if [ "$HTTP_STATUS" -ne 201 ]; then
    echo -e "${RED}FAILED (HTTP $HTTP_STATUS)${NC}"
    echo "$CREATE_RES"
    exit 1
fi

BODY=$(echo "$CREATE_RES" | sed -n '/^\r\{0,1\}$/,$p' | tail -n +2)
TENANT_ID=$(echo "$BODY" | grep -o '"tenantId":"[^"]*' | cut -d'"' -f4)
API_KEY=$(echo "$BODY" | grep -o '"apiKey":"[^"]*' | cut -d'"' -f4)
echo -e "${GREEN}PASSED (Tenant ID: ${TENANT_ID})${NC}"

# 4.2 Test Unregistered User Rejection (403)
echo -n "  2. Credential Issuance for Unregistered User... "
UNREG_RES=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}/v1/turn-credentials" \
  -H "X-Api-Key: ${API_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"${TEST_USER}\"}")

if [ "$UNREG_RES" -ne 403 ]; then
    echo -e "${RED}FAILED (Expected 403, got ${UNREG_RES})${NC}"
    exit 1
fi
echo -e "${GREEN}PASSED (HTTP 403 Forbidden)${NC}"

# 4.3 Register User
echo -n "  3. Admin Pre-Register User (${TEST_USER})... "
REG_RES=$(curl -s -i -X POST "${BASE_URL}/v1/admin/tenants/${TENANT_ID}/users" \
  -H "X-Admin-Api-Key: ${ADMIN_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"${TEST_USER}\"}")

HTTP_STATUS=$(echo "$REG_RES" | grep -i "^HTTP" | head -1 | awk '{print $2}')
if [ "$HTTP_STATUS" -ne 201 ]; then
    echo -e "${RED}FAILED (HTTP $HTTP_STATUS)${NC}"
    exit 1
fi
echo -e "${GREEN}PASSED (HTTP 201 Created)${NC}"

# 4.4 Issue Credential for Registered User (200)
echo -n "  4. Credential Issuance for Registered User... "
CRED_RES=$(curl -s -i -X POST "${BASE_URL}/v1/turn-credentials" \
  -H "X-Api-Key: ${API_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"${TEST_USER}\"}")

HTTP_STATUS=$(echo "$CRED_RES" | grep -i "^HTTP" | head -1 | awk '{print $2}')
if [ "$HTTP_STATUS" -ne 200 ]; then
    echo -e "${RED}FAILED (HTTP $HTTP_STATUS)${NC}"
    exit 1
fi

CRED_BODY=$(echo "$CRED_RES" | sed -n '/^\r\{0,1\}$/,$p' | tail -n +2)
USERNAME=$(echo "$CRED_BODY" | grep -o '"username":"[^"]*' | cut -d'"' -f4)
PASSWORD_1=$(echo "$CRED_BODY" | grep -o '"password":"[^"]*' | cut -d'"' -f4)
echo -e "${GREEN}PASSED (Username: ${USERNAME})${NC}"

# 4.5 Rotate User Secret
echo -n "  5. Admin Rotate User Secret... "
ROTATE_RES=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}/v1/admin/tenants/${TENANT_ID}/users/${TEST_USER}/rotate-secret" \
  -H "X-Admin-Api-Key: ${ADMIN_KEY}")

if [ "$ROTATE_RES" -ne 204 ]; then
    echo -e "${RED}FAILED (Expected 204, got ${ROTATE_RES})${NC}"
    exit 1
fi
echo -e "${GREEN}PASSED (HTTP 204 No Content)${NC}"

# 4.6 Re-issue Credential Post-Rotation
echo -n "  6. Credential Issuance Post-Rotation (New Secret)... "
CRED_RES_2=$(curl -s -i -X POST "${BASE_URL}/v1/turn-credentials" \
  -H "X-Api-Key: ${API_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"${TEST_USER}\"}")

HTTP_STATUS=$(echo "$CRED_RES_2" | grep -i "^HTTP" | head -1 | awk '{print $2}')
if [ "$HTTP_STATUS" -ne 200 ]; then
    echo -e "${RED}FAILED (HTTP $HTTP_STATUS)${NC}"
    exit 1
fi

CRED_BODY_2=$(echo "$CRED_RES_2" | sed -n '/^\r\{0,1\}$/,$p' | tail -n +2)
PASSWORD_2=$(echo "$CRED_BODY_2" | grep -o '"password":"[^"]*' | cut -d'"' -f4)

if [ "$PASSWORD_1" = "$PASSWORD_2" ]; then
    echo -e "${RED}FAILED (Password signature did not change after rotation)${NC}"
    exit 1
fi
echo -e "${GREEN}PASSED (New Password Signature Generated)${NC}"

# 4.7 Deregister User
echo -n "  7. Admin Deregister (Suspend) User... "
DEREG_RES=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "${BASE_URL}/v1/admin/tenants/${TENANT_ID}/users/${TEST_USER}" \
  -H "X-Admin-Api-Key: ${ADMIN_KEY}")

if [ "$DEREG_RES" -ne 204 ]; then
    echo -e "${RED}FAILED (Expected 204, got ${DEREG_RES})${NC}"
    exit 1
fi
echo -e "${GREEN}PASSED (HTTP 204 No Content)${NC}"

# 4.8 Verify Post-Deregistration Issuance Rejection (403)
echo -n "  8. Credential Issuance Post-Deregistration... "
POST_DEREG_RES=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}/v1/turn-credentials" \
  -H "X-Api-Key: ${API_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"${TEST_USER}\"}")

if [ "$POST_DEREG_RES" -ne 403 ]; then
    echo -e "${RED}FAILED (Expected 403, got ${POST_DEREG_RES})${NC}"
    exit 1
fi
echo -e "${GREEN}PASSED (HTTP 403 Forbidden)${NC}"

# 4.9 Verify Unknown User Rotation Error (404)
echo -n "  9. Admin Secret Rotation for Unknown User... "
UNKNOWN_ROT_RES=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}/v1/admin/tenants/${TENANT_ID}/users/unknown-ghost-user/rotate-secret" \
  -H "X-Admin-Api-Key: ${ADMIN_KEY}")

if [ "$UNKNOWN_ROT_RES" -ne 404 ]; then
    echo -e "${RED}FAILED (Expected 404, got ${UNKNOWN_ROT_RES})${NC}"
    exit 1
fi
echo -e "${GREEN}PASSED (HTTP 404 Not Found)${NC}"

# Step 5: Summary
echo -e "\n${BLUE}[5/5] Verification Results Summary${NC}"
echo -e "${GREEN}====================================================${NC}"
echo -e "${GREEN}  ALL 9 END-TO-END E2E API VERIFICATIONS PASSED!    ${NC}"
echo -e "${GREEN}====================================================${NC}"
