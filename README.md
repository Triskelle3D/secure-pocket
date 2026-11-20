# Secure Pocket

Secure Pocket imports recordings from the Pocket voice recorder over Bluetooth. The Android app manages playback, transcripts, summaries, and library storage. Secure Pocket Link receives library backups or direct imports on a computer or server you control.

| Component | Role | Where it runs |
| --- | --- | --- |
| **Secure Pocket** | Connect to Pocket, import and play recordings, edit transcripts and summaries, and choose where recordings are stored | Android |
| **Secure Pocket Link** | Receive recordings and backup bundles, serve them back to Android, and manage the destination from a browser | Windows, Linux, Intel Mac, Apple Silicon Mac |
| **Secure Pocket Link + Docling** | Run Link and upstream Docling together, with a browser settings page and persistent library storage | Docker Compose on x86-64 or ARM64 |

Install **Android alone** for phone storage. Add **Link** for a PC or NAS folder. Use **Link + Docling** when you want server transcription or summary formatting.

## Secure Pocket for Android

Build with JDK 17 and Android SDK 36. From this checkout:

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run Android validation on an attached, unlocked device with:

```sh
./gradlew :app:testValidationUnitTest :app:connectedValidationAndroidTest
```

The `validation` build type uses the separate application ID `com.cairn.securepocket.isotest`, so connected tests keep the production app's data intact. The normal `debug` build keeps `com.cairn.securepocket`.

On Windows, use `gradlew.bat :app:assembleDebug`. Open **Device → Connect Pocket**, then expand **Recordings on Pocket** to import a saved recording. **Library** has separate transcript and summary views.

### Library location

Open **Settings → Library Location** and choose the destination for new imports.

| Destination | Setup |
| --- | --- |
| **This phone** | Keep the working library on Android. No server required. |
| **Secure Pocket Link** | Enter the phone connection URL and token shown in Link. Use the computer’s LAN address, for example `http://192.168.1.50:8732`. |
| **NAS** | Choose a writable WebDAV folder, or point Android at a Link instance whose library folder is on a mounted NAS share. Enter the home Wi-Fi name for scheduled backups. |

Enable **Keep a copy on this phone** for Android processing, offline playback, and automatic backup. With phone copies off, new imports need a live connection to the selected server. Direct imports store audio only. Existing phone recordings stay in the phone library. Use **Test connection** before saving and **Back up now** to send existing phone recordings with the saved backup settings.

## Secure Pocket Link

Link runs as a background process. Its browser settings page manages the library folder and the connection details for Android. To save to a NAS, mount the share in the operating system first and choose a writable folder on that share. Keep the mount available while Link runs.

Get the desktop packages from [Gitea releases](http://swamp-hub:3000/Matt/secure-pocket/releases). The source launcher also works with Python 3.10 or later.

From a checkout with Python 3.10 or later, or from a packaged Link archive:

| Platform | Start | Stop |
| --- | --- | --- |
| macOS or Linux | `./link-server/start-link.sh` | `./link-server/stop-link.sh` |
| Windows PowerShell | `.\link-server\start-link.ps1` | `.\link-server\stop-link.ps1` |

The launcher opens the settings page. Closing the browser leaves Link running. See the [Link guide](link-server/README.md) for config paths, command-line options, and native packaging commands for Windows x64, Linux x64, Intel Mac, and Apple Silicon Mac.

The phone API uses port **8732** by default. The browser settings page is [http://127.0.0.1:8733/admin/](http://127.0.0.1:8733/admin/) on the computer running Link. On your phone, `localhost` refers to the phone itself. Use the separate connection address shown by Link. Keep the phone API on a trusted LAN, tailnet, or HTTPS endpoint and use the generated phone token.

Each recording is stored as a ZIP in the selected folder’s `library/` directory. Backups can include audio, transcript, summary, and title. Link can serve its library back to Android. The current desktop app provides settings and background backup hosting. Recording playback and editing are in the Android app.

## Secure Pocket Link + Docling

The Docker option runs Link and upstream Docling Serve with its Whisper audio runtime. Android connects to the same Link API it uses with the desktop app. The browser settings page configures Link and its Docling connection. See the [Docling upstream project](https://github.com/docling-project/docling).

```sh
docker compose up --build -d
```

Open [the Link settings page](http://127.0.0.1:8733/admin/) on the Docker host. Link stores the library, settings, and Docling model cache in persistent volumes. The first audio conversion can take longer while the model loads. Follow the [Docker guide](link-server/DOCLING.md) to mount a host folder or NAS share, change published ports, or stop the stack.

In Android **Settings → Transcription**, set the Link URL and token under **Docling formatting**, allow server processing, and save.

| Stage | What happens |
| --- | --- |
| **Off** | Android uses its selected speech engine and summary provider. |
| **Pre** | Docling transcribes audio and supplies the transcript and available timed segments. Android uses this text instead of its speech engine. |
| **Post** | Android’s selected LLM writes the summary. Docling converts that summary to Markdown. |
| **Both** | Docling transcribes and formats, with the selected LLM writing the summary between those stages. |

Docling configuration is separate from the library destination. You can store recordings on the phone while using server processing, or back up to Link while processing on the phone.

For model setup and other recording controls, see [Processing and library storage](docs/PROCESSING.md).
