# VAYNAX

Private-circle Android messenger concept with Chechen-inspired visual identity.

## Planned features
- Profile by display name and avatar; no phone-number registration or SMS verification.
- Invite code / QR friend adding, personal and group chats.
- Encrypted local message history and end-to-end encrypted messaging.
- Offline queue on sender device, delivery status, reconnection when users become reachable.
- Internet audio and video calls.
- Emerald, white, red and gold visual identity; Chechen watchtower and snow-covered Caucasus mountains.
- Android 13–16 compatibility target; this must be verified on real devices before claiming support.

## Important status
This repository currently contains the **initial Android application scaffold**, not a finished messenger. Messaging, cryptographic protocol, rendezvous/signaling service, relay, and WebRTC calling still require implementation and testing. Calls will require signaling and likely a TURN relay for reliable connections across mobile networks/NAT. Chat content should not be stored by the signaling service.

## Build
A GitHub Actions workflow builds a debug APK and publishes it as a workflow artifact. Open **Actions → Android debug APK → latest successful run → Artifacts**.

## Security principles
- Never invent or hard-code private keys.
- Use reviewed cryptographic libraries and established end-to-end encryption protocols.
- Request microphone/camera permissions only when needed.
- Do not log message contents, private keys, or call media.
