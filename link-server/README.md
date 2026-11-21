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

To run it in the background and open settings automatically, use `link-server/start-link.sh` on macOS/Linux or `link-server/start-link.ps1` on Windows. The browser can be closed and Link keeps running. Stop it with `link-server/stop-link.sh` or `link-server/stop-link.ps1`. These launchers use the packaged executable in `dist/` if present, otherwise Python. Logs and PID files are stored in the platform config directory.

The first run creates a token and settings file automatically. Defaults are:

| Platform | Settings file |
| --- | --- |
| Windows | `%APPDATA%\secure-pocket-link\config.json` |
| macOS | `~/Library/Application Support/secure-pocket-link/config.json` |
| Linux | `$XDG_CONFIG_HOME/secure-pocket-link/config.json`, or `~/.config/secure-pocket-link/config.json` |

Open `admin.token` in the same directory and paste its code into the settings page. The code changes when Link restarts. For Docker, the file is `/config/admin.token` in the Link container. Keep this code separate from the phone pairing token.

The encryption key is stored next to the settings file as `link.key` (or at the path passed with `--key-file`). Link encrypts new library backups and settings secrets with AES-GCM. At startup it automatically encrypts existing plaintext ZIP backups and loose MP3/JSON/Markdown/text files in the selected library while preserving their names and API access. The key is required to read the library; back it up securely with the settings file. If the key is lost, restore it before starting Link. Do not delete or replace it: Link will stop rather than create a new key when encrypted data is present. Keep the key out of the recording folder and do not share it with untrusted users. Filesystem encryption still depends on protecting the OS account and key file.

The initial recording destination is `~/Secure Pocket Library`. In Link settings, replace it with an absolute path to any writable folder. A mounted NAS share works too; mount it before starting Link so that the selected path points to the share. The config file is stored separately from the recording destination.

For regular MP3 and Markdown files, choose **Save as readable recording folders** or start Link with `--library-files`. This format relies on FileVault, BitLocker, LUKS, or equivalent encryption on the chosen volume. Link keeps each recording and its notes together under `library/`. Android can still download the recording with its notes. Existing archives stay readable through Link, and a new backup of the same recording replaces its archive with the folder after the files have been written.

The settings page shows Link uptime, whether the phone API listener is running, the LAN address when available, backup-folder readiness, and a quick recording count. Status refreshes while the page is visible without changing unsaved settings. The recent activity panel keeps up to 200 in-memory request events until Link exits; it omits tokens, request data, recording names, and file paths. Use **Check Docling** for an explicit health request with a 2.5-second timeout. A successful health response confirms the service answered, but does not verify model downloads or a full conversion.

## Pair the phone

Open Link settings and look for **Ready to receive Pocket recordings**. Configure direct API TLS or a trusted HTTPS proxy first so the page can show a safe pairing URL. On Android, open **Settings → Library Location** and select **Secure Pocket Link**. Copy the **Secure Pocket Link URL** and **Link token** from **Connect the Android app** into the matching fields. Tap **Test connection**, then **Save settings**. Both devices need access to the HTTPS endpoint. Connect your Pocket recorder to Android over Bluetooth to send its recordings.

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
