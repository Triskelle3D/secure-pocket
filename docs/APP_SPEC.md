# Cairn Recorder — Full App Spec (Android, vendor-free Pocket PKT01 client)

v1.0 design draft <PII type="DATE" id="448"/> — wire-verified against PKT01 fw 1.8.9. Every BLE
behavior here was captured live (docs/PROTOCOL.md, docs/HEALTH.md); nothing is assumed.

## 0. Product statement
A local-first recorder companion for the Pocket PKT01 device: records, pulls, stores, plays,
transcribes, summarizes, and extracts to-dos — with NO vendor cloud. The user's own AI keys
(OpenAI / OpenRouter / Anthropic / local models) power text features. The device stays factory
firmware; the app never requires OTA, pairing, or accounts.

Design principles (non-negotiable):
1. Local-first: everything works offline; cloud LLM is opt-in per feature, visibly labeled.
2. Zero egress by default: a persistent network-state badge always tells the truth.
3. The device is the user's: isolation controls are first-class features, not hidden settings.
4. Lossless auditability: every wire exchange and every generated artifact is inspectable.

## 1. BLE layer (verified protocol — implement verbatim)
Transport: BLE GATT, service 001120a0-2233-4455-6677-889912345678, no bonding, no auth.
- Char a2 (UUID …a2, WRITE|WRITE_NO_RESPONSE): send commands, ASCII `APP&CMD&args` (raw '&', zero-pad short writes).
- Char a1 (…a1, NOTIFY): responses AND record audio bytes.
- Char a3 (…a3, NOTIFY): events.
- Resolve characteristics BY UUID. Handles re-enumerate per connection (seen: 0x2B/0x2D/0x30 and 0x2a).
- MTU: request 185 on connect (responses chunk under small MTU).
- Scan filter: name prefix `PKT01_BLUE_`. Remember MAC (dev unit f44b26423747).
- Do NOT bond. Unbonded writes are accepted (proven).

Session sequence on every connect:
1. connect → MTU → discover → enable notifications on a1 + a3 (CCCD 0x2E/0x31).
2. `APP&T&<YYYYMMDDHHMMSS>` → expect `MCU&T&OK`.
3. Health burst: FW, BUILD, VER, WF, BAT, SPACE, MAC, STE, GET&USB, UPLE, WIFIS, LIST_DIRS.
4. Inventory: `APP&LIST&<date>` for today..−N days (default N=14, setting).
5. Optional `APP&SK&<16 random A-Za-z0-9>` — cosmetic; device acks ANY key; default: send, random each connect.

Commands (verified responses):
| Send | Reply |
|---|---|
| APP&SK&<key> | MCU&SK&OK |
| APP&BAT | MCU&BAT&<pct> |
| APP&SPACE | MCU&SPA&<free>&<total> |
| APP&MAC | MCU&MAC&<hex> |
| APP&FW | MCU&FW&1.8.9 |
| APP&BUILD | MCU&BUILD&1.8.9+97&V9+? |
| APP&VER | MCU&VER&26.11.2+97&? |
| APP&WF | MCU&WF&V9 |
| APP&STE | MCU&STE&0 |
| APP&GET&USB | MCU&USB&<0|1> |
| APP&USB&<0|1> | MCU&USB&<same> |
| APP&T&<ts14> | MCU&T&OK |
| APP&UPLE | MCU&UPLE&END&0&10350 |
| APP&WIFIS | MCU&WIFIS&0 [&0] |
| APP&WIFIO / WIFIC / WPING | echoed ack |
| APP&LIST_DIRS | MCU&DIRS&<date> … MCU&DIRS_SUM&<NNN> |
| APP&LIST&<date> | MCU&F&<date>&<ts14>&<secs> ×n, then MCU&LIST&<count> ('0' when empty) |
| APP&U&<date>&<ts14> | MCU&U&<bytes> (file size) |
| APP&STA (after REC&SECEN) | event MCU&STA&<ts14> |
| APP&STO | MCU&STO |
| APP&OT&OVER | MCU&OT&OVER |
| APP&ENC / APP&LOG | MCU&UNKNOWN (never send) |
Events (a3): MCU&REC&CON (slider opened), MCU&REC&CALL (call-mode slider), MCU&STA&<ts14>, MCU&STO, MCU&OFF (sleep).
NEVER send: OTA*, UPL*, UPAUTH*, ENCPK*, SCHED&OTA, SYNC&LEGACY, LOG*, ENC*, BLE&OFF. SHUT behind confirm.

Audio capture rule (proven byte-exact): from the moment `MCU&STA&<ts>` arrives until `MCU&STO`,
append EVERY a1 notification payload verbatim → that file IS a valid .mp3
(MPEG Layer III, CBR 32 kbps, 16 kHz, mono). No parsing, no re-framing. Cross-check byte count
against `APP&U&` answer; mismatch = re-pull (max 2 retries) then surface error.
`APP&SYNC&` replay for stored files is UNVERIFIED (open probe, spec §9) — do not design v1 around it.

## 2. Screens

### 2.1 Home / Device
- Status card: connection state, device name+MAC, FW badge (green "1.8.9 ✓" / amber "changed! was 1.8.9" — alert if FW/VER differ from stored values; settings reset it), battery bar, storage bar (SPA free/total, unit-labelled "device KB"), device clock vs phone clock drift (warn >2 s).
- **Isolation badge (hero element):** green "ISOLATED — no Wi-Fi credentials on device" (WIFIS&0) / red "⚠ device has Wi-Fi settings — it can upload" + one-tap `APP&WIFIP&CLR` (typed-confirm "WIPE" dialog; also clears creds+upload settings). This badge is on EVERY top bar, right of app name.
- Health strip: BUILD/VER/WF/WIFIS/STE/USB/UPLE raw values, pull-to-refresh, last-polled ts.
- Buttons: Connect / Disconnect / Sync now.

### 2.2 Record
- Giant start/stop button. Start: `REC&SECEN` (once per session) → `STA`; while live: elapsed timer (from MCU&STA ts), byte counter (streaming), red dot. Stop: `STO` → verify vs `U&` → toast "<file> saved (24,956 B ✓)".
- Pre-tag field (optional title) + chips (Meeting / Interview / Voice memo / Custom) → saved to sidecar; drives note filename + summary prompt context.
- **Passive capture mode** (toggle, default ON while app foreground): device slider events `REC&CON/STA…STO` auto-captured and saved without opening the screen; notification "Recording captured" with share button.
- Live level meter NOT possible (no audio-level command) — show byte-rate instead, honest UI.

### 2.3 Library
- List grouped by date (device `DIRS` ∪ local), each row: time, length (from F-line secs), size (U&), state chips: [on device] [local ✓ verified] [transcribed ✓ provider] [diarized] , speaker avatars (assigned names), search field (transcript+title+tags), sort, filter.
- Pull-to-refresh = inventory diff; unseen files auto-queued for pull (if SYNC& path proven) or marked "capture next time" (until proven).
- Multi-select → export (share sheet, save-to-folder, ZIP).
- Row long-press menu: Details (wire log for this file), Re-transcribe, Re-summarize, Delete local (confirm), Free device slot (hidden until D& verified; OFF by default).

### 2.4 Recording detail — 4 tabs
Tab **Play**: waveform scrubber (decoded PCM peaks cached), Media3 player, speed 0.5–2×, skip-silence toggle, A-B repeat, prev/next segment arrows, background/lockscreen/notification controls.
Tab **Transcript**: scrolling segments `0:12 You — text`. Tap segment → seek audio there; playing → current segment highlights (karaoke sync). Inline edit on tap-hold. **Speaker assignment:** colored initial chip per segment; tap chip → rename/assign (recents list); "apply to all of this voice" on rename. Diarization source per file: Local (v2, sherpa-onnx) / Deepgram-API (when cloud key) / Manual. Merge/split segment controls. Edit-any-segment dirties summary ("stale" chip until regenerate).
Tab **Summary**: TL;DR + sections Decisions / Open questions / Risks & notes; Regenerate button; prompt-template picker (default / meeting / interview / therapy-style safe); provider+model+tokens+latency footer; Editable result text (edits saved, marked user-edited).
Tab **To-dos**: checkbox list extracted from transcript+summary (JSON action-items schema), owner/date parsing when present; Export: copy-all / Obsidian task lines `-[ ] … 📅` / .ics / share. Completed state persisted.

### 2.5 Settings
Sections:
- **AI provider ("Brains")** — cards, exactly-one active per task, per-task override:
  · Local (whisper.cpp small/medium — transcription; bundled GGUF, model-download screen w/ size + on-device speed test; summary via built-in tiny model? NO — summary local only if user loads a GGUF into shared llama.cpp dir; honest "Local summary: bring-your-own-model" copy)
  · OpenAI (key; stt model whisper-1/gpt-4o-transcribe, chat model gpt-4o/4.1; base URL override)
  · OpenRouter (key; any model incl. Claude/Gemini)
  · Anthropic (key; opus/sonnet/haiku)
  · OpenAI-compatible custom (base URL + key + model — covers Groq, LM Studio, Ollama-LAN)
  · Advanced: Codex/ChatGPT token (paste; labelled "unsupported, may break silently")
  Keys → Android Keystore via EncryptedSharedPreferences; 'Test' button per provider (1-token ping + cost note).
  Kill switches: master "Allow any cloud AI" (OFF default), per-provider enable, per-task provider picker. When cloud active for a task: red network chip on every result card; wire log records endpoint (never key).
- **Sync**: auto-sync on connect (default ON), on-app-launch, scheduled via WorkManager (interval picker, "charging only" option), pull-then-verify byte rule locked ON (labelled), max-retries.
- **Vault**: storage location (app-private or SAF folder — recommend Syncthing/Syncthing-Folder path), filename pattern `Pocket/<YYYY-MM-DD>/<HHMMSS>[_title].mp3`, sidecar JSON always, optional FLAC/PCM upsample export cache for waveform only (never replaces original — original is sacred, SHA-256 in sidecar).
- **Device**: time-sync-every-connect (ON), BAT poll interval (default 30 s foreground, 0 = off background), SHUT button (OFF), BLE&RESET button (OFF, danger-styled), reset-FW-assertion.
- **Privacy**: wire-log retention (days), export-wire-log, clear-all-data, quarantine-reminder (shows official-app install detection warning + "it auto-reconnects" explainer).
- **About**: protocol doc links, device fingerprint (MAC/FW/VER/BUILD), check-for-new-firmware-assertion (never updates — only alerts on change, and only if user connects manually).

### 2.6 Notifications & entry points
- Persistent low-priority "PKT01 connected — local mode" ongoing while session active.
- Recording-captured / transcription-done / sync-needed events.
- Quick tile "Record". Deep-link `cairn://record` from launcher shortcut.
- Optional Wear watch start/stop later.

## 3. Data layer
Room DB: `files(id=date_ts14, date, ts, deviceBytes, localBytes, sha256, lengthSecs, state: DEVICE|LOCAL|TRANSCRIBED, title, tags, providerUsed, diarizationState, createdAt, updatedAt)`; `segments(fileId, idx, start, end, speakerId, text, edited)`; `speakers(fileId, sid, name, color, embeddingRef?)`; `summaries(fileId, kind, text, model, promptId, createdAt, stale)`; `todos(fileId, idx, text, done, dueDate, owner)`; `wirelog(ts, dir: TX|RX|N-TX|N-RX, value, connectionId)`; `events`.
Sidecar JSON mirrors everything for files (vault stays the source of truth, DB is cache) — Mac/Obsidian can consume vault without the app.

## 4. Pipelines (workers)
- PullWorker: connect → session init → for queued files: SYNC/replay (path per §9 probe) or live-capture → write bytes → ffprobe-verify (valid MP3, expected bytes) → state=LOCAL → enqueue TranscribeWorker.
- TranscribeWorker: pick provider (Settings) → segments[] → store + optional diarize → notify.
- SummarizeWorker: prompt = template + transcript (+title/tags context) → JSON sections → store; re-run on transcript edit (mark stale first).
- TodosWorker: second pass on summary+transcript → JSON `[{text,owner?,due?,context}]` → store.
- All workers: retry w/ backoff, cancel-on-device-disconnect mid-stream, error surfaced on Library row.

## 5. UI system
Material 3, dynamic color, dark-first. Single bottom nav: Home · Record · Library · Settings.
Detail = 4 tabs (Play/Transcript/Summary/To-dos). One accent + semantic green/red for the isolation
badge meaning (never reuse those colors decoratively). Monospace + collapsible raw sections everywhere
"Show wire values" — data honesty as visual identity. Type: Inter/Roboto, generous spacing,
28 dp min touch. Empty states teach the protocol ("No recordings on device — slide the slider to
start; I'll catch it"). Onboarding: 4 slides (connect → isolate → record → brains) + first-connect wizard.

## 6. Security/privacy posture
Keys: Keystore/EncryptedSharedPreferences, never in logs/wirelog/export. Cloud egress only
from explicit provider calls; vault folder untouched by network unless user installs their own sync.
No analytics, no crash-report server (local crash log + export). App perms: BLUETOOTH_CONNECT/SCAN
(location only if Android requires for scan, with explainer), INTERNET (only for cloud providers),
POST_NOTIFICATIONS, notifications never contain transcript text. Auto-lock app behind biometric
option (it holds sensitive meeting content). Tamper honesty: app cannot PREVENT a firmware change —
it DETECTS it (assertion badge).

## 7. Edge cases / error states
- MCU&OFF mid-record: save partial bytes, mark partial:true, UI asks re-record if critical.
- Connection drops mid-STA..STO: same partial handling, auto-reconnect, resume impossible (device segment lost — documented, not hidden).
- SPA& full → refuse record start with red toast (device can't).
- LIST mismatch (device has files, pull path missing): show "on device only" chip, offer cloud-free workaround: record-time capture reminder.
- Duplicate ts (device clock jump after T&): key files by first-seen connection, keep alias table.
- Battery <15%: warn before long records.
- Two phones: device accepts one central — single-device warning; no pairing lock to fight.

## 8. Non-goals (explicit)
Vendor cloud client, accounts, OTA push/accept, bonding, device provisioning of Wi-Fi, live in-app streaming STT (v3 maybe), encryption cmds (fw rejects), delete-on-device default, multi-user sync.

## 9. Open probes (before feature flags flip)
a. `APP&SYNC&` on unprovisioned device with stored file → a1 replay? (decides v1 scope: full-pull vs capture-time).
b. `APP&LNS&/LNP&/SCHED&LIST/WSCAN/WIFIP` read semantics (pagination + scan + wifi-listing UIs, v1.1).
c. `APP&D&…` delete semantics — lab-only on junk, owner present.
d. `APP&PAU/RESU` — segmented pause; if supported, PAU/RESU segments must map to sidecar offsets.

## 10. Build order (milestones)
M1 wire layer (Kotlin+Kable): connect/init/health/live-capture/vault write/verify/wire-log — testable on dev unit in a weekend.
M2 Library + passive capture + isolation badge + SAF vault.
M3 Provider layer + local whisper + Play/Transcript tabs.
M4 Summary/To-dos tabs + prompt templates + export.
M5 Workers/auto-sync + onboarding + polish + store-readiness (screenshots from here).
M6 probes from §9 land → sync-all + diarization + delete toggle.
Stack: Kotlin, Kable, Room, WorkManager, Media3, DataStore/EncryptedSharedPreferences,
whisper.cpp (JNI, small default), optional sherpa-onnx diarization. minSdk 30, target 35, no GMS.
