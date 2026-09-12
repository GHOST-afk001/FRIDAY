# FRIDAY Final Blueprint v1

## Goal

FRIDAY is a phone-first Android personal assistant for the Galaxy A56 5G (Android 16). The design is offline-first, privacy-conscious, and action-oriented. The assistant must understand natural Hindi, Hinglish, and English rather than relying on brittle exact phrases.

The implementation must never claim an action succeeded unless the Android API reports success or the target application was actually launched.

## Architecture

```text
                    USER VOICE
                        |
                 [Wake / Invocation]
                        |
              [Speaker Verification]
                        |
                [Speech Recognition]
                        |
              [Context + Intent Layer]
                        |
              [Local Agent / Planner]
                 /          |          \
          PHONE TOOLS   MEMORY       ONLINE BRAIN
             |             |              |
      Android APIs     local data     web/cloud AI
             \             |              /
              \------ [Action Plan] -----/
                        |
                 [Safety Gate]
                        |
               [Execute + Verify]
                        |
                 [TTS Response]
                        |
                    STANDBY
```

## Wake and background policy

1. Preferred system integration: `VoiceInteractionService` and Android Assistant role.
2. The selected assistant service stays available for system voice interaction/hotword support.
3. Do **not** use a permanently looping `SpeechRecognizer` as the primary wake-word engine. Android documents `SpeechRecognizer` as unsuitable for continuous recognition.
4. A custom "Friday" wake word requires a real local keyword-spotting engine/model. It must not be faked with repeated cloud speech recognition.
5. If the custom wake engine is unavailable, FRIDAY must expose a clear fallback: system assistant invocation / visible mic / notification action. Never silently pretend that hands-free wake is active.
6. Speaker verification is a secondary local security signal, not a perfect biometric. Sensitive actions must still use confirmation or Android's own authentication when appropriate.

## State machine

```text
OFFLINE/IDLE
  -> WAKE_DETECTED
  -> VERIFY_SPEAKER
  -> LISTENING
  -> UNDERSTAND
  -> PLAN
  -> SAFETY_CHECK
  -> EXECUTE
  -> VERIFY_RESULT
  -> RESPOND
  -> IDLE

Any state -> ERROR_RECOVERY -> IDLE
Emergency -> EMERGENCY_MODE -> ESCALATE/RESOLVE -> IDLE
```

No state may create an uncontrolled recognition loop. Every recognizer/session has explicit start, stop, timeout, cancellation, and destroy handling.

## Command taxonomy

### Tier 0: no-risk local commands

These should execute locally and without internet whenever the relevant Android API exists.

- "Friday time kya hua?"
- "Aaj ki date batao"
- "Alarm laga do 7 baje"
- "5 minute ka timer"
- "Volume kam karo"
- "Bluetooth settings kholo"
- "Wi-Fi settings kholo"
- "Camera kholo"
- "Calculator kholo"
- "YouTube kholo"
- "Settings kholo"
- "Phone settings kholo"
- "Flashlight on/off" (only if a supported camera torch is available)
- "Music pause/play"
- "Friday so jao" / "stop listening" / "standby"

### Tier 1: navigation and app intents

- "Google Maps par Yamuna Vihar Delhi dikhao"
- "Mujhe India Gate ka route dikhao"
- "Home ka route kholo"
- "WhatsApp kholo"
- "Instagram kholo"
- "Chrome kholo"
- "Files kholo"
- "Messages kholo"

Use public Android intents. For maps use `geo:`/`ACTION_VIEW` or navigation intents. Do not automate Google Maps' private UI.

### Tier 2: communications

- "Papa ko call karo"
- "Mummy ko message draft karo"
- "Mummy ko bolo main 8 baje ghar aaunga"
- "Boss ko call dialer mein kholo"

Resolve contacts only after explicit contact permission or a user-selected contact. Prefer `ACTION_DIAL` for normal calls because it does not require `CALL_PHONE`; direct `ACTION_CALL` is reserved for an explicit, confirmed user request and requires the permission. SMS composition should use `ACTION_SENDTO` with `smsto:` when possible.

For messages, default behavior is **draft/preview/confirmation**. FRIDAY must not silently send a sensitive or high-impact message just because an AI model inferred it.

### Tier 3: contextual agent tasks

Examples:

- "Friday principal ko 3 din ki professional leave application bana do, ghar mein urgent kaam hai."
- "Friday kal client ke office jaana hai, location ready rakhna."
- "Friday meri mummy ko bol dena main 8 baje ghar aaunga."
- "Friday Germany shift hone ke options research karke compare karo."

The agent extracts intent, entities, constraints, missing information, and required tools. It may ask one concise clarification when a missing value materially changes the action.

### Tier 4: emergency / safety

High-confidence phrases include:

- "Friday main khatre mein hoon"
- "Friday danger hai"
- "Friday mujhe help chahiye"
- "Friday emergency"
- "Friday koi mera peecha kar raha hai"
- "Friday meri jaan ko khatra hai"
- "Friday emergency call 112"

Emergency handling is **not** a generic keyword trigger. It uses context scoring plus a strict emergency intent classifier.

## Emergency workflow

```text
EMERGENCY PHRASE
      |
VOICE VERIFIED?
  /          \
 no           yes
 |             |
reject      EMERGENCY MODE
               |
        capture exact utterance
               |
       get fresh location
               |
      create maps location link
               |
      alert configured family
          /            \
       call           message
          \            /
           \
        escalation policy
               |
       optional 112 flow
               |
         verify status
               |
       truthful response
```

Emergency contacts must be configured by the user in FRIDAY settings before use. Store only the minimum required information. Never invent a contact or number.

Location strategy:

1. Request `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION` as needed.
2. Prefer a fresh current location when available.
3. If fresh location fails, use a clearly labelled last-known location with timestamp.
4. If no location is available, say so and continue the alert without pretending a location exists.
5. Location services being disabled must produce a clear fallback.

Emergency message should contain:

- emergency status
- exact user utterance/situation summary
- timestamp
- current/last-known location status
- maps link when available
- request to call the user

FRIDAY must never say "police/ambulance has been informed" unless the actual supported operation completed successfully. Android restrictions and device behavior can prevent background calls, so the app must report the real outcome.

## Confirmation policy

### No confirmation

Safe, reversible, local actions:

- open an app
- open settings
- show a location
- read time/date
- start a timer
- draft text without sending

### Confirmation required

Potentially consequential actions:

- send a message
- make a direct call
- delete files
- change important settings
- share sensitive location with a newly chosen recipient
- financial or account actions

Accepted confirmations:

- "yes"
- "haan"
- "haan boss"
- "yes boss"
- "kar do"

Accepted cancellations:

- "no"
- "nahi"
- "cancel"
- "rehne do"
- "stop"

The confirmation must be bound to the pending action and expire quickly.

## Voice identity

FRIDAY may maintain a local speaker profile for the owner. The profile should be treated as a convenience/security signal, not as cryptographic authentication. Noise, illness, microphone changes, recordings, and spoofing can cause false accept/reject decisions.

For high-risk actions, use voice verification plus an additional explicit confirmation or Android device authentication where available.

Never upload a raw voice profile by default.

## Offline mode

When internet is unavailable:

- wake/invocation works only if the installed local wake engine/system assistant path is available
- local speech recognition may work if an on-device recognizer/model is available
- local command parsing works
- phone intents work
- local memory works
- TTS works if a local TTS engine/language pack is installed
- emergency workflow can still prepare location and phone/SMS actions where Android/network permits
- current web information is unavailable
- cloud AI is unavailable

Offline FRIDAY must degrade gracefully instead of showing a generic "I only know basic commands" message.

## Online mode

When internet is available:

- cloud AI may improve open-ended reasoning/writing
- web/current-information tools may be used
- online speech recognition may improve recognition
- cloud fallback must never be required for basic phone safety controls

API keys must never be hard-coded into the APK.

## Android capability boundaries

- `VoiceInteractionService` is the preferred system-level assistant integration.
- The assistant role requires explicit user consent.
- Background microphone/location access is constrained by Android foreground-service and while-in-use rules.
- A foreground microphone service is not a loophole for unrestricted background listening.
- Android may require the phone to be unlocked for certain activity launches or sensitive operations.
- FRIDAY must never attempt to bypass the lock screen, security policy, or user authentication.
- Samsung One UI may impose additional battery/background behavior; the setup screen must guide the user if battery optimization or assistant-role configuration affects reliability.

## Permissions and setup

Only request permissions when the related feature is enabled:

- `RECORD_AUDIO` — voice features
- `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` — location and SOS
- `READ_CONTACTS` — only if FRIDAY resolves contacts directly
- `CALL_PHONE` — only if direct calling is implemented; otherwise use `ACTION_DIAL`
- `POST_NOTIFICATIONS` — notifications on supported Android versions
- notification listener access — explicit user-controlled system setting, only if notification reading is enabled
- foreground-service permissions/types — only when a foreground service is actually needed

Every denied permission must have a graceful fallback.

## Action verification

Every tool returns one of:

- `SUCCESS`
- `NEEDS_CONFIRMATION`
- `NEEDS_PERMISSION`
- `NOT_AVAILABLE`
- `FAILED`

The response layer must map the result to a truthful user-facing sentence.

Never return `SUCCESS` merely because `startActivity()` was called. Confirm that the intent resolved and started without throwing; for operations whose completion cannot be observed, report that the requested screen/action was opened or handed off rather than claiming the external app completed it.

## Build and QA gates

Before considering a release candidate:

1. `./gradlew --no-daemon assembleDebug`
2. `./gradlew --no-daemon test`
3. `git diff --check`
4. AndroidManifest validation
5. no binary model files accidentally committed unless deliberately required and reviewed
6. no API keys/secrets
7. CI artifact exists
8. install on Galaxy A56 5G Android 16
9. test screen locked/unlocked
10. test internet on/off
11. test microphone permission allow/deny
12. test location permission allow/deny
13. test contacts permission allow/deny
14. test no Maps app / alternate map app
15. test no speech service
16. test recognizer timeout/error/busy cases
17. test repeated wake and cancellation
18. test emergency with fresh location
19. test emergency with location disabled
20. test emergency contact unavailable
21. test false emergency phrase
22. test owner voice vs non-owner voice
23. test reboot and assistant-role recovery
24. test battery optimization impact

## Release principle

Do not optimize for the number of features. Optimize for: **truthful behavior, safe actions, predictable recovery, and real-phone reliability.**

The first final milestone should be a dependable assistant core with local phone actions and SOS, followed by the larger agent/online brain. A feature is not considered implemented until it has a real Android path, permission handling, error handling, and device QA.
