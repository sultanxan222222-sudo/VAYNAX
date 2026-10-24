# VAYNAX backend (early alpha)

This service provides account tokens, short-lived invite codes, WebSocket signaling, and a durable queue for **client-encrypted payloads**. It does not encrypt message text itself. A production client must encrypt on-device before sending; do not send plaintext.

## Endpoints
- `GET /health` health check
- `POST /v1/register` JSON `{"name":"display name"}`; returns account ID and a bearer token (store token securely on-device)
- `GET /v1/me` authenticated profile
- `POST /v1/invites` authenticated; creates a 7-day invite code
- `POST /v1/invites/redeem` authenticated JSON `{"code":"..." }`
- `GET /v1/messages/pending` authenticated pending ciphertexts
- `POST /v1/messages/ack` authenticated JSON `{"ids":["..."]}`
- WebSocket `/v1/socket?token=...`; client sends `{"type":"message","recipientId":"...","ciphertext":"..."}`

## Deploy
The Render blueprint is in `render.yaml`. Import this repository into Render and deploy the blueprint; keep the persistent disk mounted at `/data`. After deployment, configure the HTTPS/WSS base URL in the Android and iOS clients. No server is considered live until a real deployment URL passes `/health`.

## Security and limitations
This is an initial relay, not yet a production-grade secure messenger. Before public use, add rate limits, abuse controls, token revocation/rotation, invitation brute-force protection, group membership rules, audited end-to-end encryption, push notifications, and WebRTC signaling/TURN. SQLite stores queued ciphertexts until acknowledged; set retention limits and backups deliberately. Never log tokens or message bodies. WebSocket auth currently uses a query token and must be protected by TLS in deployment.
