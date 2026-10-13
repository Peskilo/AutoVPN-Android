# AutoVPN — Android VPN client

**English** · [Русский](README.ru.md)

> **🚧 Under development.** The app works, but it is not finished or released.
> The design is not final and the interface and features may change at any time.
> Expect rough edges. Not on Google Play yet.

AutoVPN is an Android VPN/proxy client built on the **sing-box** core (libbox). Add a
subscription link, pick a server, tap connect. Kotlin, Jetpack Compose
(Material 3), coroutines/Flow, Hilt, Room, DataStore, OkHttp.

It works with [Remnawave](https://docs.rw/) panels and with generic
subscriptions made of share links (VLESS, VMess, Trojan, Shadowsocks,
Hysteria2, TUIC).


## What it does

- **Subscriptions:** import by URL, share link or QR code; periodic
  auto-refresh using the provider's interval; usage, expiry and support links
  from the provider are shown. Imported data is treated as untrusted (size
  limits, typed parse errors), and a failed refresh never overwrites a working
  subscription.
- **Servers:** search, filter, favourites, latency test, "Auto" mode.
- **Routing:** proxy everything, bypass Russian sites, or proxy only blocked
  sites; per-app VPN; LAN bypass; DNS mode and upstream; custom rules.
- **Connection:** live speed graph, traffic totals, connection counts, health
  checks (IP check and DNS probe), reconnect on network change, restore after
  reboot, Quick Settings tile, Always-on VPN guidance.
- **Privacy:** HWID is sent to the provider only after explicit consent;
  diagnostics export is redacted; secrets (subscription URLs, UUIDs,
  passwords) are not logged.
- **App:** English and Russian, light/dark/system theme, dynamic colour.

The UI shows only what the connection state machine reports; there is no
decorative "Connected".

## Status

Under active development. The client works and runs on the author's phone, but is not finished and not on Google Play.
Still open: first-run onboarding, a published privacy policy URL, and a
decision on the artwork/mascot licence.

Verified on a physical phone (Android 16) and an emulator; the build and
JVM unit tests (parsers, classifier, redaction, state machine) run in CI.
Anything touching the VPN path is checked on a device, not just in unit tests.

## Downloading a CI test APK

1. Open [Android CI runs](https://github.com/Peskilo/AutoVPN-Android/actions/workflows/android-ci.yml) and choose the latest successful run on `main`.
2. In **Artifacts**, download only the archive matching the phone ABI: `autovpn-debug-arm64-v8a` (most modern phones) or `autovpn-debug-armeabi-v7a` (older 32-bit devices). `x86` and `x86_64` are usually for emulators.
3. Choose the APK matching the device ABI. `arm64-v8a` is typical for modern Android phones; `armeabi-v7a` is for older 32-bit ARM devices; `x86_64` and `x86` are mainly for emulators.
4. Check the APK against the matching entry in `SHA256SUMS.txt` before installing.

These are debug-signed test builds, not an official release. Only install them if you trust the source. Do not ignore an antivirus warning or disable protection to install an APK; stop and review the specific detection first.

## Architecture

`ConnectionManager` owns the connection state machine and is the only thing the
UI talks to. The sing-box/libbox types live behind a `VpnEngine` boundary in
`core.engine.singbox`; swapping the core would not touch the UI. Subscriptions go
through a fetch → classify → parse → validate → commit pipeline.

## Build

```bash
./gradlew assembleDebug   # downloads + checksum-verifies libbox.aar first
./gradlew test            # JVM unit tests (parsers, classifier, redaction)
./gradlew lint
```

The libbox AAR is fetched at build time from the pinned `singbox-android/libbox`
GitHub release and verified against a SHA-256 recorded in `app/build.gradle.kts`.
Core version lives in `gradle/libs.versions.toml` as `vpnCore`.

Requirements (from the build files, not from memory):

- **JDK 25 to run Gradle.** `gradle/gradle-daemon-jvm.properties` pins the
  daemon to Java 25 (`toolchainVersion=25`) and `settings.gradle.kts` applies
  the Foojay resolver, so a missing JDK 25 is provisioned automatically. CI
  (`.github/workflows/android-ci.yml`) uses Temurin 25. The compiled bytecode
  targets Java 11 (`compileOptions` in `app/build.gradle.kts`).
- Gradle 9.5.0 (wrapper, checksum-pinned), AGP 9.3.3, Kotlin 2.2.10.
- Android SDK platform 37 (`compileSdk`/`targetSdk` 37), `minSdk` 26.
- Network access on the first build to fetch the pinned libbox AAR.

Never run `connected*` Gradle tasks on a physical device — they uninstall the app and erase its data.

## Layout

```
app/src/main/java/dev/typenil/vpnclient/
├── core/
│   ├── common/log/          # SecureLog + Redactor (no secrets in logs)
│   ├── engine/              # VpnEngine boundary (no libbox types)
│   │   └── singbox/         # libbox adapter, platform bridge, config compiler
│   ├── subscription/        # fetch → classify → parse pipeline
│   │   └── model/           # ProxyNode, SubscriptionProfile, errors
│   └── vpn/                 # ClientVpnService, ConnectionManager, state machine
├── data/                    # Room db, DataStore settings, NodeConfigProvider
├── di/                      # Hilt modules
└── ui/                      # Compose screens + viewmodels
```

## License

GPL-3.0 — required by the libbox dependency. See `docs/LICENSING.md`.
