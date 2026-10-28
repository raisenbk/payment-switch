#!/usr/bin/env sh
# Sends an HMAC-signed request to the local payment switch as the demo merchant.
#
#   ./scripts/signed-curl.sh GET /v1/me
#   ./scripts/signed-curl.sh POST /v1/transactions '{"merchantRef":"order-1","productCode":"PULSA10","destination":"081234567890"}' order-1
#   ./scripts/signed-curl.sh GET /v1/transactions/<id>
#
# Arguments: METHOD PATH [BODY] [IDEMPOTENCY_KEY]
# Override API_KEY, SECRET or BASE_URL via environment variables.
set -eu

API_KEY=${API_KEY:-pk_demo_local}
SECRET=${SECRET:-sk_demo_local_only_not_secret}
BASE_URL=${BASE_URL:-http://localhost:8080}

METHOD=$1
REQUEST_PATH=$2
BODY=${3:-}
IDEMPOTENCY_KEY=${4:-}

TIMESTAMP=$(date +%s)
BODY_HASH=$(printf '%s' "$BODY" | openssl dgst -sha256 -hex | awk '{print $NF}')
SIGNATURE=$(printf '%s\n%s\n%s\n%s' "$METHOD" "$REQUEST_PATH" "$TIMESTAMP" "$BODY_HASH" \
  | openssl dgst -sha256 -hmac "$SECRET" -hex | awk '{print $NF}')

if [ -n "$BODY" ]; then
  curl -sS -X "$METHOD" "$BASE_URL$REQUEST_PATH" \
    -H "Content-Type: application/json" \
    -H "X-Api-Key: $API_KEY" -H "X-Timestamp: $TIMESTAMP" -H "X-Signature: $SIGNATURE" \
    -H "Idempotency-Key: ${IDEMPOTENCY_KEY:-$(uuidgen 2>/dev/null || date +%s)}" \
    -d "$BODY"
else
  curl -sS -X "$METHOD" "$BASE_URL$REQUEST_PATH" \
    -H "X-Api-Key: $API_KEY" -H "X-Timestamp: $TIMESTAMP" -H "X-Signature: $SIGNATURE"
fi
echo
