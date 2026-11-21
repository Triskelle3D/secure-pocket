# Secure Pocket security

## Data flow and trust boundaries

The Pocket recorder sends audio to the Android app over Bluetooth. The phone can play and process audio locally, or send it to a server the user selects. Secure Pocket Link receives backups and direct imports. Optional Docling and summary providers receive the audio or text needed for the selected processing stage.

| Component | What it protects |
| --- | --- |
| Android | The vault and settings use AES-256-GCM with separate Android Keystore keys. Protected data includes audio, transcripts, summaries, speakers, credentials, and saved last-seen location. |
| Secure Pocket Link | Link encrypts backup archives and stored tokens with AES-256-GCM. Readable recording folders use the chosen volume's encryption. A separate local admin code protects settings, diagnostics, and pairing tokens. |
| Network | The Android release requires HTTPS for Link, Docling, WebDAV, and configured network providers. It uses Android's certificate and hostname verification. Link can serve TLS directly or run behind a trusted HTTPS proxy. |
| Docker | Link and Docling use separate non-root containers on a dedicated network. The default host ports bind to loopback. Docling has no published port. An optional overlay enables certificate-verified TLS between Link and Docling. |

## Store and recover data

Keep the Link encryption key and data as separate protected backups. Recovery requires both. Move both when moving Link to another host. The key lives in the Link settings directory by default and in the persistent configuration volume for Docker. Use full-disk encryption on the Link computer to protect the local key and cached working data from offline disk access.

The Android key belongs to the app installation. Preserve the library with an encrypted Link backup before uninstalling or resetting the phone. A signed APK release uses a different signing key from earlier debug builds. Preserve the library before replacing a debug install.

For a Samba share, mount it on the Link host and let Link write encrypted artifacts there. Use SMB encryption when traffic between the Link host and NAS crosses a network you do not control. Direct WebDAV stores ZIP backups through HTTPS. Use encryption on that storage server for the stored ZIPs.

## Processing and hardware limits

The PKT01 firmware controls Bluetooth link security and storage on the recorder. App protections begin when Android receives the bytes. The BLE link and the recorder's internal copies are outside the app's encryption controls.

Processing uses decrypted audio or text in memory and may require short-lived app-private working files. File sizes and some archive identifiers can remain visible. A local administrator who can use a running Link process can also read the recordings that process can decrypt. For offline operation, choose on-device transcription and summarization and keep server processing off.

The Android 15 and later vault key requires the device to be unlocked. Older supported Android versions use Keystore without this setting because Android 12–14 can lose such keys during lock-screen changes. Release windows block screenshots and previews. Opening the last-seen point in a map gives the selected app those coordinates. [Android key restrictions](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setUnlockedDeviceRequired(boolean))

The Docling base stack keeps the internal processing connection on the dedicated bridge. Use the TLS overlay to encrypt that connection. Store TLS private keys outside shared folders and mount them only into the services that require them. Hosted AI providers receive the selected processing inputs after opt-in under their own data handling terms.

## Verification

This review used source and build inspection, dependency inventory and advisory checks, reachable Git history scanning, and tests with synthetic data in isolated instances. It reviewed Android permissions, encryption and key handling, the Link API and admin boundary, and container isolation and transport settings. [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [OSV](https://google.github.io/osv.dev/), [Gitleaks](https://github.com/gitleaks/gitleaks)

- **Android:** Keystore tests on the Galaxy S20 covered distinct encrypted artifacts, modified ciphertext rejection, and legacy preference migration. The resolved Maven dependency scan identified and guided updates to Guava and Protobuf. The build verifies the SHA-256 of the vendored binaries against the project's pinned copies.
- **Link:** Tests covered authenticated administration, encrypted configuration, backup round trips, and readable-folder storage. The phone token travels in an HTTP header over HTTPS. The settings code changes at each server start.
- **Containers:** The TLS overlays were exercised with a dedicated test CA. Real Docling Pre returned transcription and timed segments. Post formatted Markdown. Trivy found 50 high-severity records in the Debian Link base for which the scan listed no fixed version. The CentOS Stream Docling base lacks OS coverage in that scanner. OSV checked installed Python packages in both images after the dependency updates.
