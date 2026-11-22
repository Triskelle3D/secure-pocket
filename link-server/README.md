# Secure Pocket Link

Secure Pocket Link stores recordings in a folder on this computer and serves them to the Android app through direct TLS or a trusted HTTPS proxy. It has no cloud dependency. The settings page runs on this computer and controls the folder, phone token, and Docling connection.

## Run from source

Python 3.10 or newer is required. Create a virtual environment and install Link's pinned runtime dependency first:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r link-server/requirements-runtime.txt
.venv/bin/python link-server/secure_pocket_link.py
```

The runtime requirements pin cryptography 50.0.2 except on Intel macOS, where 48.0.1 provides the compatible universal2 wheel. Link only uses AES-GCM, so the Intel pin avoids unrelated PKCS#7 code paths. With a Rust toolchain installed, Intel macOS can instead build 50.0.2 from source.

On Windows, use `.venv\\Scripts\\python.exe` in place of `.venv/bin/python`. After setup, the launch scripts use `.venv` automatically when present. The phone API listens on `127.0.0.1:8732` by default. Settings listen on `127.0.0.1:8733`; open [http://127.0.0.1:8733/admin/](http://127.0.0.1:8733/admin/) on the computer to choose the backup destination and copy the token. The source launch commands run in the foreground; stop Link with Ctrl+C.

For phone access, configure direct TLS with a certificate trusted by Android, or put Link behind a trusted HTTPS proxy. Direct TLS example:

```sh
.venv/bin/python link-server/secure_pocket_link.py --api-host 0.0.0.0 --tls-cert /path/to/fullchain.pem --tls-key /path/to/private.key
```

For a local proxy, leave Link on its loopback default and configure the proxy to forward HTTPS traffic to `127.0.0.1:8732`. For a container or other trusted proxy backend that must bind the API on all internal interfaces, pass `--proxy-backend`, restrict network access to the proxy, and set `LINK_PUBLIC_URL` to the full external HTTPS origin (for example `https://link.example:8443`). Link does not trust forwarded host or scheme headers. Do not expose the plain HTTP backend directly to a LAN.

To run it in the background and open settings automatically, use `link-server/start-link.sh` on macOS/Linux or `link-server/start-link.ps1` on Windows. The browser can be closed and Link keeps running. Stop it with `link-server/stop-link.sh` or `link-server/stop-link.ps1`. On macOS and Linux, the launcher prefers the source checkout's `.venv` when available. On Windows, it checks for the packaged executable in `dist/` first. Logs and PID files are stored in the platform config directory.

The first run creates a token and settings file automatically. Defaults are:

| Platform | Settings file |
| --- | --- |
| Windows | `%APPDATA%\secure-pocket-link\config.json` |
| macOS | `~/Library/Application Support/secure-pocket-link/config.json` |
| Linux | `$XDG_CONFIG_HOME/secure-pocket-link/config.json`, or `~/.config/secure-pocket-link/config.json` |

Open `admin.token` in the same directory and paste its code into the settings page. The code changes when Link restarts. For Docker, the file is `/config/admin.token` in the Link container. Keep this code separate from the phone pairing token.

The encryption key is stored next to the settings file as `link.key` (or at the path passed with `--key-file`). Link encrypts new library backups and settings secrets with AES-GCM. At startup it automatically encrypts existing plaintext ZIP backups and loose MP3/JSON/Markdown/text files in the selected library while preserving their names and API access. The Link settings page has an **Encryption key backup** section with a **Download recovery key** action. Back up this key and `config.json` securely. If the key is lost, encrypted data cannot be recovered. Do not delete or replace the active key. Keep recovery copies separate from the library and protect them with encrypted storage.

The initial recording destination is `~/Secure Pocket Library`. In Link settings, replace it with an absolute path to any writable folder. A mounted NAS share works too; mount it before starting Link so that the selected path points to the share. The config file is stored separately from the recording destination.

For regular MP3 and Markdown files, choose **Save as readable recording folders** or start Link with `--library-files`. This format relies on FileVault, BitLocker, LUKS, or equivalent encryption on the chosen volume. Link keeps each recording and its notes together under `library/`. Android can still download the recording with its notes. Existing archives stay readable through Link, and a new backup of the same recording replaces its archive with the folder after the files have been written.

The settings page shows Link uptime, whether the phone API listener is running, the LAN address when available, backup-folder readiness, and a quick recording count. Status refreshes while the page is visible without changing unsaved settings. The recent activity panel keeps up to 200 in-memory request events until Link exits; it omits tokens, request data, recording names, and file paths. Use **Check Docling** for an explicit health request with a 2.5-second timeout. A successful health response confirms the service answered, but does not verify model downloads or a full conversion.

## Pair the phone

1. Open Link settings at `http://127.0.0.1:8733/admin/`. Unlock with the code in `admin.token`. Wait for **Ready to receive Pocket recordings** and confirm the backup folder is writable.
2. Install Tailscale on the Link computer and Android phone. Sign both into the same tailnet and connect Tailscale on the phone.
3. On the Link computer, run `tailscale serve --bg 8732` once. This provides a private HTTPS address and keeps Serve running after you close the terminal. It resumes when Tailscale restarts or the computer reboots. Link settings detects the address when the Tailscale CLI is available. If Link runs in Docker, copy the HTTPS address printed by Tailscale into the editable **Tailscale URL** field in Link settings. Use `tailscale serve status` to see it later. Disable this route with `tailscale serve --bg 8732 off`. [Tailscale Serve guide](https://tailscale.com/docs/reference/tailscale-cli/serve).
4. In Secure Pocket on Android, open **Settings → Library Location → Secure Pocket Link**. Paste the Link token. For Tailscale only, leave **Local URL (preferred)** blank and paste the HTTPS address into **Tailscale URL (fallback)**. For local-first access, also enter your LAN HTTPS address under **Local URL (preferred)**. Tap **Test connection**, then **Save settings**.

Connect the Pocket recorder to Android over Bluetooth to import recordings. Secure Pocket then sends them to Link. The phone and Link computer must be online together for server-only imports.

For LAN access without Tailscale, configure direct TLS or a trusted HTTPS proxy. Link’s API and settings listeners bind to loopback by default. Do not expose its plain HTTP API port to a LAN or the internet. If both Android URLs are set, the local route’s certificate must match the Tailscale URL hostname.

## Encryption key backup and recovery

Link creates a 32-byte AES-GCM key on first start. In the local Link settings page, use **Encryption key backup → Download recovery key**. The download contains the raw key. Anyone who obtains both this file and the encrypted library can decrypt recordings and saved Link secrets. Store the recovery file separately on encrypted storage. Do not email it, put it in the library folder, or share it.

To move or recover a Link installation:

1. Stop Link on the destination computer.
2. Restore the recovery key as `link.key` beside the matching `config.json` in Link’s configuration folder. The platform paths are listed above. For Docker, restore it to `/config/link.key` in the persistent `link-config` volume.
3. Restore the matching library folder or Docker `link-library` volume.
4. Start Link and confirm the library opens.

Do not start Link against an encrypted library with a new key. Link fails closed if the key is missing and encrypted data exists. Losing the key makes that data unrecoverable. Link does not currently rotate encryption keys; replace neither the key nor its matching configuration for an existing library.

## Use a Samba/SMB share

Mount the NAS share on the computer running Link and sign in with an account that can write to it. Create a folder for the library, then enter its full path in **Backup destination**.

| Computer | Connect to the share | Example library path |
| --- | --- | --- |
| Windows | Map a network drive in File Explorer. [Microsoft instructions](https://support.microsoft.com/en-us/windows/experience/connectivity-networking/file-sharing-over-a-network-in-windows) | `Z:\Secure Pocket Library` |
| macOS | In Finder, choose **Go → Connect to Server** and enter an address such as `smb://nas/Recordings`. [Apple instructions](https://support.apple.com/guide/mac-help/connect-mac-shared-computers-servers-mchlp1140/mac) | `/Volumes/Recordings/Secure Pocket Library` |
| Linux | Mount the share with the system's SMB/CIFS client. | `/mnt/recordings/secure-pocket-library` |

Keep the share mounted while Link runs. In Android, open **Settings → Library Location → NAS → Connect through Secure Pocket Link**. Enter Link's phone URL and token and your home Wi-Fi name. Tap **Test connection**, then **Save settings**.

For Docker, mount the SMB share on the Docker host and use its path with the [host folder overlay](DOCLING.md). The Link container needs write access to the mounted folder.

## Portable executable builds

Install the build dependency and run `python link-server/package.py` on the target operating system and CPU architecture:

```sh
python -m pip install -r link-server/requirements-build.txt
python link-server/package.py
```

The executable is written to `dist/SecurePocketLink` (or `SecurePocketLink.exe` on Windows). The executable is self-contained and the settings page is included. Start it directly in a terminal, or use the platform launcher to run it in the background. The optional GitHub Actions workflow creates per-platform ZIP downloads with the executable, start/stop scripts, and this README for Windows x64, Linux x64, macOS Intel, and macOS Apple Silicon. Native builds on the target OS and architecture are the primary release path.

## Docker

Compose publishes the phone API and settings page on host loopback by default. The API uses proxy-backend mode so a trusted TLS reverse proxy or tailnet forwarder on the Docker host can reach it at `127.0.0.1:8732`; settings are at `http://127.0.0.1:8733/admin/`. Set `LINK_PUBLIC_URL` to the public HTTPS origin so Link displays the encrypted pairing URL. Do not publish the plain HTTP backend directly to the LAN.

For direct API TLS with a certificate trusted by Android, use the [`compose.link-tls.yaml` overlay](DOCLING.md#host-folders-and-api-access). Set `LINK_BIND_ADDRESS=0.0.0.0` only with this TLS overlay. The settings port remains loopback-only.

Compose publishes the API on `LINK_PUBLIC_API_PORT` (default `8732`) and settings on `LINK_PUBLIC_ADMIN_PORT` (default `8733`), both at host loopback. `LINK_PUBLIC_URL` supplies the externally reachable HTTPS origin. Keep the library, settings, and `/config/link.key` encryption key in persistent volumes; do not mount the Docker socket into Link.

Docker defaults use `/data` for recordings and `http://docling:5001` for processing on first launch. Recordings and config secrets are encrypted at rest with the key in `/config/link.key`; keep the `link-config` volume persistent and backed up together with the library. The settings UI can change either value; saved choices take precedence after restart. Compose uses a persistent named volume for `/data` by default. To store the library in a host folder instead, set `LINK_LIBRARY_HOST_PATH` to an absolute, writable host path and start Compose with the optional overlay:

```sh
LINK_LIBRARY_HOST_PATH=/path/to/secure-pocket-library docker compose -f compose.yaml -f compose.host-library.yaml up -d --build
```

The host folder must be writable by the Link container's `pocket` user. The overlay only replaces the library mount; config and Docling models remain in their own persistent volumes.

## Command-line options

`--root PATH` selects the library folder, `--token VALUE` or `--token-file PATH` overrides the saved token, `--port PORT` changes the phone API port, `--admin-port PORT` changes the settings port, `--admin-host ADDRESS` changes the settings bind address, and `--config PATH` selects a config file.
