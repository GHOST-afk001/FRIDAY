# FRIDAY

FRIDAY is a phone-first Android personal assistant designed for Hindi, Hinglish and English. V2 adds a system-assistant path, an optional local **Hey Friday** wake-word runtime, natural local phone commands, persistent lightweight conversation memory, and an optional Gemini reasoning brain.

## What V2 actually does

- **Hands-free path:** FRIDAY can qualify for Android's Assistant role and run a local wake-word detector when the CI build includes the upstream `hey_friday.onnx` model.
- **Wake word:** the build downloads the `hey_friday.onnx` model and the Voicute Android inference runtime; the model runs locally after installation.
- **Speech:** Android `SpeechRecognizer` is used only after invocation. It is not used as an always-on wake loop.
- **Natural local commands:** time/date, timers, alarms, flashlight, volume, common apps, Maps locations/routes, dialer, and SMS compose.
- **Online intelligence:** optional Gemini 2.5 Flash provider with a user-supplied API key. No key is embedded in source or APK.
- **Memory:** recent conversation turns are stored locally in app-private preferences.
- **Voice:** Android TTS prefers an installed female Hindi/English voice and uses a controlled assistant-like rate/pitch. Exact voice cloning is not included.
- **Safety:** calls/messages are handed to Android's dialer/messaging UI rather than silently claiming they were completed.

## Important Android reality

A third-party Android app cannot guarantee unrestricted microphone access forever. The most reliable system path is to become the user's selected Assistant app. Android controls background microphone/audio access and may impose additional Samsung/One UI battery restrictions. If the custom wake runtime is unavailable, FRIDAY falls back to the system assistant invocation or the visible microphone button instead of pretending hands-free mode is active.

## Build

The normal source tree remains buildable without committing binary model files. The GitHub Actions workflow downloads the optional wake runtime and model at build time, then creates the APK artifact.

```bash
./gradlew test
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions

Open **Actions → Build Friday debug APK → Run workflow**. The workflow:

1. installs Android SDK 35 and JDK 17,
2. downloads the upstream Voicute wake runtime and `hey_friday.onnx`,
3. generates `model_info.json`,
4. runs unit tests,
5. builds `app-debug.apk`,
6. uploads `friday-debug-apk`.

Wake runtime/model files are intentionally not committed to Git because they are binary artifacts with their own upstream provenance.

## First phone setup

1. Install the APK.
2. Allow microphone permission.
3. Tap **ENABLE HANDS-FREE** and choose FRIDAY as the Android Assistant if the system offers the role.
4. Keep battery optimization from aggressively restricting FRIDAY if Samsung/One UI shows a warning.
5. Test the visible microphone first.
6. Then test: **“Friday” → wait for the assistant response → “Google map par Delhi Yamuna Vihar ki location lagao.”**
7. Optional: tap **AI BRAIN** and enter your own Gemini API key. It is encrypted using Android Keystore.

## Example commands

- “Friday time kya hua?”
- “Friday 5 minute ka timer.”
- “Friday 7 baje alarm laga do.”
- “Friday Google map par Delhi Yamuna Vihar ki location lagao.”
- “Friday India Gate ka route dikhao.”
- “Friday YouTube kholo.”
- “Friday torch on karo.”
- “Friday mummy ko message karo ki main 8 baje ghar aaunga.”
- “Friday Germany shift hone ke options research karke batao.” — handled by the optional online brain when configured.

## Limitations that are deliberately not hidden

- Exact owner-only speaker authentication is not claimed yet. A real speaker-verification model and enrollment/anti-spoof evaluation are still required before treating voice identity as strong authentication.
- Exact reproduction of the supplied female voice sample is not claimed. Android TTS can match the requested character (female, calm, controlled, assistant-like), but exact voice identity requires a compatible voice model and licensing.
- Live web research is not automatically guaranteed by the Gemini fallback. Current information should be routed through an explicit web/search tool in a later agent layer rather than pretending model knowledge is live.
- Emergency/SOS is not yet marked as a release-ready automatic feature in V2; it needs its own permission, location, contact configuration, escalation and device-QA gate.

## Architecture

```text
Voice / Assistant invocation
        ↓
Local Hey-Friday wake detector
        ↓
Android SpeechRecognizer
        ↓
Local command planner ─────→ Android public APIs
        ↓
Optional Gemini reasoning
        ↓
Memory + response
        ↓
Female-leaning Android TTS
```

See `docs/FRIDAY_FINAL_BLUEPRINT.md` for the safety and command taxonomy that governs the next milestones.
