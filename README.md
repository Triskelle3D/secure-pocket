<h1 align="center">Secure Pocket</h1>

<p align="center">
  <img src="docs/assets/secure-pocket-icon.png" width="180" height="180" alt="Secure Pocket green padlock icon">
</p>

Secure Pocket is an Android companion for the **Pocket voice recorder (PKT01)**. It imports recordings over Bluetooth, plays audio, and creates editable transcripts and summaries. Keep recordings on your phone or back them up to a computer or NAS you control.

## Start here

1. Download the latest APK from [Secure Pocket Android releases](releases) to an Android 11+ phone. Open the APK, allow your browser or file manager to install apps if Android asks, then tap **Install** and **Open**.
2. Prepare the Pocket recorder as described below, then connect it in **Device → Connect Pocket**.
3. Import a recording. Find its audio, transcript, and summary in **Library**.
4. Add Secure Pocket Link only if you want computer or NAS backups. Add Docling only if you want server-side transcription or formatting.

### Prepare or reset the Pocket

Before first pairing, factory-reset the Pocket:

1. **Back up anything you want to keep.** Factory reset permanently deletes every recording stored on the Pocket itself.
2. Tap the Pocket’s button three times.
3. Press and hold the button until the LED turns blue.
4. Keep the Pocket near the phone. In Secure Pocket, open **Device → Connect Pocket** and follow the Bluetooth permission prompts.

Resetting the Pocket does not delete the phone’s Secure Pocket library or Link backup.

## Add Secure Pocket Link

Secure Pocket Link (Link) is an optional background app. It stores encrypted backups in a folder on a Windows, macOS, or Linux computer. You can choose a local disk or a mounted NAS folder, including Samba/SMB. Link has no cloud service. The Android phone and computer must be able to reach one another.

### Install Link on Windows

1. Install [Python 3.10 or newer](https://www.python.org/downloads/windows/). During setup, enable **Add Python to PATH**.
2. Download and extract the [Secure Pocket source archive](archive/master.zip). Open PowerShell in the extracted project folder (the folder containing `link-server`).
3. Create Link’s Python environment and install its dependencies:

   ```powershell
   py -3 -m venv .venv
   .\.venv\Scripts\python.exe -m pip install -r link-server\requirements-runtime.txt
   ```

4. Start Link in the background and open its settings page:

   ```powershell
   .\link-server\start-link.ps1
   ```

5. In the browser, open **Backup destination** and choose a writable folder. For an SMB share, map it in File Explorer first, then select its folder. Save settings.

Stop Link with `.\link-server\stop-link.ps1` in PowerShell. Link logs are in `%APPDATA%\secure-pocket-link\link.log` and `link-error.log`.

### Install Link on macOS

1. Install [Python 3.10 or newer](https://www.python.org/downloads/macos/).
2. Download and extract the [Secure Pocket source archive](archive/master.zip). Open Terminal in the extracted project folder (the folder containing `link-server`).
3. Create Link’s Python environment and install its dependencies:

   ```sh
   python3 -m venv .venv
   .venv/bin/python -m pip install -r link-server/requirements-runtime.txt
   ```

4. Start Link in the background and open its settings page:

   ```sh
   sh ./link-server/start-link.sh
   ```

5. In the browser, open **Backup destination** and choose a writable folder. For an SMB share, connect in Finder with **Go → Connect to Server**, mount the share, then choose its folder. Save settings.

Stop Link with `sh ./link-server/stop-link.sh`. Logs are in `~/Library/Application Support/secure-pocket-link/`.

### Install Link on Linux

1. Install Python 3.10 or newer and its virtual-environment package. On Debian or Ubuntu, run `sudo apt install python3 python3-venv`. On Fedora, run `sudo dnf install python3`. On Arch Linux, run `sudo pacman -S python`.
2. Download and extract the [Secure Pocket source archive](archive/master.zip). Open a terminal in the extracted project folder (the folder containing `link-server`).
3. Create Link’s Python environment and install its dependencies:

   ```sh
   python3 -m venv .venv
   .venv/bin/python -m pip install -r link-server/requirements-runtime.txt
   ```

4. Start Link in the background and open its settings page:

   ```sh
   sh ./link-server/start-link.sh
   ```

5. In the browser, open **Backup destination** and choose a writable folder. For an SMB share, mount it on Linux first, then choose its mounted folder. Save settings.

Stop Link with `sh ./link-server/stop-link.sh`. Logs are in `${XDG_CONFIG_HOME:-~/.config}/secure-pocket-link/`.

### Connect Android to Link

1. On the Link computer, open `http://127.0.0.1:8733/admin/`. The settings page says **Ready to receive Pocket recordings** when Link and the backup folder are ready.
2. Unlock settings using the code in Link’s `admin.token` file. Keep this admin code private. It is different from the **Link token** used by the phone.
3. In **Connect the Android app**, copy the **Link token** and configure an HTTPS address that the phone can reach. The API listens on `127.0.0.1:8732` by default, so it is not exposed to the network. Set up direct TLS or a trusted HTTPS proxy before connecting a phone. See the [Link guide](link-server/README.md#run-from-source) for TLS and network setup.
4. In Secure Pocket, open **Settings → Library Location → Secure Pocket Link**. Enter the HTTPS server address under **Local URL (preferred)** and the fallback HTTPS address under **Tailscale URL (fallback)**, if configured. Paste the Link token. The app tries the local address first, then the fallback.
5. Tap **Test connection**, then **Save settings**. Choose **Also keep a copy on this phone** if you want local playback, offline access, or on-phone processing. Turn it off for server-only imports; Link must be reachable when you import.

Use the exact HTTPS URLs shown by your TLS setup. When both addresses are set, the local route’s certificate must match the hostname in the fallback URL. Do not enter `127.0.0.1` or a plain `http://` address on Android: `127.0.0.1` on the phone means the phone itself. Do not expose Link’s unencrypted API port directly to a LAN or the internet.

## Optional: Docling server processing

Docling transcribes audio or formats summaries. It is separate from Link’s backup function. The repository’s Docker Compose stack runs Secure Pocket Link with upstream Docling Serve. It supports x86-64 and ARM64; the first build and first transcription can take time to download images and speech models.

You need [Docker Compose](https://docs.docker.com/compose/install/) on the computer or server. From the repository root, run:

```sh
docker compose up --build -d
```

Open Link settings at `http://127.0.0.1:8733/admin/`, choose a backup destination, and use **Check Docling**. Configure HTTPS for phone access as described in the [Docker guide](link-server/DOCLING.md#host-folders-and-api-access). In Android, open **Settings → Transcription → Docling formatting**, select **Pre**, **Post**, or **Both**, enable server processing, and save. Select a summary provider for **Post** or **Both**. **Pre** uses Docling for transcription, so a separate speech-to-text provider is not needed.

For a computer folder or NAS share, mount the share on the Docker host and follow [the Docker storage guide](link-server/DOCLING.md#host-folders-and-api-access). Keep Link’s config volume and library volume backed up together; the config volume contains the encryption key needed to read backups.

## Storage choices

- **Phone:** recordings stay in the Android app’s library.
- **Secure Pocket Link:** Link writes to its selected computer folder or mounted SMB share.
- **WebDAV NAS:** configure the NAS URL and credentials in **Settings → Library Location**. Keep the phone copy enabled if the server-only connection check is unavailable on your device.

Choose one primary library location in the app. Server-only imports require an active network connection to the selected server. WebDAV and SMB are different: WebDAV connects from Android; SMB shares should be mounted on the computer running Link.

## Transcription and summaries

Secure Pocket includes English Vosk speech-to-text for on-device transcription. Summary providers include an on-device model, Ollama, OpenAI, OpenRouter, and compatible HTTP services. Configure providers in **Settings → Transcription** and **Settings → Summaries**.

The repository does not include Gemma model weights because their license has separate distribution terms. To use Gemma 3 1B IT INT4, accept the publisher’s terms and download the `.task` model from [LiteRT Community](https://huggingface.co/litert-community/Gemma3-1B-IT/tree/main). In the app, open **Settings → Summaries → On-device LLM → Choose model file**, select the downloaded file, confirm its name appears, then save.

## Security and data

Link encrypts recordings and settings secrets at rest. Keep its `link.key` with the Link configuration backup; losing the key can make encrypted backups unreadable. Read the [security notes](docs/SECURITY.md) before choosing a storage setup.

The Pocket PKT01’s Bluetooth protocol does not provide authenticated encryption. Nearby attackers may be able to intercept or impersonate the recorder. Secure Pocket cannot protect recordings while they remain on the Pocket or while they travel over Bluetooth. A factory reset does not change this protocol limitation.

## License

Original Secure Pocket project code is available under the permissive [MIT License](LICENSE). You may use, copy, modify, reproduce, distribute, sublicense, and sell it, subject to the license terms. Third-party libraries, fonts, speech models, and other assets keep their own licenses; see the notices in `third_party/` and each component’s license. Gemma model weights are not included.

## More information

- [Secure Pocket Link setup, TLS, storage, and executable builds](link-server/README.md)
- [Docker and Docling setup](link-server/DOCLING.md)
- [Processing, transcripts, speakers, and summaries](docs/PROCESSING.md)
- [Security notes](docs/SECURITY.md)
- [Build Android from source](docs/BUILD.md)
