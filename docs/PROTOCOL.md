# Pocket (PKT01) BLE protocol — captured 2026-11-18-19, live official-app sessions + probes
# Source: Samsung HCI snoop br4.zip + br14.zip (see ADDENDUM end-of-file) + nRF Connect probes. All commands ASCII, no encryption, no pairing.

Transport: BLE, central writes commands to char 001120a2 (WRITE/WRITE_NO_RESPONSE, handle 0x2B);
device replies/events as notifications on 001120a3 (handle 0x30) and 001120a1 (handle 0x2D, CCC 0x2E).
Use acknowledged writes containing exactly the ASCII command bytes. In br14 an APP&BAT write has a seven-byte value and a ten-byte ATT PDU.
Commands are prefixed "APP&", responses "MCU&", tokens joined by '&'.

## Command/Response table (observed)
| # | APP -> (a2 write)            | MCU <- (a3 notify)                       | Meaning |
|---|------------------------------|------------------------------------------|---------|
| 1 | APP&SK&<9+char nonce>        | MCU&SK&OK                                | session handshake (nonce then OK) |
| 2 | APP&BAT                      | MCU&BAT&<pct>                            | battery percent |
| 3 | APP&SPACE                    | MCU&SPA&<int>&<int>                      | storage (free/total? 59813/59814) |
| 4 | APP&MAC                      | MCU&MAC&f44b26423747                     | mac as hex string |
| 5 | APP&FW                       | MCU&FW&1.8.9                             | firmware version (chunked at small MTU: 'MCU&FW&1.8' then full at MTU 23+) |
| 6 | APP&WF                       | MCU&WF&V9                                | wifi coprocessor fw |
| 6b| APP&BUILD                    | MCU&BUILD&1.8.9+97&V9+?                  | build id |
| 6c| APP&VER                      | MCU&VER&26.11.2+97&?                     | app-facing version; 26.11.2 = build date 2026-11-02 |
| 6d| APP&T&<YYYYMMDDhhmmss>       | MCU&T&OK                                 | time sync — run on every connect |
| 6e| APP&UPLE                     | MCU&UPLE&END&0&10350                     | upload state (END + counters) |
| 6f| APP&OT&OVER                  | MCU&OT&OVER                              | OTA session end/abort ack |
| 6g| APP&ENC / APP&LOG            | MCU&UNKNOWN                              | newer-firmware commands; 1.8.9 replies UNKNOWN |
| 7 | APP&GET&USB                  | MCU&USB&0                                | USB mode state (APP&USB&1/0 sets + acks) |
| 8 | APP&STE                      | MCU&STE&0                                | status/ste? |
| 9 | APP&REC&SECOND (or SECEN?)   | MCU&REC&CON / MCU&REC&CALL; MCU&STA&<ts> | start recording; STA& = start ts YYYYMMDDhhmmss. App also writes APP&REC&SECEN then sees REC&CON |
|10 | APP&STO                      | MCU&STO                                  | stop recording; audio streamed on a1 meanwhile |
|11 | APP&U&<YYYY-MM-DD>&<ts14>    | MCU&U&<bytes>                            | per-recording FILE SIZE in bytes (24808/31868/24956 observed) |
|11b| APP&LIST&<YYYY-MM-DD>        | MCU&F&<date>&<ts14>&<secs> x N, then MCU&LIST&<NNN> | file listing; F suffix = length-seconds; terminator = count ('0' when empty) |
|11c| APP&LIST_DIRS                | MCU&DIRS&<date> + MCU&DIRS_SUM&<NNN>     | date-folders on device |
|12 | APP&VER                      | (see 6c; early capture saw UNKNOWN once at small MTU) | |
|13 | APP&WIFIS                    | MCU&WIFIS&0 or MCU&WIFIS&0&0             | wifi sync state; 0 saved networks = isolated |
|14 | APP&WIFIC                    | MCU&WIFIC                                | wifi connect (device join attempt) |
|15 | APP&WIFIO                    | MCU&WIFIO                                | wifi on/off/open |
|16 | APP&WPING                    | MCU&WPING                                | keepalive ping |

## Device-initiated events (unsolicited on a3)
- MCU&REC&CALL / MCU&REC&CON — slider position (call mode / normal "con" mode). Button press = NO BLE traffic (local-only event).
- MCU&STA&<YYYYMMDDhhmmss> — recording started (with timestamp)
- MCU&STO — recording stopped
- MCU&OFF — device powering link off / sleep
- MCU&BAT&<n>, MCU&REC&CON…"..."… (recording-in-progress w/ extra fields)

## File transfer (Galaxy S20 and PKT01, firmware 1.8.9)

Subscribe to a3 for control messages and a1 for audio. Wait for each CCCD write callback. The app uses a stable install identifier in the session handshake and waits for the recorder to confirm it. On a previously paired unit, Android completed service discovery but the recorder disconnected during the handshake. After the owner reset the device, setup and subsequent reconnects succeeded with the same app identifier. A reset erases recordings.

List date directories, then list each date. Preserve the identifier in each file entry exactly, including the PH prefix on Call recordings. Request a stored file with the date and original identifier. The U reply gives its size and starts playback of the file bytes on a1. Append exactly that many bytes before publishing the MP3. A U request is therefore a transfer, including when a client only wants the size.

The S20 imported a 16,028-byte Conversation recording and a 40,074-byte Call recording. The Conversation import matched the SHA-256 of the live capture. Both file transfers preserved the source recording on the Pocket.

## Device observations
- Device is a listener; zero traffic until addressed. Re-estabs => 8 indication-acks (CCCD chatter) + device-info reads only.
- Firmware 1.8.9 (matches docs' Wi-Fi-upload requirement >=1.8.5).

## ADDENDUM <PII type="DATE" id="448"/> — br14 decode (sweep_raw.txt = ground truth)
- AUDIO ON THE WIRE (FINAL, kb 29655): between MCU&STA&<ts> and MCU&STO, the a1 (0x2d) notifications
  carry a PLAIN MP3 byte-stream — MPEG-1 Layer III CBR 32 kbps 16 kHz mono, no container/framing.
  Concatenate ATT values VERBATIM = byte-identical file (tone: 24,956 B == cloud copy == MCU&U& answer).
  The old "Wi-Fi offload / no bulk chunks on BLE" note was WRONG: sync streams over BLE fine.
- APP SESSION TEMPLATE (order the official app uses on connect — clone should mirror):
  SK&key → BAT×2, SPACE, MAC, FW, BUILD, WF, UPLE, GET&USB, STE, REC&SECEN, T&<ts>
  → VER, BUILD, ENC(→UNKNOWN), WF, VER, BUILD, WIFIS, WIFIO
  → LIST&<d> for last ~11 days (calendar refresh) → LOG(→UNKNOWN), VER/BUILD repeats
  → then APP&BAT poll every ~1 s while foreground; APP&WPING keepalives.
- Official app AUTO-RECONNECTS: force-stop is not enough — it grabbed the device ~10 s into our
  health macro mid-run. Kill/flight-mode it before ANY BLE session of our own.
- APP&SK&<16char> → MCU&SK&OK confirms the application session. Keep the identifier stable across reconnects.
- btsnoop method lesson: Samsung/BCM log (datalink 1002) has vendor ts prefix that breaks strict
  record-walkers. Use pattern+printable-run extraction: regex b'\x04\x00\x1b' (notify) /
  b'\x04\x00\x12' (write), value = printable ASCII from match+5. Handles re-enumerate per
  connection (responses seen on 0x2a as well as 0x2d/0x30) — filter by VALUE, not handle.
- UNANSWERED over BLE so far: LNS&, LNP&, SCHED&LIST, WSCAN, WIFIP(&CLR), D&<date>&<ts> (delete —
  do NOT issue without explicit owner OK; junk file only, and only after isolation confirmed).


## Single-file deletion

The archived official-app capture br14 records an acknowledged write on a2 containing `APP&D&2026-11-18&20261118083337` and a subsequent `MCU&D` notification on a3. The file identifier never appears again in the capture despite subsequent inventory requests. Firmware strings also define `MCU&D&ERR`. Secure Pocket requires a verified local copy with matching bytes from a fresh transfer and checks the resulting file list before reporting success.
