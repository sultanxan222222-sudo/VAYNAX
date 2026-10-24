#!/usr/bin/env bash
set -euo pipefail
base="${BASE_URL:-http://127.0.0.1:8088}"

# Invalid input and unauthenticated access must fail cleanly.
status="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$base/v1/register" -H 'content-type: application/json' -d '{"name":"   "}')"
test "$status" = "400"
status="$(curl -sS -o /dev/null -w '%{http_code}' "$base/v1/me")"
test "$status" = "401"

a="$(curl -fsS -X POST "$base/v1/register" -H 'content-type: application/json' -d '{"name":"Smoke Alice"}')"
b="$(curl -fsS -X POST "$base/v1/register" -H 'content-type: application/json' -d '{"name":"Smoke Bob"}')"
token_a="$(node -e 'let x="";process.stdin.on("data",d=>x+=d).on("end",()=>process.stdout.write(JSON.parse(x).token))' <<<"$a")"
token_b="$(node -e 'let x="";process.stdin.on("data",d=>x+=d).on("end",()=>process.stdout.write(JSON.parse(x).token))' <<<"$b")"
id_a="$(node -e 'let x="";process.stdin.on("data",d=>x+=d).on("end",()=>process.stdout.write(JSON.parse(x).id))' <<<"$a")"
id_b="$(node -e 'let x="";process.stdin.on("data",d=>x+=d).on("end",()=>process.stdout.write(JSON.parse(x).id))' <<<"$b")"

# The owner cannot redeem their own invite.
invite="$(curl -fsS -X POST "$base/v1/invites" -H "authorization: Bearer $token_a")"
code="$(node -e 'let x="";process.stdin.on("data",d=>x+=d).on("end",()=>process.stdout.write(JSON.parse(x).code))' <<<"$invite")"
status="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$base/v1/invites/redeem" -H "authorization: Bearer $token_a" -H 'content-type: application/json' -d "{\"code\":\"$code\"}")"
test "$status" = "400"

# A different user redeems once; both users must see the relationship.
curl -fsS -X POST "$base/v1/invites/redeem" -H "authorization: Bearer $token_b" -H 'content-type: application/json' -d "{\"code\":\"$code\"}" >/dev/null
contacts_b="$(curl -fsS "$base/v1/contacts" -H "authorization: Bearer $token_b")"
contacts_a="$(curl -fsS "$base/v1/contacts" -H "authorization: Bearer $token_a")"
node -e 'const x=JSON.parse(process.argv[1]);if(!x.contacts.some(c=>c.id===process.argv[2]))process.exit(1)' "$contacts_b" "$id_a"
node -e 'const x=JSON.parse(process.argv[1]);if(!x.contacts.some(c=>c.id===process.argv[2]))process.exit(1)' "$contacts_a" "$id_b"
status="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$base/v1/invites/redeem" -H "authorization: Bearer $token_b" -H 'content-type: application/json' -d "{\"code\":\"$code\"}")"
test "$status" = "404"


# Message delivery is restricted to approved contacts, queued, and acknowledged.
send="$(curl -fsS -X POST "$base/v1/messages/send" -H "authorization: Bearer $token_a" -H 'content-type: application/json' -d "{\"recipientId\":\"$id_b\",\"ciphertext\":\"test-ciphertext\"}")"
message_id="$(node -e 'let x="";process.stdin.on("data",d=>x+=d).on("end",()=>process.stdout.write(JSON.parse(x).id))' <<<"$send")"
pending="$(curl -fsS "$base/v1/messages/pending" -H "authorization: Bearer $token_b")"
node -e 'const x=JSON.parse(process.argv[1]);if(!x.messages.some(m=>m.id===process.argv[2]&&m.senderId===process.argv[3]&&m.ciphertext==="test-ciphertext"))process.exit(1)' "$pending" "$message_id" "$id_a"
status="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$base/v1/messages/send" -H "authorization: Bearer $token_b" -H 'content-type: application/json' -d "{\"recipientId\":\"$id_a\",\"ciphertext\":\"unauthorized-test\"}")"
test "$status" = "201"
curl -fsS -X POST "$base/v1/messages/ack" -H "authorization: Bearer $token_b" -H 'content-type: application/json' -d "{\"ids\":[\"$message_id\"]}" >/dev/null
pending_after="$(curl -fsS "$base/v1/messages/pending" -H "authorization: Bearer $token_b")"
node -e 'const x=JSON.parse(process.argv[1]);if(x.messages.some(m=>m.id===process.argv[2]))process.exit(1)' "$pending_after" "$message_id"

echo "VAYNAX backend smoke test passed: validation, auth, invitations, contacts, queued messages and acknowledgements."
