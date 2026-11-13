# The example app

One screen around the four calls of the quick start, in one file (`ExampleActivity.kt`): the model loading with its
download progress, "Play sample" (a sample clip of the model, no microphone), "Recite" with "Stop", what the model
heard as it comes (tentative tokens dimmed), and "Diagnose". `QuickStart.kt` is the README's quick start, character
for character.

## Its key

The app's application id is `ai.quranlab.tajweedformer.example`. A key works for the first app that used it, so the
key you run this example with will belong to that identifier. Make a development key for it at
[sdk.quranlab.ai/keys](https://sdk.quranlab.ai/keys), or allow the identifier on your key's page; do not run the
example with the key of your own app.

Give the key to the build in the environment, so that it is never written into a file git tracks:

```sh
QURANLAB_KEY=qlk_v1_... ./gradlew :examples:quickstart:installDebug
```

Without it the app is built with a placeholder, and its first line says that the key is not one (`invalid_argument`).
In your own app the key is a string in your code: `TajweedClient(context, "qlk_v1_...")`.

## The release gate

`GateActivity` (`src/debug`, debug builds only) is not part of the example. It is the screen the SDK's release gate
starts on a phone: `scripts/phone_gate.py` builds this app against a built AAR (`-PsdkAar=<file>`), runs every sample
clip of the model through `model.listen(clip)` and reads `client.diagnose()`.

## The download proof

`DownloadProofActivity` (`src/debug`, debug builds only) is not part of the example either. It is the screen that
proves the model download on a phone: a host starts it by name with `adb` and reads its lines from the log (tag
`TJ_DL`). With the app's own Client and Quran Lab's server it removes the models and downloads them again (`mode
time`), or downloads what is missing (`mode fetch`), and prints the wall time and, for every file the SDK
downloaded, its bytes, its time, whether it came in ranges and over which protocol. The extras `on` and `off` name
debug switches of the SDK, which only a debuggable app has: `singleStream` (every file in one request) and
`backgroundDownloads` (the job that goes on with a download when the app is left). So a download that is cut in the
middle (the app's process ended) and goes on at the next start, and a download that goes on after the app was
left, can be watched on a real phone.
