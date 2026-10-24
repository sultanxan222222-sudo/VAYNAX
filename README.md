# VAYNAX

Android messenger prototype with Chechen-inspired visual identity.

## Current status — not production-ready

This repository is an **early UI/API scaffold**, not a working private messenger. The current app uses a configurable central HTTP API for registration, contacts and message queues. It does **not** yet implement phone-to-phone peer discovery, reliable P2P connectivity, end-to-end encryption, secure local history, QR invites, or WebRTC call signaling.

Plain Base64 is encoding, **not encryption**. The app's text-send control is intentionally disabled until real end-to-end encryption is implemented; do not use this prototype for private messages.

## User requirements for the intended version

- No paid central messaging server and no mandatory phone-number/SMS registration.
- Phone-to-phone communication where the network permits it.
- Russian-language interface and a Chechen-inspired emerald, white, red and gold identity.
- Android 13–16 support, to be verified on real devices.
- Work across MegaFon, Beeline and MTS mobile networks, subject to real SIM/network testing.

## Networking reality

Phones on cellular networks are often behind carrier-grade NAT. A device cannot reliably accept a direct incoming connection merely because the app is running. A serverless design needs an explicit way to exchange reachable peer information (for example, manual QR/contact exchange plus reachable IPv6 or local-network discovery). Arbitrary cross-carrier connectivity may still fail. Reliable WebRTC calling commonly needs signaling and sometimes a TURN relay; a TURN relay consumes bandwidth and is not guaranteed to be free. Do not claim universal carrier compatibility until tested.

## Required engineering before release

1. Choose and document a realistic discovery/bootstrap method that does not require a paid central server.
2. Implement authenticated peer discovery and connection establishment, with clear failure states.
3. Implement reviewed end-to-end cryptography with per-device keys, key verification, rotation and safe storage; never invent or hard-code private keys.
4. Encrypt local message history and any queued payloads.
5. Add robust delivery acknowledgements, retries, duplicate suppression and reconnection handling.
6. Add Android foreground/background behavior only with correct user-visible notification and lifecycle handling.
7. Implement WebRTC signaling and determine whether a relay is needed; do not imply calls work before tests pass.
8. Test builds and networking on real Android 13, 14, 15 and 16 devices and SIMs from MegaFon, Beeline and MTS.
9. Run security review and automated tests before enabling message sending.

## Build

The GitHub Actions workflow builds a **debug APK** and uploads it as a workflow artifact when a build is successfully run. A successful compile only proves that the app compiles; it does not prove P2P networking, encryption, background delivery or carrier compatibility. Artifacts are temporary and are not a permanent release download.

A push to `main` is configured to start the Android debug build automatically; `workflow_dispatch` is also enabled for manual runs.

## Security principles

- Never store plaintext chat content in a discovery/signaling service.
- Never log message contents, private keys or call media.
- Request camera and microphone permissions only when needed.
- Be explicit in the UI about prototype limitations and connection failures.
