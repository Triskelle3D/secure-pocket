# Secure Pocket security

## Data flow and trust boundaries

The Pocket recorder sends audio to the Android app over Bluetooth. The phone can play and process audio locally, or send it to a server the user selects. Secure Pocket Link receives backups and direct imports. Optional Docling and summary providers receive the audio or text needed for the selected processing stage.

| Component | What it protects |
| --- | --- |
| Android | The vault and settings use AES-256-GCM with separate Android Keystore keys. Protected data includes audio, transcripts, summaries, speakers, credentials, and saved last-seen location. |
| Secure Pocket Link | Link encrypts backup archives and stored tokens with AES-256-GCM. The encryption key is stored separately from settings and library data. In readable-folder mode, recording files are plaintext and rely on the chosen volume's encryption. A separate local admin code protects settings, diagnostics, and pairing tokens. |
| Network | The Android release requires HTTPS for Link, WebDAV, and configured network providers, using Android's certificate and hostname verification. Docling is reached by Link over the Docker connection described below. Link can serve TLS directly or run behind a trusted HTTPS proxy. |
| Docker | Link and Docling use separate non-root containers on a dedicated network. The default host ports bind to loopback. Docling has no published port. Link-to-Docling uses certificate-verified TLS and Docling API-key authentication by default, provisioned by a persistent network-isolated helper. |

## Store and recover data

Keep the Link encryption key, configuration, and data as separate protected backups. Recovery requires the key and matching config/library. Desktop installs store the key in a separate platform data directory; Docker stores it in the persistent `link-key` volume, apart from `link-config` and `link-library`. This separation helps if only config and library copies are exposed. A host or Docker administrator can still access all of these locations. Use full-disk encryption on the Link computer to protect them from offline disk access.

The Android key belongs to the app installation. Preserve the library with an encrypted Link backup before uninstalling or resetting the phone. A signed APK release uses a different signing key from earlier debug builds. Preserve the library before replacing a debug install.

For a Samba share, mount it on the Link host and let Link write encrypted artifacts there. Use SMB encryption when traffic between the Link host and NAS crosses a network you do not control. Direct WebDAV stores ZIP backups through HTTPS. Use encryption on that storage server for the stored ZIPs.

## Processing and hardware limits

The PKT01 firmware controls Bluetooth link security and storage on the recorder. App protections begin when Android receives the bytes. The BLE link and the recorder's internal copies are outside the app's encryption controls. The current firmware does not authenticate or encrypt BLE, so nearby attackers may intercept or impersonate the Pocket. Factory reset does not change this.

Processing uses decrypted audio or text in memory and may require short-lived app-private working files. File sizes and some archive identifiers can remain visible. A local administrator who can use a running Link process can also read the recordings that process can decrypt. For offline operation, choose on-device transcription and summarization and keep server processing off.

The Android 15 and later vault key requires the device to be unlocked. Older supported Android versions use Keystore without this setting because Android 12–14 can lose such keys during lock-screen changes. Release windows block screenshots and previews. Opening the last-seen point in a map gives the selected app those coordinates. [Android key restrictions](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setUnlockedDeviceRequired(boolean))

Compose puts Link and Docling on a dedicated Docker bridge, publishes no Docling host port, and uses certificate-verified TLS between the services by default. A persistent, network-isolated helper generates a private CA and a Docling server certificate; Link trusts the CA, and the CA private key is mounted only into the helper. Users may replace the generated leaf certificate with one from their own CA. This authenticates Docling to Link and encrypts the link; Docling does not require a client certificate from Link, so this is not mutual TLS. A compromised Docling container can still read and alter audio and text sent to it because it is the intended processor.

Link encrypts its recording bundles and saved secrets. The key is now stored outside the Link config directory; Docker uses a dedicated `link-key` volume. An attacker who copies only config and library data cannot decrypt them without the key. This is not a defense against a compromised host, Docker administrator, or running Link process, all of which can access the key. Protect the host with full-disk encryption and store a recovery copy of the key separately.

Processing decrypts audio and text in memory and may create short-lived plaintext files in app-private cache or Link's private temporary storage. The app removes its Link-import temporary audio on success and failure; cache cleanup is best-effort and flash storage does not guarantee secure erasure. Do not treat deletion as forensic wiping. Use device encryption and host full-disk encryption.

The Android app accepts a user-selected MediaPipe `.task` model (up to 2 GiB). It checks the file format, but cannot establish that a model is trustworthy or defend against every bug in the native inference parser. Install model files only from a publisher you trust. Hosted STT/LLM providers receive the selected audio or text after server processing is enabled; their retention and access rules are outside this project.

The Android manifest requests foreground location only in app features, but older builds declared background location and boot permissions unnecessarily. Current source removes those unused declarations. Location is sampled only while the app has a connected Pocket and permission is granted; the last-seen coordinate is stored in encrypted app preferences.

## Current source audit (2026-11-22)

This audit reviewed current Android, Link, and Compose source after comparing the repository's previous security notes. Static findings addressed in this revision: Link-import plaintext audio is now deleted in a `finally` path; Android bounds responses from Link and processing providers; unused background-location and boot permissions were removed; Compose now uses certificate-verified TLS for Link-to-Docling by default; and the Link key is separated from config/library storage. The reported Link admin token leak and unbounded Docling response/redirect issues are already fixed in the current source. The known BLE limitation remains.

The initial source review did not rebuild the APK, launch containers, probe a live server, or rerun dependency vulnerability scanners. Image and dependency digests are pinned in the deployment files, but Python installs are not hash-locked; refresh and scan dependencies before production deployment. The pinned Docling base is upstream Docling Serve with upstream Whisper runtime additions, not the Teczone image.

Link limits concurrent request workers and applies an idle socket timeout, but it does not enforce a strict total deadline for slowly delivered request headers or TLS handshakes. A client that can reach the Link API may occupy worker slots by sending data very slowly. Keep the API behind trusted HTTPS and restrict who can reach its host or tailnet listener.

## Findings from the supplied full audit

The worktree was checked against `docs/security/SECURITY-AUDIT-2026-11-FULL.md` and its skeptical review. Status below reflects current source, not the older HEAD named in those reports.

| Finding | Status and remaining limit |
| --- | --- |
| A1/B1: Docker admin listener bind-address assumptions and `admin.token` exposure | Admin API data routes require a 256-bit rotating unlock code, even when Docker binds the listener on its container interface. Docker stores that code only in Link's temporary `/run/link-admin` filesystem; it is no longer in the persistent config volume. The host-published admin port stays loopback-only. Non-loopback API binds now print a warning. A Docker administrator or local account able to read the process/container can still obtain the code. Desktop installs keep `admin.token` beside config with private file permissions. |
| A2: unauthenticated Pocket BLE | Accepted hardware limitation; no firmware change is possible from the app. The documented nearby interception and device-impersonation risks remain. |
| B2: cleartext unauthenticated Link-to-Docling | Compose now uses certificate-verified TLS plus Docling Serve's API-key check by default. A network-isolated helper provisions the private CA, leaf certificate, and API key. The API key authenticates Link to Docling; TLS authenticates Docling to Link. This is not mutual TLS. A compromised Docling service can read or alter the content it processes. |
| B3: Android Keystore keys not always user-authentication-bound | Documented trade-off. Android 15+ requires the phone to be unlocked for the library key; older supported Android versions do not because of Keystore invalidation behavior. The app does not require a biometric or PIN for each decrypt operation. An unlocked or compromised phone can use the app's key. |
| B4: non-loopback API bind footgun | Link refuses non-loopback plain binds without TLS or proxy mode and now emits a warning whenever the API binds beyond loopback. Operators must still restrict access to that listener. |
| B5: validation build variant | Internal-only test APK with debug inheritance and test hooks. Do not distribute it; distribute the minified release APK. |
| B6/N2: excess location/boot permissions | The unused background-location and boot permissions have been removed. Foreground location is requested only for Find Pocket. |
| C1: repeated full-library hashing on `/index.json` | Still present: authenticated index requests hash library audio to verify entries. This can cost CPU as a library grows, but requires a valid Link token and does not expose data to an unauthenticated caller. |
| C2: slow TLS handshake/header clients | Worker count and idle timeout bound concurrent resource use, but no strict total request deadline exists. Restrict network reachability as above. |
| C3: inline admin script and CSP `unsafe-inline` | Retained for the single-file settings UI. The page uses `textContent` for dynamic data, blocks framing and external connections, and does not render remote HTML. A strict script nonce/hash would be stronger. |
| C4: transcript prompt injection | Prompts treat transcript content as data and validate summary structure/names, but generated summaries remain untrusted text. Do not treat them as commands or verified facts. |
| C5: no certificate pinning in Android | The app uses Android's normal certificate-chain and hostname validation, not pins. A malicious or compromised trusted CA remains in the trust model. |
| N1/N3: undeclared providers and `share()` | Rejected by the skeptical review as findings from code that does not exist in this repository. No action is needed for those claims. |

Earlier Link findings marked fixed in the supplied audit remain fixed: index scan exception, token minimum length, thread cap/idle timeout, Docling response-size cap and redirect refusal, release minification, and health response path disclosure.

## Verification

The supplied audit report records earlier Android, Link, container, dependency, and Docling pipeline checks. The current change pass adds these results:

- The minified Android release build compiled successfully with Java 17. Gradle produced an unsigned APK because release signing values and the original keystore were not available in this checkout. The build accepts values from the environment or ignored `local.properties`; no key or password is stored in the repository. The S20 still has signed version 1.8.0 installed, so this build cannot replace it without the original private signing key. The validation package was used for device tests. On 2026-11-22 the original keystore was searched for and not found: never in this repository's Git history or dangling objects, not on this machine outside debug keystores, no Time Machine destination or user snapshots, no stashes, no copy in the backup working copy, CI does not sign, and the hub host was not reachable for a key-auth search. Unless a copy surfaces in a password manager or on another machine, future releases must use a new signing key, which cannot update the installed 1.8.0 app in place; that phone would need uninstall and a restore from an encrypted Link backup.
- A new go-forward signing identity was generated on 2026-11-22 to make releases possible again. Keystore: `secure-pocket-release.jks` at the repo root, alias `secure-pocket`, RSA 3072, ignored by `.gitignore` (never commit it). Certificate SHA-256: `15:31:10:BB:BC:D3:23:B9:62:85:3F:FA:59:C3:97:DF:AA:2B:73:8B:49:D9:A6:F1:C7:6E:46:AC:83:89:6F:E7`. Store and key passwords live only in the ignored `local.properties` (`securePocketRelease*` properties). Back up both the keystore and those passwords offline; losing this key repeats the 2026-11 loss. Every install signed with the previous key (including the 1.8.0 on the test phone) must be uninstalled and reinstalled from a new-key APK, then re-synced from Link.
- `connectedValidationAndroidTest` ran on the Galaxy S20 (Android 13). The first full pass reported failures that proved to be environment and test-code races, not app defects: some UI tests ran while the phone was dozing at its lock screen, and `speechPlaybackMarkdownRoundTrip` clicked "Edit summary" before the reopened detail finished enabling. The test now waits for that button to be enabled before clicking, and `LibraryScreen` keys the detail view by recording and tab so state cannot be reused across switches. A focused rerun of `RecordingFlowTest` on the unlocked phone then passed both cases (2 of 2, verified in the connected test-results XML). The full suite has not been rerun since this fix; the skipped cases still require a temporary Gemma model, a WebDAV fixture URL, or a live Docling URL.
- Compose rebuilt and started the official Docling Serve stack. Link-to-Docling TLS certificate verification passed. A conversion request without the API key received HTTP 401; a request with the key reached Docling validation. Docling Pre and Post requests through Link both returned HTTP 200 using the test audio fixture and test Markdown.
- The live Docker Link API and settings ports bind to host loopback. Its encryption key is in a separate persistent volume and its admin unlock code is in temporary container storage. The desktop Link key was moved to its separate mode-0600 location; all 63 existing library files remained present. Authenticated health requests passed locally and through the existing Tailscale HTTPS route.

Compose configuration parsing, Python syntax parsing, and `git diff --check` also passed. The release signing key is still needed to install the current release build over the existing app without erasing app data. The dependency scanners were not rerun in this pass. Re-run those checks before treating this revision as release-verified.

The earlier report notes a dependency scanning limitation: the scanner did not provide fix availability for the listed Debian Link-base findings and lacked OS coverage for the CentOS Stream Docling base. Review upstream image and package updates and scan the resulting images as part of release preparation. [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [OSV](https://google.github.io/osv.dev/), [Gitleaks](https://github.com/gitleaks/gitleaks)
