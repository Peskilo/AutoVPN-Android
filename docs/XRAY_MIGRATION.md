# Xray migration prototype

## Goal

Evaluate a clean Xray-based Android client as the new base for SayanVPN, while preserving our own UI and the two differentiators:

- **Автовыбор** — choose a node by end-to-end usability, not TCP latency alone.
- **Авторус** — keep our subscription/config compatibility logic and explicitly report unsupported transports/plugins instead of silently pretending they work.

This branch is a migration spike. It does not replace the production branch and does not claim the core has already been migrated.

## Candidate evaluated first: LibreXrayVPN

Repository: https://github.com/Ko4Learner/LibreXrayVPN

The upstream README describes Kotlin + Jetpack Compose, an MVI architecture, Hilt, server latency tests, subscription/config import, and an Android `VpnService` built on Xray through AndroidLibXrayLite.

Important constraints found in its README/build file:

- License: GPL-3.0-or-later. A distributed derivative must preserve license notices and provide the corresponding source under compatible terms.
- The Android app declares `minSdk = 29` (Android 10).
- Runtime files are not all stored in Git: `libs/libv2ray.aar`, source JAR, and ABI-specific `libtun2socks.so` are expected from upstream releases/dependencies.
- The repository uses multiple Gradle modules. Importing selected modules into the current project may be harder and less reliable than building the upstream app as a baseline and migrating UI/features deliberately.

This is a candidate, not yet an adopted codebase. Before copying source, verify the current default branch, latest release, license files, CI, native artifacts, and whether a reproducible build can be made from a clean checkout.

## Proposed migration sequence

1. **Reproducible baseline:** build the unmodified upstream app for `arm64-v8a` in CI and verify its Xray runtime/native libraries are actually present.
2. **Sayan identity and UI:** replace branding/home screen only after baseline works; retain upstream license and notices.
3. **Авторус:** port the existing subscription/config normalization and unsupported-feature reporting; add tests for VLESS/REALITY, VMess, Trojan, Shadowsocks and transport edge cases.
4. **Автовыбор:** test candidate nodes concurrently with bounded timeouts. Use a real end-to-end HTTP probe through the tunnel, not a TCP ping alone. Record separate stages: TCP reachable, protocol handshake, tunnel ready, HTTP probe success.
5. **Failover:** if the selected node fails its end-to-end check, try the next candidate with a bounded retry budget and avoid repeatedly choosing recently failed nodes.
6. **Diagnostics:** keep the opt-in local journal, redact credentials and full proxy URIs, and never upload logs automatically.
7. **Regression/build:** test Android 10+ and arm64-v8a first; only then expand ABI coverage and create a test APK.

## Acceptance criteria

- A clean CI build produces a real installable APK with all required native libraries.
- A failed node is not reported as internet-ready merely because a TCP connect or latency check succeeded.
- Автовыбор selects a node only after the end-to-end probe succeeds.
- Авторус either imports a configuration correctly or shows a specific unsupported-feature reason.
- No secrets, UUIDs, full subscription URLs, or visited domains appear in exported diagnostics.
- The existing `main` branch remains unchanged until the new baseline is demonstrably buildable and testable.

## Current status

- [x] Inspected the candidate README and Android Gradle configuration.
- [x] Identified license and native-runtime constraints.
- [ ] Build upstream baseline in CI.
- [ ] Port SayanVPN UI and Авторус.
- [ ] Implement end-to-end Автовыбор.
- [ ] Produce and install a test APK.

No migration build or APK is claimed by this document.
