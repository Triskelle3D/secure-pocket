# Android processing validation

Run the isolated instrumentation suite (the validation app ID is `com.cairn.securepocket.isotest`):

```sh
./gradlew :app:connectedValidationAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cairn.securepocket.ProcessingPipelineTest
```

This covers compatible speech and LLM wire contracts, Ollama URL normalization, Docling Off/Pre/Post/Both routing against a synthetic loopback service, local Vosk transcription, and cloud missing-key validation. `ProviderSettingsTest` walks every speech, Docling, and summary provider choice, library destination, and color theme through the pending Settings draft and Save action. `LibraryRecordActionsTest` checks participant roster persistence, manual speaker labeling, rename, and delete using one synthetic vault record. Test recordings are imported only into the `.isotest` vault and deleted during teardown.

`LibrarySearchTest` imports one uniquely titled fixture in `.isotest`, verifies matching search and a no-match query, then removes the fixture and restores the isolated package settings. Run it alone with:

```sh
./gradlew :app:connectedValidationAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cairn.securepocket.LibrarySearchTest
```

Run the settings and record-action UI checks on their own with:

```sh
./gradlew :app:connectedValidationAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cairn.securepocket.ProviderSettingsTest
./gradlew :app:connectedValidationAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cairn.securepocket.LibraryRecordActionsTest
```

## Live Secure Pocket Link Docling

The live integration test uses the same `docling_url` and `docling_token` instrumentation arguments as Settings. The URL is required; the token is optional. It exercises Pre, Post, and Both. A synthetic compatible LLM handles summary generation, so this check needs no cloud API key.

```sh
./gradlew :app:connectedValidationAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.cairn.securepocket.ProcessingPipelineTest#liveDoclingPrePostAndBoth \
  -Pandroid.testInstrumentationRunnerArguments.docling_url="$DOCLING_URL" \
  -Pandroid.testInstrumentationRunnerArguments.docling_token="$DOCLING_TOKEN"
```

Supply secrets from a local secret manager or environment variables. Do not put a real token in source control, a checked-in test property, or a captured test log. When no URL is provided, the live test is reported as skipped.

## Local Gemma

`provisionedGemmaSummarizesLocalTranscriptAndIsRemoved` is conditional on a model at the validation app's `no_backup/summary.task`. The production app and validation app have separate private storage. To temporarily reuse the user's already selected model without writing it to shared storage or the host, copy directly between the two app sandboxes after installing the validation APK:

```sh
adb shell run-as com.cairn.securepocket.isotest sh -c 'if [ -e no_backup/summary.task ]; then cp no_backup/summary.task no_backup/summary.task.validation-backup; fi'
adb exec-out run-as com.cairn.securepocket cat no_backup/summary.task \
  | adb shell run-as com.cairn.securepocket.isotest sh -c 'cat > no_backup/summary.task'
```

Run the single Gemma test with:

```sh
adb shell am instrument -w \
  -e class com.cairn.securepocket.ProcessingPipelineTest#provisionedGemmaSummarizesLocalTranscriptAndIsRemoved \
  com.cairn.securepocket.isotest.test/androidx.test.runner.AndroidJUnitRunner
```

The test removes its temporary validation copy and restores any previous validation model backup in `finally`; it does not change the production app's selected model. If the source model is absent, the test skips. If the test process is forcibly stopped, restore the prior validation model manually with `adb shell run-as com.cairn.securepocket.isotest sh -c 'if [ -e no_backup/summary.task.validation-backup ]; then mv no_backup/summary.task.validation-backup no_backup/summary.task; else rm -f no_backup/summary.task; fi'`.

## Synthetic WebDAV backup

The opt-in `isolatedWebDavPreflightAndLibrarySync` test requires the synthetic LAN fixture URL and `ACCESS_FINE_LOCATION` granted to `.isotest` before launch (the test never changes permissions). It temporarily sets NAS settings only in `.isotest`, validates PROPFIND, then runs a forced library backup of one synthetic recording. The phone must be connected to Wi-Fi; the test uses its current SSID only for the isolated validation settings.

```sh
./gradlew :app:connectedValidationAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.cairn.securepocket.ProcessingPipelineTest#isolatedWebDavPreflightAndLibrarySync \
  -Pandroid.testInstrumentationRunnerArguments.webdav_url="$WEBDAV_URL"
```

Grant permission before the run with `adb shell pm grant com.cairn.securepocket.isotest android.permission.ACCESS_FINE_LOCATION`. Revoke it afterward with `adb shell pm revoke com.cairn.securepocket.isotest android.permission.ACCESS_FINE_LOCATION` if it was not previously granted.

For a direct transport diagnostic that pins PROPFIND to the active Wi-Fi `Network` and reports the exact response or exception, run:

```sh
./gradlew :app:connectedValidationAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.cairn.securepocket.WebDavProbeTest \
  -Pandroid.testInstrumentationRunnerArguments.webdav_url="$WEBDAV_URL"
```

The fixture credentials default to `SyntheticTest` / `FixtureOnly`; override with `webdav_user` and `webdav_password` arguments only if the local fixture has different credentials. This checks the WebDAV storage route; Link-specific `/index.json` behavior requires a Secure Pocket Link server fixture and is not exercised by the WebDAV service.
