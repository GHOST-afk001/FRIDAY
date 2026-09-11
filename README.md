# Friday

A free, offline-first Android Demo V1 personal assistant written in Kotlin and Jetpack Compose. Friday uses Android's built-in `SpeechRecognizer` and `TextToSpeech`; no backend, subscription, or API key is required.

## Build

Install Android SDK Platform 35 and Build Tools 35.0.0, set `ANDROID_HOME` (or add `sdk.dir` to an untracked `local.properties`), and ensure `curl` and `unzip` are available. The source-only `gradlew` script downloads pinned Gradle 8.11.1 automatically, then run:

```bash
./gradlew assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. Install it on a USB-debugging-enabled phone with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Speech and Hindi TTS availability depend on the speech/TTS engines installed on the Android device.

## GitHub Actions APK build

The repository includes [the APK build workflow](.github/workflows/build-apk.yml). In GitHub, open the repository’s **Actions** tab, select **Build Friday debug APK**, then choose **Run workflow**. When it finishes, open that workflow run and download the **friday-debug-apk** artifact. It contains `app-debug.apk`.
