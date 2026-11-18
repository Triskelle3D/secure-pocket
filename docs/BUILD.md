# Secure Pocket v0 — build & verification record

Date: 2026-11-19. Device: emulator-5554 (API local AVD). Repo owner: Matt. Author: Cairn (matts-agent).

## What v0 is
Air-gapped pocket-recorder companion app. USB transport simulated by an in-process
scripted Simulator (never touches network). Cloud AI egress: none — no network code paths exist.
UI: "Obsidian Whisper" design system (see stitch_pocket_recorder/obsidian_whisper/DESIGN.md),
strict dark, JetBrains Mono telemetry + Plus Jakarta Sans display, Emerald = live/isolated only,
Crimson = recording/violation only. Three tabs: DEVICE (record trigger lives here), LIBRARY, SETTINGS.

## Toolchain (proven, 2026-11-19)
- Build: `JAVA_HOME=$PWD/tools-dist/jdk17/Contents/Home ./gradlew :app:assembleDebug --console=plain --no-configuration-cache -q`
  (Gradle wrapper 9.6.0; AGP 9.5.1 requires Gradle >= 9.6.0. Compose BOM 2026.03.01. Min/target SDK 30+.)
- Install: `~/.local/bin/android run --apks=app/build/outputs/apk/debug/app-debug.apk --device emulator-5554 --activity MainActivity`
- Screenshot: `~/.local/bin/android screen capture --device emulator-5554 --output <abs.png>`
- Fresh APK evidence: app/build/outputs/apk/debug/app-debug.apk, 64,063,106 B, 2026-11-19 10:03.

## Tests (unit)
14/14 green: ProtocolTest 7, CaptureVaultTest 3, RoundTripTest 2, MainScreenViewModelTest 2.
Evidence: app/build/test-results/testDebugUnitTest/*.xml (failures="0" errors="0").
RoundTripTest drives App <-> Simulator over the real transport channel incl. capture commit.

## On-device proof (PNGs, artifacts/ in pocket-ai session dir)
- emu_20_device.png — reskin renders, 3-tab nav, record ring + micro-bars, IDLE.
- emu_22_synced.png — READY, battery 97% emerald (split-reply reassembly fix: 'MCU&BAT&'+'97'
  across two notifies now parked and joined — old -1% bug gone on device), AIR-GAPPED pill, nets 0.
- emu_23/24 — record START/STOP: capture committed to vault, 24,000 B.
- emu_26_library_fixed.png — Library row shows clean `10:06 · 2026-11-19` (toString leak fixed),
  exactly two filter modes TRANSCRIPTIONS/SUMMARIES per design ruling.
- emu_27_settings.png — Zero egress page: all cloud providers DISABLED · v1, TRANSPORT SIMULATOR.
- Crash buffer clean since PocketApp manifest fix (android:name=".PocketApp").

## Known gaps (honest list)
1. **Verification stuck at NOT_VERIFIED/NOT_YET**: app never sends `APP&U&<date>&<ts14>` size
   query, so deviceExpectedBytes is null at commit. Cannot be fixed naively: while
   phase==CAPTURING, RESPONSE-channel bytes feed the audio sink (audio shares the RESPONSE
   channel; terminator is the MCU&STO marker scan), so a mid-capture query reply would
   corrupt the mp3. Correct fix = two-phase commit: commit with null, then send APP&U& after
   MCU&STO, update sidecar deviceBytes, re-verify, refresh UI (needs small VaultStore
   setExpected API). v0.1 scope.
2. BUILD/VER/UPLE health rows render raw protocol strings (cosmetic).
3. "1 RECORDINGS" grammar nit (plural not handled).
4. Room DB index later — v0 vault is JSON sidecars + files on app storage.
5. Protocol padding rules marked TBD in PROTOCOL.md; Commands.encode has a NEVER_SEND guard
   (OTA/OT/UPL/UPLDROP/UPAUTH/UPKEY/ENCPK/SCHED/SYNC/LOG/LOGF/LOGACK refused at encode time).
6. Transcripts/summaries: placeholders; on-device Whisper lands v1.

## Provenance
- Protocol ground truth: docs/PROTOCOL.md + docs/PROTOCOL_CMDS.md (from ~/repos/pocket-ai/docs, mirrored here 2026-11-19).
- Fonts: Plus Jakarta Sans + JetBrains Mono variable TTFs vendored from google/fonts (OFL),
  licenses in third_party/fonts/.
- Design: stitch_pocket_recorder/obsidian_whisper (Matt directive 2026-11-19 — this IS the going-forward UI).
