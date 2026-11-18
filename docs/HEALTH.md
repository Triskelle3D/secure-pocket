# PKT01 health report — device PKT01_BLUE_26423747, captured <PII type="DATE" id="448"/> 00:19 (br14.zip)

All values read verbatim from BLE responses in artifacts/nrf/sweep_raw.txt (btsnoop14.log regions
440K-610K = official-app session Nov-18 + 1780K-1815K = our macro start + app auto-reconnect).
15 of 19 health-macro commands never answered: the official app auto-reconnected mid-sweep
(APP&SK&xGLDAcz6ZjeLfhSR @1793992) and stole the connection. The app session answers everything anyway.

| Item | Command | Response | Notes |
|---|---|---|---|
| MCU firmware | APP&FW | MCU&FW&1.8.9 | 1.8.9 current; docs want >=1.8.5 for Wi-Fi upload — satisfied |
| Build | APP&BUILD | MCU&BUILD&1.8.9+97&V9+? | build 97; last token unresolved |
| App-facing ver | APP&VER | MCU&VER&26.11.2+97&? | 26.11.2 = build date 2026-11-02; one response carried &V9+? variant |
| Wi-Fi coprocessor | APP&WF | MCU&WF&V9 | Actions SoC + W91-class coprocessor |
| Battery | APP&BAT | MCU&BAT&96..100 | charging ramp observed; app polls every ~1s foreground |
| Storage | APP&SPACE | MCU&SPA&059802&059814 | free&total; near-full-looking pair, unit (KB blocks?) unresolved |
| MAC | APP&MAC | MCU&MAC&f44b26423747 | matches advertisement |
| USB mode | APP&GET&USB / APP&USB&<0\|1> | MCU&USB&<0\|1> | get + set both ack; observed toggle 0→1→0→1 |
| State | APP&STE | MCU&STE&0 | |
| Wi-Fi saved nets | APP&WIFIS | MCU&WIFIS&0 / &0&0 | ZERO saved networks = never provisioned = ISOLATED. New fw sends two args |
| Upload state | APP&UPLE | MCU&UPLE&END&0&10350 | END + counters; no pending upload |
| Date dirs | APP&LIST_DIRS | MCU&DIRS&<date>, MCU&DIRS_SUM&001 | one date-folder on device |
| Files Nov-18 | APP&LIST&<date> | MCU&F&<date>&20261118082946&6; …083652&7; …085640&6; MCU&LIST&003 | F = date&start-ts&LENGTH-SECONDS; terminator = file count |
| Empty days | APP&LIST&<date> | MCU&LIST&0 | '0' not '000' when empty |
| Per-file size | APP&U&<date>&<ts14> | MCU&U&24808 / 31868 / 24956 | BYTES — 24956 == wire_tone.bin byte size == tone file |
| OTA state | APP&OT&OVER | MCU&OT&OVER | ack |
| Time sync | APP&T&<ts14> | MCU&T&OK | CONFIRMED — clone must sync clock on connect |
| Handshake | APP&SK&<16char> | MCU&SK&OK | app sends a 16-char key; device acks ANY key? nRF got full responses WITHOUT SK → not enforced |
| Unsupported | APP&ENC / APP&LOG | MCU&UNKNOWN | app probes commands newer fw has; fw 1.8.9 rejects politely |
| Events | (unsolicited a1/a3) | MCU&REC&CON → MCU&STA&<ts14> … MCU&STO; MCU&OFF | CON=slider-open precedes STA; OFF precedes every disconnect |

## Device posture verdict
- Isolated: no Wi-Fi creds on device (WIFIS&0, UPAUTH never pushed by us). Device cannot upload on its own.
- Official app = the only component that ever talks to the cloud; it is force-stoppable BUT auto-reconnects to BLE — kill it (or flight-mode it) before any BLE session.
- Tone recording '12:22' = device 20261118085640, 32s on-device, 24,956 B = proves wire format (kb 29655: plain MPEG-1 Layer III CBR 32kbps 16 kHz mono, verbatim a1 concat).

## Still unanswered (never successfully issued over BLE)
APP&LNS& / APP&LNP& (pagination), APP&SCHED&LIST, APP&WSCAN, APP&WIFIP (±&CLR wipe), APP&D&… (delete — NEVER issue without Matt's OK on a junk file).
