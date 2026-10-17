#!/usr/bin/env bash
# Walks through every feature against the two running instances.
# Usage: start the service (docker compose up), then run ./scripts/demo.sh
# Needs: bash, curl, openssl. Reads JWT_SECRET / WEBHOOK_SECRET from .env.
set -euo pipefail

cd "$(dirname "$0")/.."
if [ -f .env ]; then set -a; . ./.env; set +a; fi
: "${JWT_SECRET:?set JWT_SECRET (or create .env from .env.example)}"
: "${WEBHOOK_SECRET:?set WEBHOOK_SECRET (or create .env from .env.example)}"
A="${INSTANCE_A:-http://localhost:8080}"
B="${INSTANCE_B:-http://localhost:8081}"

bold() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
uuid() { uuidgen 2>/dev/null | tr 'A-Z' 'a-z' || cat /proc/sys/kernel/random/uuid; }
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
field() { sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p"; }

# There is no login endpoint: we mint HS256 tokens with the shared secret.
token() { # token <user-id> <role> [restaurant-id]
  local exp=$(( $(date +%s) + 3600 )) extra=""
  [ -n "${3:-}" ] && extra=",\"restaurant_id\":\"$3\""
  local h p s
  h=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
  p=$(printf '{"sub":"%s","role":"%s"%s,"exp":%s}' "$1" "$2" "$extra" "$exp" | b64url)
  s=$(printf '%s' "$h.$p" | openssl dgst -sha256 -hmac "$JWT_SECRET" -binary | b64url)
  printf '%s.%s.%s' "$h" "$p" "$s"
}

# Signs like the payment provider: HMAC-SHA256 over "<t>.<raw body>".
signature() {
  local t; t=$(date +%s)
  printf 't=%s,v1=%s' "$t" "$(printf '%s.%s' "$t" "$1" | openssl dgst -sha256 -hmac "$WEBHOOK_SECRET" -hex | sed 's/^.* //')"
}

call() { # call <METHOD> <URL> [curl args...]; prints status + body, sets $BODY
  local method=$1 url=$2; shift 2
  local out; out=$(curl -s -i -X "$method" "$url" "$@")
  BODY=$(printf '%s' "$out" | tail -n 1)
  printf '%s\n' "$out" | grep -E '^HTTP/|^Idempotent-Replayed' | tr -d '\r'
  printf '%s\n' "$BODY"
}

RESTAURANT=$(uuid)
ADMIN=$(token "$(uuid)" restaurant_admin "$RESTAURANT")
ALICE_ID=$(uuid); ALICE=$(token "$ALICE_ID" customer)
BOB=$(token "$(uuid)" customer)
JSON=(-H "Content-Type: application/json")

bold "1. Restaurant admin creates a deal with 3 units (instance A, :8080)"
call POST "$A/v1/deals" "${JSON[@]}" -H "Authorization: Bearer $ADMIN" \
  -d '{"title":"Chicken biryani box","price_paise":9900,"stock_total":3,"starts_at":"2020-01-01T00:00:00Z","ends_at":"2099-01-01T00:00:00Z"}'
DEAL=$(printf '%s' "$BODY" | field id)

bold "2. A customer reads it from the OTHER instance (B, :8081) - same database"
call GET "$B/v1/deals/$DEAL" -H "Authorization: Bearer $BOB"

bold "3. Alice claims 2 units with Idempotency-Key alice-1"
call POST "$A/v1/deals/$DEAL/claims" "${JSON[@]}" -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: alice-1" -d '{"quantity":2}'
CLAIM=$(printf '%s' "$BODY" | field id)

bold "4. Her phone retries the same request on instance B -> replayed, no second claim"
call POST "$B/v1/deals/$DEAL/claims" "${JSON[@]}" -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: alice-1" -d '{"quantity":2}'

bold "5. Same key, different quantity -> 422 IDEMPOTENCY_KEY_REUSED"
call POST "$A/v1/deals/$DEAL/claims" "${JSON[@]}" -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: alice-1" -d '{"quantity":1}'

bold "6. Alice tries a second claim with a new key -> 409 ALREADY_CLAIMED"
call POST "$A/v1/deals/$DEAL/claims" "${JSON[@]}" -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: alice-2" -d '{"quantity":1}'

bold "7. Bob wants 2, only 1 left -> 409 SOLD_OUT"
call POST "$B/v1/deals/$DEAL/claims" "${JSON[@]}" -H "Authorization: Bearer $BOB" -H "Idempotency-Key: bob-1" -d '{"quantity":2}'

bold "8. Bob tries to read Alice's claim -> 404 (not 403: he can't even learn it exists)"
call GET "$A/v1/claims/$CLAIM" -H "Authorization: Bearer $BOB"

bold "9. No token / customer creating a deal -> 401 / 403"
call GET "$A/v1/deals/$DEAL"
call POST "$A/v1/deals" "${JSON[@]}" -H "Authorization: Bearer $BOB" -d '{}'

EVENT="{\"event_id\":\"evt_$(uuid)\",\"type\":\"payment.succeeded\",\"claim_id\":\"$CLAIM\",\"amount_paise\":19800,\"occurred_at\":\"2026-10-20T12:03:11Z\"}"

bold "10. Webhook with a forged signature -> 401"
call POST "$A/v1/webhooks/payments" "${JSON[@]}" -H "X-Payment-Signature: t=$(date +%s),v1=$(printf '0%.0s' {1..64})" -d "$EVENT"

bold "11. Correctly signed payment -> claim becomes PAID"
call POST "$A/v1/webhooks/payments" "${JSON[@]}" -H "X-Payment-Signature: $(signature "$EVENT")" -d "$EVENT"

bold "12. Provider delivers the same event again (other instance) -> DUPLICATE, no change"
call POST "$B/v1/webhooks/payments" "${JSON[@]}" -H "X-Payment-Signature: $(signature "$EVENT")" -d "$EVENT"

bold "13. Alice checks her claim"
call GET "$B/v1/claims/$CLAIM" -H "Authorization: Bearer $ALICE"

bold "14. Flash crowd: 100 customers claim a 10-unit deal at once, across both instances"
call POST "$A/v1/deals" "${JSON[@]}" -H "Authorization: Bearer $ADMIN" \
  -d '{"title":"Viral deal","price_paise":4900,"stock_total":10,"starts_at":"2020-01-01T00:00:00Z","ends_at":"2099-01-01T00:00:00Z"}' > /dev/null
HOT=$(printf '%s' "$BODY" | field id)
RESULTS=$(mktemp)
for i in $(seq 1 100); do
  url=$A; [ $(( i % 2 )) -eq 0 ] && url=$B
  curl -s -o /dev/null -w "%{http_code}\n" -X POST "$url/v1/deals/$HOT/claims" "${JSON[@]}" \
    -H "Authorization: Bearer $(token "$(uuid)" customer)" -H "Idempotency-Key: k$i" -d '{"quantity":1}' >> "$RESULTS" &
done
wait
sort "$RESULTS" | uniq -c | sed 's/^/   /'; rm -f "$RESULTS"
echo "   (expect exactly 10 x 201 and 90 x 409 SOLD_OUT)"
call GET "$A/v1/deals/$HOT" -H "Authorization: Bearer $BOB"

bold "Done. Metrics: curl $A/metrics | grep flashdeals"
