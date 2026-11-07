# VAYNAX HYBRID — Android client

This is a separate Android app module in the VAYNAX repository. It does not replace the VAYNAX Messenger app.

## Current implementation
- Native Android app with Russian interface and Chechen-flag palette.
- Embedded official WireGuard Android tunnel library.
- Imports a user-provided WireGuard `.conf` profile.
- Requests Android's VPN permission and starts/stops a real userspace WireGuard tunnel.
- Does not request root access or modify device firmware.

## Important
The app cannot connect without a valid WireGuard server profile. No public relay is bundled or claimed to be operational. The adaptive multi-route engine, relay discovery, failover and shared hotspot routing are not implemented yet. Those features require separate engineering and real network tests.

## Build
GitHub Actions builds `hybrid/build/outputs/apk/debug/hybrid-debug.apk` for branch `hybrid/vpn-client`.
