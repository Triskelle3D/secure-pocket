# Secure Pocket

<img src="docs/assets/secure-pocket-icon.png" width="180" height="180" alt="Secure Pocket icon, a green padlock with binary digits and microphones">

Secure Pocket is an Android companion for the **Pocket voice recorder (PKT01)**. It imports recordings over Bluetooth, plays the original audio, and lets you create and edit transcripts and summaries. Keep the library on your phone or back it up to a computer or NAS you control.

## Choose your setup

| I want to… | What I need |
| --- | --- |
| Import and play recordings, with optional offline transcription and summaries | [Secure Pocket for Android](#1-install-secure-pocket-on-android) and a Pocket recorder |
| Back up to my computer or a NAS, including Samba/SMB | Android plus [Secure Pocket Link](#2-add-secure-pocket-link-for-backups) on Windows, Linux, Intel Mac, or Apple Silicon Mac |
| Use Docling to transcribe recordings or format summaries | Android plus [Link + Docling](#3-add-docling-for-server-processing), running in Docker Compose on a computer or server |

**Start with Android.** Link is optional. The Docker stack includes Link, so it can handle both backups and Docling from the same installation. If your NAS already offers WebDAV, Android can also back up directly to it.

## 1. Install Secure Pocket on Android

**You need** a Pocket PKT01, a phone with Android 11 or later and Bluetooth, and a USB cable for installing the app from your computer.

The Android app is currently built from source. Download the [source ZIP](http://swamp-hub:3000/Matt/secure-pocket/archive/master.zip) and extract it, or clone this repository.

1. Install Android Studio. In **SDK Manager**, install **Android SDK Platform 36** and **Android SDK Platform-Tools**. Use JDK 17 for the Gradle build. [Android Studio downloads](https://developer.android.com/studio)
2. Open the extracted repository in Android Studio. On the phone, enable **Developer options → USB debugging**, plug it in, and approve the debugging prompt.
3. Select the phone in Android Studio and run the **app** configuration with the **debug** build variant. The app opens when installation finishes.
4. In Secure Pocket, go to **Device → Connect Pocket** and grant the Bluetooth permission. With the recorder nearby, expand **Recordings on Pocket** and tap **Import** on a recording.
5. Open **Library** to play the audio or view the transcript and summary. The bundled English Vosk speech engine can transcribe on the phone. For summaries, [choose a model](#choose-a-summary-model).

If you prefer the terminal, run these commands from the repository root with JDK 17 selected and Platform-Tools on your path. Use `gradlew.bat` in Windows Command Prompt or `.\gradlew.bat` in PowerShell.

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 2. Add Secure Pocket Link for backups

**You need** a computer or server with a writable folder and a network your phone can reach. Link runs in the background. Its settings open in your browser.

1. Choose a download and open a terminal in the extracted folder.
   - **Current source, on Windows, macOS, or Linux.** Install Python 3.10 or newer and make it available on your PATH. Link uses Python's standard library. [Source ZIP](http://swamp-hub:3000/Matt/secure-pocket/archive/master.zip), [Python downloads](https://www.python.org/downloads/)
   - **Packaged Link, on Linux x64, Intel Mac, or Apple Silicon Mac.** Choose the ZIP matching your computer. The packaged executable includes its runtime. [Gitea Releases](http://swamp-hub:3000/Matt/secure-pocket/releases)
2. Run the start command from the table below.
3. Open [Link settings](http://127.0.0.1:8733/admin/) on the computer. Check that the library folder is writable and the page says **Ready to receive Pocket recordings**. To use a NAS folder through Link, mount the share on the computer and choose that folder here.
4. On Android, open **Settings → Library Location → Secure Pocket Link**. Copy the **Secure Pocket Link URL** and **Link token** from **Connect the Android app** in your browser. Keep both devices on the same Wi-Fi or tailnet.
5. Tap **Test connection**, then **Save settings**. Leave **Also keep a copy on this phone** on for Android processing, offline playback, and automatic backup. Use **Back up now · saved settings** to send recordings already on your phone.

| Computer | Start Link | Stop Link |
| --- | --- | --- |
| macOS or Linux | `sh ./link-server/start-link.sh` | `sh ./link-server/stop-link.sh` |
| Windows PowerShell | `.\link-server\start-link.ps1` | `.\link-server\stop-link.ps1` |

Closing the browser leaves Link running. The phone API uses **8732** by default. The settings page uses **8733** on the computer itself. Copy the pairing URL shown by Link, which uses an address such as `http://192.168.1.50:8732`. Keep Link reachable only on a trusted network or through a configured HTTPS endpoint. See the [Link guide](link-server/README.md) for logs, platform builds, and custom ports.

The v0.1.0 packages show the same information under **Link status**, **Pair your phone**, and **Phone access token**.

### Other storage choices

- **Phone** keeps the library and processing on Android when you choose on-device providers.
- **NAS with WebDAV.** On Android, choose **NAS → Connect with WebDAV** and enter the writable folder URL and credentials. Keep **Also keep a copy on this phone** on, save, and use **Back up now · saved settings**. This backup path passed testing on a Galaxy S20. The current Android build's WebDAV connection check fails on that phone and blocks server-only imports. Use Link for server-only NAS storage.
- **NAS with Samba/SMB.** Connect to the share from the computer running Link. In Link settings, choose the mounted folder as the library location. In Android, choose **NAS → Connect through Secure Pocket Link** and enter the Link URL and token.
- **Server-only imports** are enabled by switching off **Also keep a copy on this phone**. New imports go to the selected server as audio and require it to be reachable at import time. Keep the switch on if you want Android to transcribe and summarize new recordings automatically.

For either NAS option, enter your home Wi-Fi name and grant the permissions shown for Wi-Fi and scheduled backups. With Link, tap **Test connection**, then **Save settings**. [How to mount an SMB share for Link](link-server/README.md#use-a-sambasmb-share)

## 3. Add Docling for server processing

**You need** Docker Desktop with Compose on macOS or Windows, or Docker Engine with the Compose plugin on Linux. The stack supports x86-64 and ARM64. It uses upstream Docling Serve with the audio runtime needed for Whisper. The first build pulls container images, and the first transcription may download the speech model. [Docker Compose installation](https://docs.docker.com/compose/install/)

1. Download or clone the repository and open a terminal in its root, beside `compose.yaml`.
2. Start the stack.

   ```sh
   docker compose up --build -d
   ```

3. Open [Link settings](http://127.0.0.1:8733/admin/) on the Docker host. Click **Check Docling**. The combined stack enables Docling and sets its internal service address on first launch.
4. In Android **Settings → Transcription → Docling formatting**, choose a stage from the table below. Enter the **Secure Pocket Link URL** and **Link token** from the Docker Link settings page. If the page asks for a host address, use the Docker computer's LAN IP with port **8732**. Enable **Send audio or text to selected processing servers**, then tap **Save settings**.
5. Import a new recording and open its transcript in **Library**. To use **Post** or **Both**, select a working summary model in **Settings → Summaries** before importing.

| Docling stage | Transcription | Summary |
| --- | --- | --- |
| **Off** | Android uses the selected speech engine. | The selected summary model writes the Markdown. |
| **Pre** | Docling transcribes the audio and provides timed segments when available. | The selected summary model writes the Markdown. |
| **Post** | Android uses the selected speech engine. | The selected model writes the summary. Docling formats it. |
| **Both** | Docling transcribes the audio and provides timed segments when available. | The selected model writes the summary. Docling formats it. |

Docling and **Library Location** are independent. You can use Docling while storing the library on your phone. If Link is already running on the host, the [Docker guide](link-server/DOCLING.md) shows how to run the stack on different ports. It also covers mounting a host or NAS folder, persistent data, and stopping the stack.

## Choose a summary model

Secure Pocket leaves Gemma weights out of the repository and builds because they have separate licensing terms. Download models from their publishers and review their terms first. [Gemma Terms of Use](https://gemma.ai/resources/terms/)

| Model | Where it runs | How to get it |
| --- | --- | --- |
| **Gemma 3 1B IT, INT4** | On the Android phone. Tested on the Galaxy S20. | Sign in to Hugging Face, accept the Gemma terms, then download `gemma3-1b-it-int4.task` from **Files and versions**. [LiteRT model files](https://huggingface.co/litert-community/Gemma3-1B-IT/tree/main) |
| **IBM Granite 3.3 2B** | On a computer with Ollama. Apache 2.0. | Run `ollama pull granite3.3:2b`. [Ollama model page](https://ollama.com/library/granite3.3:2b) |
| **Mistral 7B Instruct v0.3** | On a computer with Ollama. Apache 2.0. | Run `ollama pull mistral:7b`. [Ollama model page](https://ollama.com/library/mistral:7b) |

- **On the phone.** Copy the downloaded `.task` file to your phone, open **Settings → Summaries → On-device LLM → Choose model file**, and select it. Wait for the filename to appear and tap **Save settings**. The app uses CPU-compatible MediaPipe LLM bundles on ARM64 phones. Gemma INT4 is about 555 MB and the app stores a private copy.
- **On your own computer.** Install Ollama and pull one of the models above. In **Settings → Summaries → Ollama**, enter the computer's reachable address, such as `http://192.168.1.50:11434`, and the exact model tag you pulled. Allow sending text and save. Ollama listens on the computer's loopback address by default. Follow its network setup instructions for your platform and keep it on a trusted network. [Ollama downloads](https://ollama.com/download), [Ollama network setup](https://docs.ollama.com/faq#how-can-i-expose-ollama-on-my-network)
- **With a hosted provider.** Choose **OpenAI** or **OpenRouter**, enter your API key and the provider's model ID, allow sending text, and save. For another OpenAI-compatible provider, choose **Compatible server** and enter its API base URL, including its API prefix such as `/v1`.

To try a different summary model on an existing recording, open its summary and tap **Regenerate summary**.

## If setup gets stuck

| Symptom | Check |
| --- | --- |
| Android cannot find the Pocket | Wake the recorder, keep it close to the phone, grant Bluetooth permissions, and disconnect any other app using the recorder. |
| The browser shows Link is ready, but Android cannot connect | Copy the phone pairing URL and token from Link. A `127.0.0.1` URL on Android points to Android itself. Check the phone's network and the computer's firewall. |
| A transcript is saved but there is no summary | Check the selected model, its file or API credentials, **Summarize and title new recordings**, and **Save settings**. |
| Docling is reachable but the first import is slow | The first conversion may need to download and load the speech model. Check Link's recent activity and `docker compose logs docling`. |

For playback, speaker labels, finding the recorder, and library controls, see [Recording and processing guide](docs/PROCESSING.md). For developers, run `./gradlew :app:testValidationUnitTest :app:connectedValidationAndroidTest` on an unlocked Android device. Connected tests use the separate `com.cairn.securepocket.isotest` package.
