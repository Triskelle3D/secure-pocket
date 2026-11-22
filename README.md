<h1 align="center">Secure Pocket</h1>

<p align="center">
  <img src="docs/assets/secure-pocket-icon.png" width="180" height="180" alt="Secure Pocket green padlock icon">
</p>

Secure Pocket is an Android companion for the **Pocket voice recorder (PKT01)**. It imports recordings over Bluetooth, plays audio, and creates editable transcripts and summaries. Keep recordings on your phone or back them up to a computer or NAS you control.

> **Weakest link in the chain: the Pocket’s Bluetooth connection.** The PKT01’s current firmware does not authenticate or encrypt BLE communications. Someone nearby may be able to intercept traffic or impersonate the recorder. Secure Pocket encrypts data after it reaches the phone and Link protects stored backups, but neither can secure audio on the Pocket or while it travels over BLE. Factory reset does not fix this limitation.

## Start here

1. Download the latest APK from [Secure Pocket Android releases](releases) to an Android 11+ phone. Open the APK, allow your browser or file manager to install apps if Android asks, then tap **Install** and **Open**.
2. Prepare the Pocket recorder as described below, then connect it in **Device → Connect Pocket**.
3. Set up a summary provider using [the summary guide](#choose-a-summary-provider).
4. Import a recording. Find its audio, transcript, and summary in **Library**.
5. Add Secure Pocket Link only if you want computer or NAS backups. Add Docling only if you want server-side transcription or formatting.

### Prepare or reset the Pocket

Before first pairing, factory-reset the Pocket:

1. **Back up anything you want to keep.** Factory reset permanently deletes every recording stored on the Pocket itself.
2. Tap the Pocket’s button three times.
3. Press and hold the button until the LED turns blue.
4. Keep the Pocket near the phone. In Secure Pocket, open **Device → Connect Pocket** and follow the Bluetooth permission prompts.

Resetting the Pocket does not delete the phone’s Secure Pocket library or Link backup.

## Choose a summary provider

New recordings get a summary only when a summary provider is selected and **Summarize and title new recordings** is on. Open **Settings → Summaries**, select a provider, then tap **Save settings**.

### On-device model

This keeps transcript text on the phone. Secure Pocket does not include model weights because the available Gemma model has separate license terms.

1. Review and accept the model publisher’s [Gemma terms](https://ai.google.dev/gemma/terms).
2. Download a compatible `.task` file, such as Gemma 3 1B IT INT4, from [LiteRT Community](https://huggingface.co/litert-community/Gemma3-1B-IT/tree/main).
3. In **Settings → Summaries**, choose **On-device LLM → Choose model file** and select the downloaded file.
4. Confirm the selected model name appears, enable **Summarize and title new recordings**, and tap **Save settings**.

### OpenAI or OpenRouter

These hosted options send transcript text to the selected provider. Create an API key with [OpenAI](https://platform.openai.com/api-keys) or [OpenRouter](https://openrouter.ai/settings/keys). In **Settings → Summaries**, select **OpenAI** or **OpenRouter**, enter the API key and a model ID supported by that provider, enable **Allow sending text to this provider**, and tap **Save settings**. Keep the key private.

### Ollama or another compatible server

For Ollama, install it on a computer, download a model with `ollama pull <model-name>`, then select **Ollama** in **Settings → Summaries**. Enter the model tag and an HTTPS server URL reachable from the phone. For another OpenAI-compatible service, choose **Compatible server**, enter its API base URL (including `/v1` when required), model ID, and API key. Enable **Allow sending text to this provider** and save. The phone must be able to reach the service; use HTTPS for network connections. See [Ollama’s OpenAI-compatible API guide](https://docs.ollama.com/api/openai-compatibility) for server details.

Summary generation can use a local on-device model or a server. When you choose a hosted provider, the transcript text is sent to that provider for processing.

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
3. In **Connect the Android app**, copy the **Link token**. Link’s API listens on `127.0.0.1:8732` by default. For HTTPS access, choose direct TLS, a trusted HTTPS proxy, or the optional Tailscale setup below.
4. In Secure Pocket, open **Settings → Library Location → Secure Pocket Link**. Enter your LAN HTTPS address under **Local URL (preferred)** if you have one. Enter the HTTPS fallback address under **Tailscale URL (fallback)** if configured. Paste the Link token. The app tries the local address first, then the fallback.
5. Tap **Test connection**, then **Save settings**. Choose **Also keep a copy on this phone** if you want local playback, offline access, or on-phone processing. Turn it off for server-only imports; Link must be reachable when you import.

Use the exact HTTPS URLs shown by your TLS setup. When both addresses are set, the local route’s certificate must match the hostname in the fallback URL. Do not enter `127.0.0.1` or a plain `http://` address on Android: `127.0.0.1` on the phone means the phone itself. Do not expose Link’s unencrypted API port directly to a LAN or the internet.

### Optional: Tailscale private access

Tailscale provides an HTTPS route to Link without opening a router port or configuring a public reverse proxy. Install [Tailscale on the Link computer](https://tailscale.com/download) and [on Android](https://tailscale.com/docs/install/android), then sign both devices in to the same tailnet. Keep Tailscale connected on the phone when using Link remotely.

With Link running on the computer, open a terminal there and run:

```sh
tailscale serve 8732
```

Tailscale Serve prints an HTTPS `*.ts.net` address for that computer. It forwards tailnet traffic to Link’s loopback API on port `8732`. Enter that printed HTTPS address in Android’s **Tailscale URL (fallback)** field and leave **Local URL (preferred)** blank if you only want to use Tailscale. If you also configure a LAN URL, the TLS certificate on that local route must match the hostname in the Tailscale URL. See [Tailscale Serve documentation](https://tailscale.com/docs/reference/tailscale-cli/serve). Do not use Tailscale Funnel; Serve keeps access within your tailnet.

Leave the terminal running while using this route; press **Ctrl+C** to stop it. Tailscale must stay connected on the phone and computer.

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

## Transcription

Secure Pocket includes English Vosk speech-to-text on the phone. **Settings → Transcription** also offers OpenAI speech-to-text and compatible speech APIs. **Docling Pre** or **Both** uses Docling for transcription and disables the other speech-engine choices. See [Docling setup](#optional-docling-server-processing) to configure it.

## Security and data

Link encrypts recordings and settings secrets at rest. Keep its `link.key` with the Link configuration backup; losing the key can make encrypted backups unreadable. Read the [security notes](docs/SECURITY.md) before choosing a storage setup.

The Bluetooth limitation described at the top is part of the PKT01 firmware. App and Link encryption protect data only after it leaves the recorder and while it is stored. A factory reset does not change BLE security.

## Planned for the future

- Add more transcription providers, including Whisper and other speech-to-text services, plus more summary LLM providers.
- Explore custom Pocket firmware that adds authentication and encryption to BLE communications.
- Explore firmware that lets the Pocket send recordings over built-in Wi-Fi directly to Secure Pocket Link or the Secure Pocket Android app, bypassing BLE for audio transfer.

## License

Original Secure Pocket project code is available under the permissive [MIT License](LICENSE). You may use, copy, modify, reproduce, distribute, sublicense, and sell it, subject to the license terms. Third-party libraries, fonts, speech models, and other assets keep their own licenses; see the notices in `third_party/` and each component’s license. Gemma model weights are not included.

## More information

- [Secure Pocket Link setup, TLS, storage, and executable builds](link-server/README.md)
- [Docker and Docling setup](link-server/DOCLING.md)
- [Processing, transcripts, speakers, and summaries](docs/PROCESSING.md)
- [Security notes](docs/SECURITY.md)
- [Build Android from source](docs/BUILD.md)
