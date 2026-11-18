# Pocket BLE command dictionary (from Dart AOT strings, verbatim)

APP&AUTOUP&<0|1>	set device auto-update
APP&BAT	battery
APP&BLE&OFF | APP&BLE&RESET	BLE radio control
APP&BUILD	build info
APP&D&<date>	recordings list by date
APP&ENC&<v> | APP&ENCPK&<key>	encryption toggle | push encryption PUBLIC KEY
APP&FW	firmware version
APP&GET&USB	USB state
APP&LIST&[...] | APP&LNS& | APP&LNP&	full list + next-page pagination
APP&LOG&ON|OFF | APP&LOGF& | APP&LOGACK&	device log streaming over BLE (ack flow)
APP&MAC	device MAC
APP&OTA&<url>	OTA: device downloads URL (per Dart strings: at scheduled minute, >=50% battery, resumable, 'Download over the device AP' mode exists)
APP&OTA&WIFI&<...>	OTA via Wi-Fi
APP&PAU | APP&RESU	record pause/resume
APP&SCHED&LIST | APP&SCHED&OTA&<...> | APP&SCHED&SYNC&<...>|OFF	scheduled OTA/sync
APP&SHUT	shutdown device
APP&SK&<nonce>	handshake
APP&SPACE	storage free/total
APP&STA|APP&STO	record start/stop (devicePath ts)
APP&STE	state
APP&SYNC&[|LEGACY&0]	file sync (audio streams on a1)
APP&T&<...>	set time
APP&U&<date> | APP&U&WIFI	usage stats
APP&UPAUTH&[|CLR|E&..]	Wi-Fi upload authorization grant/revoke (stored ON device)
APP&UPKEY&<...>	upload key
APP&UPL&<..> | APP&UPL&ABRAND*|ABORT	upload URL push ('Enter the URL the device downloads the image from'-class debug)
APP&USB&<..>	USB mode
APP&VER	version
APP&OT&OVER	misc
(*ABRAND typo-check later; verbatim string was APP&UPL&ABORT)

# Observed responses (br4 + br14 live captures, <PII type="DATE" id="448"/>)
APP&SK&k        -> MCU&SK&OK (16-char key from app; NOT enforced — works without)
APP&BAT         -> MCU&BAT&<pct>            APP&SPACE -> MCU&SPA&<free>&<total>
APP&MAC         -> MCU&MAC&f44b26423747     APP&FW -> MCU&FW&1.8.9
APP&BUILD       -> MCU&BUILD&1.8.9+97&V9+?  APP&VER -> MCU&VER&26.11.2+97&? (build 2026-11-02)
APP&WF          -> MCU&WF&V9                APP&STE -> MCU&STE&0
APP&GET&USB     -> MCU&USB&<0|1>            APP&USB&<0|1> -> MCU&USB&<same> (ack)
APP&T&<ts14>    -> MCU&T&OK                 APP&UPLE -> MCU&UPLE&END&0&10350
APP&OT&OVER     -> MCU&OT&OVER              APP&WIFIS -> MCU&WIFIS&0[&0]
APP&WIFIO/WIFIC/WPING -> echo ack same token
APP&LIST&<date> -> MCU&F&<date>&<ts14>&<secs> xN; terminator MCU&LIST&<count> ('0' if empty)
APP&LIST_DIRS   -> MCU&DIRS&<date> + MCU&DIRS_SUM&<NNN>
APP&U&<date>&<ts14> -> MCU&U&<bytes>        (per-file SIZE, not time)
APP&REC&SECEN   -> (then event MCU&REC&CON) APP&STA -> event MCU&STA&<ts14>; APP&STO -> MCU&STO
APP&ENC / APP&LOG -> MCU&UNKNOWN            (fw 1.8.9 lacks them; app probes anyway)
NEVER ISSUED LIVE: LNS& LNP& SCHED&LIST WSCAN WIFIP(&CLR) D& — delete cmd STILL TBD (D&<date>&<ts>?
  do not fire without owner OK). DICT NOTE: APP&D&<date> above is from Dart strings; br14 shows
  APP&LIST&<date> fills the date-listing role — D& may be delete after all. Verify on junk.
