# Third-party components

## Voicute onnx-wakeword

FRIDAY optionally downloads the `onnx-wakeword` Android runtime and the `hey_friday.onnx` wake-word model during GitHub Actions builds. They are not committed to this repository.

- Project: https://github.com/voicute/onnx-wakeword
- Runtime license: see the upstream repository's `LICENSE` file.
- The wake-word model remains an upstream artifact and is fetched at build time rather than redistributed in this source repository.

The FRIDAY app uses the runtime only for local keyword spotting. Microphone audio is processed on-device by the wake-word runtime; the wake detector does not upload audio.

## ONNX Runtime Android

FRIDAY depends on `com.microsoft.onnxruntime:onnxruntime-android:1.20.0`. Refer to the package's upstream license and notices.

## Google Gemini API

FRIDAY's optional online brain uses the Google Gemini Developer API when the user supplies their own API key. The key is stored with Android Keystore-backed encryption and is never hard-coded into the APK.
