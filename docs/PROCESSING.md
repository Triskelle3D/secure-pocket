# Processing and library storage

Configure **Settings → Transcription** to use on-device Vosk (English), OpenAI speech transcription, or an OpenAI-compatible server such as a local Whisper service. The compatible base URL includes its API prefix, usually /v1. Choose an LLM and model ID under **Summaries**. Ollama takes the server URL (usually port 11434). OpenAI, OpenRouter, and compatible servers take the model ID supported by that service. Enable server processing after configuring the destination. New imports process automatically by default. Summary generation requires a selected LLM. Use Regenerate summary to upgrade an older result. The three views share the LLM title. Remote bundles contain files named with that title.

## Docling

Start the stock Docling stack described in the [main README](../README.md#secure-pocket-link--docling). Use the Link URL and phone token in Android's Docling settings. **Pre** transcribes the audio, **Post** formats the generated summary, and **Both** enables both stages. The summary provider still writes the summary.

## Library Location

- **This phone** keeps the library available offline.
- **Secure Pocket Link** saves bundles on the computer running Link. Copy its phone URL and token. Use **Keep a copy on this phone** if you want Android to process locally and back up in the background. With phone copies off, a new import needs the server available.
- **NAS** accepts a writable WebDAV folder or Link configured to store on a mounted NAS share. Enter the exact home Wi-Fi name. Android needs permission to read the network name and background location access to check it for scheduled backups while the app is closed.

## On-device summaries

On-device summaries require importing a CPU-compatible MediaPipe LLM `.task` model in **Settings → Summaries → On-device LLM**. The model is copied to private phone storage. Gemma 3 1B is one option; its terms must be accepted before downloading. Use Vosk for speech and turn Docling off for a pipeline that stays on the phone. Long transcripts are summarized in sections before the app combines them.

## Find and clear the Pocket

**Find Pocket** uses the address of the Pocket you connected to. When nearby, the signal meter uses Bluetooth advertisements or the current connection. Grant Android location access once. The app then saves the phone’s location automatically while the Pocket is connected, including when you move to another app screen. Android limits updates in the background according to its Location permission. Open the saved point in a map app. Optional alerts report unexpected Bluetooth disconnects while the app holds the connection.

To free space, expand **Recordings on Pocket**. After import, select **Delete** for the specific recording and confirm. The app re-downloads the recording and compares its SHA-256 with the verified phone copy before sending a delete request. It refreshes that date's inventory to confirm removal.

## Names and speaker labels

Press and hold a recording title to rename it or open its delete action. Rename changes the shared title in the phone library, including the Pocket row whenever a saved copy exists. A manual title survives re-summarization. Deleting from Summaries removes the summary from the phone. Deleting from Transcriptions removes the recording and its notes from the phone. Deleting from the Pocket still requires a matching saved audio copy.

Use **Who?** in the Transcriptions menu to save the participant roster. Open a recording's transcript, play a section in the timestamped list, and choose a speaker from its dropdown. Give each person several clear examples. **Apply names** saves timestamps and speaker labels in the transcript and queues a new summary. Matching compares voices on the phone using Vosk. It leaves ambiguous segments unidentified. Review matched names and correct any errors. Speaker analysis uses Vosk's English speech model, which can produce different wording from a previously selected speech provider.
