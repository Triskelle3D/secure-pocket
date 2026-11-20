# Secure Pocket Link

Secure Pocket Link stores recordings in a folder on this computer and serves them to the Android app over your LAN or tailnet. It has no cloud dependency. The settings page runs on this computer and controls the folder, phone token, and Docling connection.

## Run from source

Python 3.10 or newer is required. On macOS/Linux, run `link-server/run-link.sh`. On Windows, run `link-server/run-link.ps1`. Or start it directly:

```sh
python link-server/secure_pocket_link.py
```

The phone API listens on port `8732` at the computer's network interfaces. Settings listen on `127.0.0.1:8733`. Open [http://127.0.0.1:8733/admin/](http://127.0.0.1:8733/admin/) on the computer to choose the backup destination and copy the token. The source launch commands run in the foreground; stop Link with Ctrl+C.

To run it in the background and open settings automatically, use `link-server/start-link.sh` on macOS/Linux or `link-server/start-link.ps1` on Windows. The browser can be closed and Link keeps running. Stop it with `link-server/stop-link.sh` or `link-server/stop-link.ps1`. These launchers use the packaged executable in `dist/` if present, otherwise Python. Logs and PID files are stored in the platform config directory.

The first run creates a token and settings file automatically. Defaults are:

| Platform | Settings file |
| --- | --- |
| Windows | `%APPDATA%\secure-pocket-link\config.json` |
| macOS | `~/Library/Application Support/secure-pocket-link/config.json` |
| Linux | `$XDG_CONFIG_HOME/secure-pocket-link/config.json`, or `~/.config/secure-pocket-link/config.json` |

The initial recording destination is `~/Secure Pocket Library`. In Link settings, replace it with an absolute path to any writable folder. A mounted NAS share works too; mount it before starting Link so that the selected path points to the share. The config file is stored separately from the recording destination.

The settings page shows Link uptime, whether the phone API listener is running, the LAN address when available, backup-folder readiness, and a quick recording count. Status refreshes while the page is visible without changing unsaved settings. The recent activity panel keeps up to 200 in-memory request events until Link exits; it omits tokens, request data, recording names, and file paths. Use **Check Docling** for an explicit health request with a 2.5-second timeout. A successful health response confirms the service answered, but does not verify model downloads or a full conversion.

## Pair the phone

Open Link settings and look for **Ready to receive Pocket recordings**. On Android, open **Settings → Library Location** and select **Secure Pocket Link**. Copy the **Secure Pocket Link URL** and **Link token** from **Connect the Android app** into the matching fields. Tap **Test connection**, then **Save settings**. Both devices need access to the same Wi-Fi or tailnet. Connect your Pocket recorder to Android over Bluetooth to send its recordings.

## Portable executable builds

Install the build dependency and run `python link-server/package.py` on the target operating system and CPU architecture:

```sh
python -m pip install -r link-server/requirements-build.txt
python link-server/package.py
```

The executable is written to `dist/SecurePocketLink` (or `SecurePocketLink.exe` on Windows). The executable is self-contained and the settings page is included. Start it directly in a terminal, or use the platform launcher to run it in the background. The optional GitHub Actions workflow creates per-platform ZIP downloads with the executable, start/stop scripts, and this README for Windows x64, Linux x64, macOS Intel, and macOS Apple Silicon. Native builds on the target OS and architecture are the primary release path.

## Docker

For container deployments, publish the phone API to the LAN and publish the settings port only on host loopback. The container must bind its admin listener to all container interfaces for Docker port forwarding:

```sh
python link-server/secure_pocket_link.py --admin-host 0.0.0.0
```

Map ports as `8732:8732` and `127.0.0.1:8733:8733`. Keep the selected library directory on a dedicated data volume; the settings/config file belongs in the user's config directory, not inside that library volume. Do not mount the Docker socket into Link.

Compose publishes the phone API on `LINK_PUBLIC_API_PORT` (default `8732`) and settings on `LINK_PUBLIC_ADMIN_PORT` (default `8733`). Those same values are passed into Link so the settings page shows the correct host ports and accepts same-origin browser requests when either port is overridden. Set `LINK_PUBLIC_HOST` to the host computer's LAN name or IP to show a copyable pairing URL in Docker; if unset, the page asks you to use that host's LAN IP.

Docker defaults use `/data` for recordings and `http://docling:5001` for processing on first launch. The settings UI can change either value; saved choices take precedence after restart. Compose uses a persistent named volume for `/data` by default. To store the library in a host folder instead, set `LINK_LIBRARY_HOST_PATH` to an absolute, writable host path and start Compose with the optional overlay:

```sh
LINK_LIBRARY_HOST_PATH=/path/to/secure-pocket-library docker compose -f compose.yaml -f compose.host-library.yaml up -d --build
```

The host folder must be writable by the Link container's `pocket` user. The overlay only replaces the library mount; config and Docling models remain in their own persistent volumes.

## Command-line options

`--root PATH` selects the library folder, `--token VALUE` or `--token-file PATH` overrides the saved token, `--port PORT` changes the phone API port, `--admin-port PORT` changes the settings port, `--admin-host ADDRESS` changes the settings bind address, and `--config PATH` selects a config file.
